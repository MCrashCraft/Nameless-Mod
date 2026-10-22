/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded write-ahead receipts. Unresolved exchanges are never replayed or refunded automatically. */
public final class ExchangeJournal {
    public enum State { PREPARED, DEBITED, POSTING, CREDITED, CANCELLED, HELD }
    public record Entry(UUID id, UUID playerId, int userId, int dollars, List<CashWallet.CashSlot> debits,
                        State state, long updatedAt) {
        public Entry { debits = List.copyOf(debits); }
        public boolean unresolved() { return state != State.CREDITED && state != State.CANCELLED; }
    }
    public static final int MAX_DOLLARS = CashPlan.MAX_DOLLARS;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path directory;
    private final Path file;
    private final Object lock;

    public ExchangeJournal(Path dataDirectory) throws IOException {
        directory = dataDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(directory)) throw new IOException("Exchange directory is a symbolic link");
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid exchange directory");
        file = directory.resolve("exchange-journal.json");
        lock = LOCKS.computeIfAbsent(file, ignored -> new Object());
        synchronized (lock) {
            Map<UUID, Entry> entries = read();
            boolean changed = false;
            for (Entry entry : List.copyOf(entries.values())) {
                if (entry.unresolved() && entry.state() != State.HELD) {
                    entries.put(entry.id(), withState(entry, State.HELD));
                    changed = true;
                }
            }
            if (changed) persist(entries);
        }
    }

    public Entry prepare(CashWallet.Quote quote, int userId) throws IOException {
        validateQuote(quote);
        if (userId <= 0) throw new IOException("Invalid website user ID");
        synchronized (lock) {
            Map<UUID, Entry> entries = read();
            if (entries.values().stream().anyMatch(e -> e.playerId().equals(quote.playerId()) && e.unresolved()))
                throw new IOException("Player has an unresolved exchange");
            if (entries.size() >= MAX_ENTRIES) throw new IOException("Exchange receipt limit requires operator review");
            Entry entry = new Entry(UUID.randomUUID(), quote.playerId(), userId, quote.dollars(), quote.debits(), State.PREPARED, System.currentTimeMillis());
            entries.put(entry.id(), entry);
            persist(entries);
            return entry;
        }
    }

    public Entry transition(UUID id, State next) throws IOException {
        synchronized (lock) {
            Map<UUID, Entry> entries = read();
            Entry old = entries.get(id);
            if (old == null) throw new IOException("Missing exchange receipt");
            boolean allowed = next == State.HELD && old.unresolved()
                    || old.state() == State.PREPARED && (next == State.DEBITED || next == State.CANCELLED)
                    || old.state() == State.DEBITED && next == State.POSTING
                    || old.state() == State.POSTING && next == State.CREDITED;
            if (!allowed) throw new IOException("Unsafe exchange receipt transition");
            Entry changed = withState(old, next);
            entries.put(id, changed);
            persist(entries);
            return changed;
        }
    }

    public Optional<Entry> unresolved(UUID playerId) throws IOException {
        synchronized (lock) { return read().values().stream().filter(e -> e.playerId().equals(playerId) && e.unresolved()).findFirst(); }
    }
    public Optional<Entry> latest(UUID playerId) throws IOException {
        synchronized (lock) { return read().values().stream().filter(e -> e.playerId().equals(playerId)).max(Comparator.comparingLong(Entry::updatedAt)); }
    }
    public int heldCount() throws IOException {
        synchronized (lock) { return (int) read().values().stream().filter(e -> e.state() == State.HELD).count(); }
    }
    public Path file() { return file; }

    public static void validateQuote(CashWallet.Quote quote) throws IOException {
        try { CashPlan.validateQuote(quote); }
        catch (RuntimeException invalid) { throw new IOException("Invalid exchange debit plan"); }
    }

    private static Entry withState(Entry e, State state) {
        return new Entry(e.id(), e.playerId(), e.userId(), e.dollars(), e.debits(), state, System.currentTimeMillis());
    }
    private static UUID uuid(String value) throws IOException {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw new IOException("Noncanonical exchange UUID");
        return parsed;
    }
    private Map<UUID, Entry> read() throws IOException {
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) throw new IOException("Aliased exchange journal is refused");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new TreeMap<>();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) throw new IOException("Invalid or oversized exchange journal");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (integer(root.get("format")) != 1) throw new IOException("Unknown exchange journal format");
            JsonObject raw = root.getAsJsonObject("entries");
            if (raw.size() > MAX_ENTRIES) throw new IOException("Exchange receipt count exceeds the limit");
            Map<UUID, Entry> result = new TreeMap<>();
            Set<UUID> unresolvedPlayers = new HashSet<>();
            for (Map.Entry<String, JsonElement> row : raw.entrySet()) {
                UUID id = uuid(row.getKey());
                JsonObject value = row.getValue().getAsJsonObject();
                UUID playerId = uuid(value.get("player_uuid").getAsString());
                int userId = integer(value.get("website_user_id"));
                int dollars = integer(value.get("dollars"));
                JsonArray debits = value.getAsJsonArray("debits");
                if (debits.size() > CashPlan.INVENTORY_SLOTS) throw new IOException("Invalid exchange debit count");
                List<CashWallet.CashSlot> slots = new ArrayList<>();
                for (JsonElement debit : debits) {
                    JsonObject d = debit.getAsJsonObject();
                    slots.add(new CashWallet.CashSlot(integer(d.get("slot")), d.get("item").getAsString(), integer(d.get("before")), integer(d.get("take"))));
                }
                validateQuote(new CashWallet.Quote(playerId, dollars, slots));
                if (userId <= 0) throw new IOException("Invalid exchange website ID");
                Entry e = new Entry(id, playerId, userId, dollars, slots, State.valueOf(value.get("state").getAsString()), value.get("updated_at").getAsBigDecimal().longValueExact());
                if (e.updatedAt() < 0 || e.unresolved() && !unresolvedPlayers.add(playerId)) throw new IOException("Invalid or duplicated unresolved exchange");
                result.put(id, e);
            }
            return result;
        } catch (RuntimeException malformed) { throw new IOException("Malformed exchange journal requires review", malformed); }
    }
    private static int integer(JsonElement raw) throws IOException {
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) throw new IOException("Invalid exchange journal number");
        return raw.getAsBigDecimal().intValueExact();
    }
    private void persist(Map<UUID, Entry> entries) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        JsonObject raw = new JsonObject();
        entries.forEach((id, entry) -> {
            JsonObject row = new JsonObject();
            row.addProperty("player_uuid", entry.playerId().toString());
            row.addProperty("website_user_id", entry.userId());
            row.addProperty("dollars", entry.dollars());
            row.addProperty("state", entry.state().name());
            row.addProperty("updated_at", entry.updatedAt());
            JsonArray debits = new JsonArray();
            for (CashWallet.CashSlot slot : entry.debits()) {
                JsonObject d = new JsonObject();
                d.addProperty("slot", slot.slot()); d.addProperty("item", slot.itemId());
                d.addProperty("before", slot.beforeCount()); d.addProperty("take", slot.takeCount()); debits.add(d);
            }
            row.add("debits", debits); raw.add(id.toString(), row);
        });
        root.add("entries", raw);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES || Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) throw new IOException("Unsafe or full exchange journal");
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        Path temp = posix ? Files.createTempFile(directory, ".exchange-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(directory, ".exchange-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) throw new IOException("Exchange journal write made no progress");
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (posix) try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        } finally { Files.deleteIfExists(temp); }
    }
}
