package com.namelessmc.plugin.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Conservative write-ahead receipts: ambiguous executions are held, never blindly replayed. */
public final class StoreDeliveryLedger {
    public enum State { PREPARED, EXECUTED, ACKNOWLEDGED, FAILED }
    private record Entry(String fingerprint, State state, long updatedAt) { }
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path directory;
    private final Path file;
    private final int connectionId;
    private final Object lock;

    public StoreDeliveryLedger(Path dataDirectory, int connectionId) throws IOException {
        if (connectionId <= 0) throw new IOException("Store connection ID must be positive");
        this.connectionId = connectionId;
        directory = dataDirectory.toAbsolutePath().normalize().resolve("store-delivery");
        if (Files.isSymbolicLink(directory)) throw new IOException("Store receipt directory cannot be a symbolic link");
        if (!Files.exists(directory)) {
            if (Files.getFileStore(dataDirectory).supportsFileAttributeView("posix")) {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(directory);
            }
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Store receipt directory");
        file = directory.resolve("connection-" + connectionId + ".json");
        lock = LOCKS.computeIfAbsent(file, ignored -> new Object());
        synchronized (lock) { read(); }
    }

    public State state(int id, String fingerprint) throws IOException {
        synchronized (lock) {
            Entry entry = checked(read(), id, fingerprint);
            return entry == null ? null : entry.state();
        }
    }

    public boolean prepare(int id, String fingerprint) throws IOException {
        synchronized (lock) {
            Map<Integer, Entry> entries = read();
            if (checked(entries, id, fingerprint) != null) return false;
            if (entries.size() >= MAX_ENTRIES) throw new IOException("Store receipt limit reached; operator review required");
            entries.put(id, new Entry(fingerprint, State.PREPARED, System.currentTimeMillis()));
            persist(entries);
            return true;
        }
    }

    public void executed(int id, String fingerprint) throws IOException { transition(id, fingerprint, State.EXECUTED); }
    public void failed(int id, String fingerprint) throws IOException { transition(id, fingerprint, State.FAILED); }
    public void acknowledged(int id, String fingerprint) throws IOException { transition(id, fingerprint, State.ACKNOWLEDGED); }

    private void transition(int id, String fingerprint, State next) throws IOException {
        synchronized (lock) {
            Map<Integer, Entry> entries = read();
            Entry current = checked(entries, id, fingerprint);
            if (current == null) throw new IOException("Missing Store receipt");
            boolean allowed = current.state() == State.PREPARED && (next == State.EXECUTED || next == State.FAILED)
                    || (current.state() == State.EXECUTED || current.state() == State.ACKNOWLEDGED) && next == State.ACKNOWLEDGED;
            if (!allowed) throw new IOException("Ambiguous or failed Store delivery requires operator review");
            entries.put(id, new Entry(fingerprint, next, System.currentTimeMillis()));
            persist(entries);
        }
    }

    private static Entry checked(Map<Integer, Entry> entries, int id, String fingerprint) throws IOException {
        if (id <= 0 || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) throw new IOException("Invalid Store receipt identifier");
        Entry entry = entries.get(id);
        if (entry != null && !entry.fingerprint().equals(fingerprint)) throw new IOException("Store command ID payload changed; operator review required");
        return entry;
    }

    private Map<Integer, Entry> read() throws IOException {
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) throw new IOException("Aliased Store receipts are refused");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new TreeMap<>();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) throw new IOException("Invalid or oversized Store receipts");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("format").getAsInt() != 1 || root.get("connection_id").getAsInt() != connectionId) throw new IOException("Store receipt format or connection differs");
            JsonObject raw = root.getAsJsonObject("entries");
            if (raw.size() > MAX_ENTRIES) throw new IOException("Store receipt count exceeds the limit");
            Map<Integer, Entry> result = new TreeMap<>();
            for (Map.Entry<String, JsonElement> row : raw.entrySet()) {
                int id = Integer.parseInt(row.getKey());
                JsonObject value = row.getValue().getAsJsonObject();
                String fingerprint = value.get("fingerprint").getAsString();
                checked(Map.of(), id, fingerprint);
                if (!Integer.toString(id).equals(row.getKey())) throw new IOException("Noncanonical Store receipt ID");
                result.put(id, new Entry(fingerprint, State.valueOf(value.get("state").getAsString()), value.get("updated_at").getAsLong()));
            }
            return result;
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed Store receipts; operator review required", malformed);
        }
    }

    private void persist(Map<Integer, Entry> entries) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("connection_id", connectionId);
        JsonObject raw = new JsonObject();
        entries.forEach((id, entry) -> {
            JsonObject row = new JsonObject();
            row.addProperty("fingerprint", entry.fingerprint());
            row.addProperty("state", entry.state().name());
            row.addProperty("updated_at", entry.updatedAt());
            raw.add(Integer.toString(id), row);
        });
        root.add("entries", raw);
        byte[] bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Store receipt size limit reached");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) throw new IOException("Aliased Store receipts are refused");
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        Path temporary = posix
                ? Files.createTempFile(directory, ".delivery-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(directory, ".delivery-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // POSIX supports directory fsync. Windows has no portable Java equivalent.
            if (posix) {
                try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
