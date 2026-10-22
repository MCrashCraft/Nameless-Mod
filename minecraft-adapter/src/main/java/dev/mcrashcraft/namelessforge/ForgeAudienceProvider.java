/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessforge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.namelessmc.plugin.common.NamelessPlugin;
import com.namelessmc.plugin.common.audiences.AbstractAudienceProvider;
import com.namelessmc.plugin.common.audiences.NamelessCommandSender;
import com.namelessmc.plugin.common.audiences.NamelessConsole;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.json.JSONOptions;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Translates the common audiences without exposing Minecraft state to HTTP workers. */
public final class ForgeAudienceProvider extends AbstractAudienceProvider implements AutoCloseable {
    // Select this exact game's JSON schema rather than Adventure's newest default.
    private static final GsonComponentSerializer MINECRAFT_JSON = GsonComponentSerializer.builder()
            .options(JSONOptions.byDataVersion().at(MinecraftCompatibility.dataVersion())).build();
    private final MinecraftServer server;
    private final NamelessPlugin plugin;
    private final ForgeCommandSender console;
    private volatile PlayerSnapshot players = new PlayerSnapshot(Map.of(), Map.of(), List.of());
    private volatile boolean active = true;

    public ForgeAudienceProvider(MinecraftServer server, NamelessPlugin plugin) {
        this.server = server;
        this.plugin = plugin;
        this.console = new ForgeCommandSender(this, server.createCommandSourceStack());
    }

    /** Call on the server thread after startup/login/logout, and before command dispatch. */
    public void refreshPlayers() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Player snapshots must be refreshed on the server thread");
        }
        if (!active) {
            return;
        }
        Map<UUID, ForgePlayer> byUuid = new HashMap<>();
        Map<String, ForgePlayer> byName = new HashMap<>();
        List<NamelessPlayer> online = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ForgePlayer snapshot = new ForgePlayer(this, player);
            byUuid.put(player.getUUID(), snapshot);
            byUuid.put(snapshot.uuid(), snapshot);
            byName.put(snapshot.username().toLowerCase(Locale.ROOT), snapshot);
            online.add(snapshot);
        }
        players = new PlayerSnapshot(Map.copyOf(byUuid), Map.copyOf(byName), List.copyOf(online));
    }

    /** Logout hooks can run before PlayerList removes the player; remove the snapshot explicitly. */
    public void removePlayer(UUID uuid) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Player snapshots must be changed on the server thread");
        }
        PlayerSnapshot before = players;
        ForgePlayer departed = before.byUuid().get(uuid);
        if (departed == null) {
            return;
        }
        Map<UUID, ForgePlayer> byUuid = new HashMap<>(before.byUuid());
        byUuid.values().removeIf(player -> player == departed);
        Map<String, ForgePlayer> byName = new HashMap<>(before.byName());
        byName.values().removeIf(player -> player == departed);
        List<NamelessPlayer> online = before.online().stream().filter(player -> player != departed).toList();
        players = new PlayerSnapshot(Map.copyOf(byUuid), Map.copyOf(byName), online);
    }

    NamelessPlugin plugin() {
        return plugin;
    }

    NamelessCommandSender sender(CommandSourceStack source) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Command source conversion must run on the server thread");
        }
        if (source.getEntity() instanceof ServerPlayer player) {
            return new ForgePlayer(this, player);
        }
        // Command blocks and other low-privilege non-player sources must not become consoles.
        return MinecraftCompatibility.hasPermission(source, 4) ? new ForgeCommandSender(this, source) : null;
    }

    Audience playerAudience(ServerPlayer player) {
        UUID actualUuid = player.getUUID();
        Object connection = player.connection;
        return audience(message -> enqueue(() -> {
            ServerPlayer current = server.getPlayerList().getPlayer(actualUuid);
            if (current != null && current.connection == connection) {
                current.sendSystemMessage(message);
            }
        }));
    }

    Audience commandAudience(CommandSourceStack source) {
        return audience(message -> enqueue(() -> source.sendSuccess(() -> message, false)));
    }

    void dispatchConsoleCommand(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        enqueue(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }

    /** Store delivery must finish on the server thread before its caller can acknowledge it. */
    boolean dispatchStoreCommand(String command) {
        if (!active || !server.isSameThread() || !validStoreCommand(command)) {
            return false;
        }
        StoreCommandResult completion = new StoreCommandResult();
        try {
            CommandSourceStack source = MinecraftCompatibility.withCallback(server.createCommandSourceStack(), completion::completed)
                    .withSuppressedOutput();
            // This method executes immediately. Its int return alone is insufficient: Forge's
            // cancelled CommandEvent can return 1 even though Brigadier never ran a command.
            server.getCommands().performPrefixedCommand(source, command);
            return active && completion.succeeded();
        } catch (RuntimeException | LinkageError failure) {
            // Do not expose fulfillment command text or mistake a partial execution for success.
            return false;
        }
    }

    static boolean validStoreCommand(String command) {
        return command != null && !command.isBlank() && command.length() <= 4_096
                && command.indexOf('\n') < 0 && command.indexOf('\r') < 0 && command.indexOf('\0') < 0;
    }

    /** A zero numeric result can succeed; a missing callback or any failed fork cannot. */
    static final class StoreCommandResult {
        private int callbacks;
        private boolean failed;
        void completed(boolean succeeded, int value) {
            callbacks++;
            if (!succeeded) failed = true;
        }
        boolean succeeded() { return callbacks > 0 && !failed; }
    }

    private void enqueue(Runnable runnable) {
        if (active) {
            plugin.scheduler().runSync(() -> {
                if (active) {
                    runnable.run();
                }
            });
        }
    }

    private Audience audience(Consumer<net.minecraft.network.chat.Component> send) {
        return new Audience() {
            @Override
            public void sendMessage(Identity source, Component message, MessageType type) {
                if (active) {
                    send.accept(minecraftComponent(message, server));
                }
            }
        };
    }

    @Override
    public NamelessConsole console() {
        return console;
    }

    @Override
    public Audience broadcast() {
        // Resolve the recipients when called, not when a repeating announcement is created.
        List<Audience> recipients = new ArrayList<>(players.online());
        recipients.add(console);
        return Audience.audience(recipients);
    }

    @Override
    public NamelessPlayer player(UUID uuid) {
        return players.byUuid().get(uuid);
    }

    @Override
    public NamelessPlayer playerByUsername(String username) {
        return username == null ? null : players.byName().get(username.toLowerCase(Locale.ROOT));
    }

    @Override
    public Collection<NamelessPlayer> onlinePlayers() {
        return players.online();
    }

    @Override
    public void close() {
        active = false;
        players = new PlayerSnapshot(Map.of(), Map.of(), List.of());
    }

    private record PlayerSnapshot(Map<UUID, ForgePlayer> byUuid, Map<String, ForgePlayer> byName,
                                  List<NamelessPlayer> online) { }

    static net.minecraft.network.chat.Component minecraftComponent(Component message) {
        return minecraftComponent(message, null);
    }

    static net.minecraft.network.chat.Component minecraftComponent(Component message, MinecraftServer server) {
        try {
            String safeJson = sanitizeChatJson(MINECRAFT_JSON.serialize(message));
            net.minecraft.network.chat.Component converted = MinecraftCompatibility.parseComponent(safeJson, server);
            if (converted != null) {
                return converted;
            }
        } catch (RuntimeException ignored) {
            // Website text must not crash chat or expose the response through a log exception.
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(message);
        if (plain.length() > 16_384) {
            plain = plain.substring(0, 16_384) + "â€¦";
        }
        return net.minecraft.network.chat.Component.literal(plain);
    }

    /** Preserve rich text, while website links can only open HTTP(S) URLs or suggest commands. */
    static String sanitizeChatJson(String json) {
        if (json.length() > 262_144) {
            throw new IllegalArgumentException("Website chat component exceeds the size limit");
        }
        JsonElement element = JsonParser.parseString(json);
        sanitize(element, 0);
        return element.toString();
    }

    private static void sanitize(JsonElement element, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("Website chat component exceeds the nesting limit");
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                sanitize(child, depth + 1);
            }
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            sanitizeClick(object, "clickEvent");
            sanitizeClick(object, "click_event");
            for (Map.Entry<String, JsonElement> child : object.entrySet()) {
                sanitize(child.getValue(), depth + 1);
            }
        }
    }

    private static void sanitizeClick(JsonObject owner, String key) {
        JsonElement raw = owner.get(key);
        if (raw == null) {
            return;
        }
        if (!raw.isJsonObject()) {
            owner.remove(key);
            return;
        }
        JsonObject click = raw.getAsJsonObject();
        String action = primitiveString(click.get("action"));
        String value = primitiveString(click.get("value"));
        if (value == null) {
            value = primitiveString(click.get("url"));
        }
        if (value == null) {
            value = primitiveString(click.get("command"));
        }
        if (value == null || value.length() > 4_096) {
            owner.remove(key);
            return;
        }
        // Different game schemas read different fields. Reject an ambiguous event
        // rather than validate one field while the native codec reads another.
        for (String field : List.of("value", "url", "command")) {
            if (click.has(field) && !value.equals(primitiveString(click.get(field)))) {
                owner.remove(key);
                return;
            }
        }
        if ("open_url".equals(action)) {
            try {
                URI uri = URI.create(value);
                if (uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme())
                        || "http".equalsIgnoreCase(uri.getScheme()))) {
                    return;
                }
            } catch (IllegalArgumentException ignored) { }
        } else if (("suggest_command".equals(action) || "run_command".equals(action)) && value.startsWith("/")) {
            // An upstream/custom translation may use run_command; require a deliberate player submission.
            click.addProperty("action", "suggest_command");
            return;
        }
        owner.remove(key);
    }

    private static String primitiveString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }
}
