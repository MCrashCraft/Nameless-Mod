/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange.forge;

import dev.mcrashcraft.namelessexchange.core.CashPlan;
import dev.mcrashcraft.namelessexchange.core.CashWallet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** Only main inventory cash is eligible; this class never refunds an uncertain debit. */
public final class ForgeCashWallet implements CashWallet {
    private static final long MAX_PLAYER_FILE = 8L * 1024 * 1024;
    private static final long MAX_PLAYER_NBT = 32L * 1024 * 1024;
    private static final Set<String> STACK_KEYS = Set.of("id", "Count", "Slot", "tag", "ForgeCaps");
    private final ServerPlayer player;
    private final MinecraftServer server;
    private final UUID playerId;

    public ForgeCashWallet(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "Player is required");
        this.server = Objects.requireNonNull(player.getServer(), "Server is required");
        this.playerId = player.getUUID();
    }

    @Override public UUID playerId() { return playerId; }

    @Override public boolean isCurrent() {
        if (!onlineSession()) return false;
        GameType type = player.gameMode.getGameModeForPlayer();
        return type == GameType.SURVIVAL || type == GameType.ADVENTURE;
    }

    private boolean onlineSession() {
        return server.isSameThread() && !player.isRemoved() && player.connection != null
                && server.getPlayerList().getPlayer(playerId) == player;
    }

    @Override public Quote quote(int requestedDollars) {
        requireCurrent();
        return CashPlan.quote(playerId, requestedDollars, new ArrayList<>(captureCash().values()));
    }

    public int availableDollars() {
        requireCurrent();
        return CashPlan.availableDollars(new ArrayList<>(captureCash().values()));
    }

    @Override public DebitResult debitAndPersist(Quote quote) {
        boolean mutated = false;
        try {
            if (!isCurrent()) return noChange("You must remain connected in survival or adventure mode.");
            CashPlan.validateQuote(quote);
            if (!playerId.equals(quote.playerId())) return noChange("The cash quote belongs to another player.");
            Map<Integer, CashPlan.BillStack> before = captureCash();
            for (CashSlot debit : quote.debits()) {
                CashPlan.BillStack actual = before.get(debit.slot());
                if (actual == null || !actual.itemId().equals(debit.itemId()) || actual.count() != debit.beforeCount()) {
                    return noChange("Your quoted cash stacks changed. Request a new exchange.");
                }
            }
            Path dataFile = checkedPlayerFile();
            Map<Integer, CashPlan.BillStack> expected = new HashMap<>(before);
            for (CashSlot debit : quote.debits()) {
                int remaining = debit.beforeCount() - debit.takeCount();
                if (remaining == 0) expected.remove(debit.slot());
                else expected.put(debit.slot(), new CashPlan.BillStack(debit.slot(), debit.itemId(), remaining));
            }
            // Every slot and every amount above is validated before the first mutation.
            // Set the flag first: even a partially throwing inventory operation is uncertain.
            mutated = true;
            for (CashSlot debit : quote.debits()) player.getInventory().getItem(debit.slot()).shrink(debit.takeCount());
            player.getInventory().setChanged();
            if (!expected.equals(captureCash())) return uncertain();

            // The access transformer exposes this exact single-player vanilla save.
            // PlayerDataStorage catches IO failures internally, so its return is not proof.
            server.getPlayerList().save(player);
            forcePlayerFile(dataFile);
            CompoundTag persisted = readPlayerFile(dataFile);
            if (!isCurrent() || !persisted.hasUUID("UUID") || !playerId.equals(persisted.getUUID("UUID"))
                    || !expected.equals(savedMainCash(persisted)) || !expected.equals(captureCash())) return uncertain();
            return new DebitResult(DebitState.SAVED, "Cash debit was saved and verified.");
        } catch (IOException | RuntimeException | LinkageError failure) {
            return mutated ? uncertain() : noChange("Cash could not be safely validated; nothing was exchanged.");
        } finally {
            if (mutated) {
                try {
                    player.inventoryMenu.broadcastChanges();
                    if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
                } catch (RuntimeException ignored) {
                    // A failed client update does not roll back a persisted cash debit.
                }
            }
        }
    }

    @Override public void message(String text) {
        if (onlineSession() && text != null) player.sendSystemMessage(Component.literal(text.substring(0, Math.min(text.length(), 2_048))));
    }

    private void requireCurrent() {
        if (!isCurrent()) throw new IllegalArgumentException("Exchange cash while connected in survival or adventure mode.");
    }

    private Map<Integer, CashPlan.BillStack> captureCash() {
        if (!server.isSameThread()) throw new IllegalStateException("Cash inventory must be read on the server thread");
        Map<Integer, CashPlan.BillStack> result = new HashMap<>();
        for (int slot = 0; slot < CashPlan.INVENTORY_SLOTS; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            String itemId = key == null ? null : key.toString();
            if (CashPlan.denomination(itemId) == 0) continue;
            if (stack.getCount() < 1 || stack.getCount() > CashPlan.MAX_STACK
                    || stack.getCount() > stack.getMaxStackSize() || !plainSerializedStack(stack.save(new CompoundTag()))) {
                throw new IllegalArgumentException("Only plain, unmodified cash stacks of at most 64 bills can be exchanged.");
            }
            result.put(slot, new CashPlan.BillStack(slot, itemId, stack.getCount()));
        }
        return Map.copyOf(result);
    }

    /** hasTag alone is insufficient: Forge serializes capabilities outside the item tag. */
    static boolean plainSerializedStack(CompoundTag stack) {
        if (!STACK_KEYS.containsAll(stack.getAllKeys())) return false;
        for (String key : new String[]{"tag", "ForgeCaps"}) {
            if (stack.contains(key) && (!stack.contains(key, Tag.TAG_COMPOUND) || !stack.getCompound(key).isEmpty())) return false;
        }
        return true;
    }

    /** Read only the same 36 slots used by the quote, excluding armor and offhand cash. */
    static Map<Integer, CashPlan.BillStack> savedMainCash(CompoundTag playerData) throws IOException {
        if (!playerData.contains("Inventory", Tag.TAG_LIST)) throw new IOException("Saved inventory is missing");
        ListTag inventory = (ListTag) playerData.get("Inventory");
        if (inventory.size() > 128 || inventory.getElementType() != Tag.TAG_COMPOUND && !inventory.isEmpty()) {
            throw new IOException("Saved inventory is malformed");
        }
        Map<Integer, CashPlan.BillStack> result = new HashMap<>();
        boolean[] seen = new boolean[CashPlan.INVENTORY_SLOTS];
        for (int row = 0; row < inventory.size(); row++) {
            CompoundTag stack = inventory.getCompound(row);
            if (!stack.contains("Slot", Tag.TAG_BYTE)) throw new IOException("Saved inventory slot is malformed");
            int slot = stack.getByte("Slot") & 255;
            if (slot >= CashPlan.INVENTORY_SLOTS) continue;
            if (seen[slot]) throw new IOException("Duplicate saved inventory slot");
            seen[slot] = true;
            if (!stack.contains("id", Tag.TAG_STRING)) throw new IOException("Saved item ID is missing");
            String itemId = stack.getString("id");
            if (CashPlan.denomination(itemId) == 0) continue;
            if (!stack.contains("Count", Tag.TAG_BYTE) || stack.getByte("Count") < 1
                    || stack.getByte("Count") > CashPlan.MAX_STACK || !plainSerializedStack(stack)) {
                throw new IOException("Saved cash is not plain cash");
            }
            result.put(slot, new CashPlan.BillStack(slot, itemId, stack.getByte("Count")));
        }
        return Map.copyOf(result);
    }

    private Path checkedPlayerFile() throws IOException {
        Path directory = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory)) {
            throw new IOException("Player data directory is not a regular directory");
        }
        Path file = directory.resolve(playerId + ".dat");
        Path backup = directory.resolve(playerId + ".dat_old");
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(backup)
                || Files.exists(file, LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > MAX_PLAYER_FILE)) throw new IOException("Player save path is not safe");
        return file;
    }

    private static void forcePlayerFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > MAX_PLAYER_FILE) throw new IOException("Player save is unavailable");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
        // Java does not provide portable directory fsync on Windows. POSIX servers do.
        if (Files.getFileStore(file.getParent()).supportsFileAttributeView("posix")) {
            try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        }
    }

    private static CompoundTag readPlayerFile(Path file) throws IOException {
        try (var compressed = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var gzip = new GZIPInputStream(compressed);
             var input = new DataInputStream(gzip)) {
            return NbtIo.read(input, new NbtAccounter(MAX_PLAYER_NBT));
        }
    }

    private static DebitResult noChange(String reason) { return new DebitResult(DebitState.NO_CHANGE, reason); }
    private static DebitResult uncertain() {
        return new DebitResult(DebitState.UNCERTAIN, "Cash debit needs staff review. Do not repeat it or issue an automatic refund.");
    }
}
