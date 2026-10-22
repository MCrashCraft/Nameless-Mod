package com.namelessmc.plugin.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.namelessmc.java_api.modules.store.PendingCommandsResponse;
import com.namelessmc.plugin.common.audiences.AbstractAudienceProvider;
import com.namelessmc.plugin.common.audiences.NamelessConsole;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import com.namelessmc.plugin.common.command.AbstractScheduler;
import com.namelessmc.plugin.common.logger.AbstractLogger;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.audience.Audience;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic loopback orders only. No Minecraft server, real website or paid order is contacted. */
class StoreDeliveryTest {
    @TempDir Path temporary;
    private static final String UUID_TEXT = "00000000-0000-0000-0000-000000000001";

    static final class Scheduler extends AbstractScheduler {
        final Deque<Runnable> asynchronous = new ArrayDeque<>(), synchronous = new ArrayDeque<>();
        boolean serverThread;
        @Override public void runAsync(Runnable runnable) { asynchronous.add(runnable); }
        @Override public void runSync(Runnable runnable) { synchronous.add(runnable); }
        @Override public AbstractScheduledTask runTimer(Runnable runnable, Duration interval) {
            return new AbstractScheduledTask() { @Override public void cancel() {} };
        }
        @Override public AbstractScheduledTask runDelayed(Runnable runnable, Duration delay) { return null; }
        void async() { assertFalse(asynchronous.isEmpty()); serverThread = false; asynchronous.remove().run(); }
        void sync() {
            assertFalse(synchronous.isEmpty()); serverThread = true;
            try { synchronous.remove().run(); } finally { serverThread = false; }
        }
        void drain() {
            for (int i = 0; i < 20 && (!asynchronous.isEmpty() || !synchronous.isEmpty()); i++) {
                if (!asynchronous.isEmpty()) async();
                if (!synchronous.isEmpty()) sync();
            }
            assertTrue(asynchronous.isEmpty() && synchronous.isEmpty(), "pipeline should be bounded");
        }
    }
    static final class QuietLogger extends AbstractLogger {
        QuietLogger(ConfigurationHandler config) { super(config); }
        @Override public void info(String value) {} @Override public void warning(String value) {} @Override public void severe(String value) {}
        @Override public void info(Supplier<String> value) {} @Override public void warning(Supplier<String> value) {} @Override public void severe(Supplier<String> value) {}
    }

    final class Fixture implements AutoCloseable {
        final Path directory;
        final Scheduler scheduler = new Scheduler();
        final AtomicInteger dispatches = new AtomicInteger(), grants = new AtomicInteger(), fetches = new AtomicInteger(), acks = new AtomicInteger();
        final ExecutorService requests = Executors.newSingleThreadExecutor();
        final HttpServer http;
        final NamelessPlugin plugin;
        final Store store;
        volatile boolean failAck;
        volatile JsonObject response = response(101, "grant {username}", false);
        volatile List<Integer> acknowledged = List.of();

        Fixture(String name) throws Exception {
            directory = temporary.resolve(name);
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(requests);
            http.createContext("/", exchange -> {
                String route = exchange.getRequestURI().getPath();
                String body;
                int status = 200;
                if (route.endsWith("/info")) {
                    body = "{\"nameless_version\":\"2.1.2\",\"modules\":[\"Store\"],\"locale\":\"en_UK\"}";
                } else if (route.contains("pending-commands")) {
                    fetches.incrementAndGet(); body = response.toString();
                } else if (route.endsWith("/commands-executed")) {
                    acks.incrementAndGet();
                    JsonArray ids = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject().getAsJsonArray("commands");
                    List<Integer> parsed = new ArrayList<>();
                    ids.forEach(id -> parsed.add(id.getAsInt()));
                    acknowledged = List.copyOf(parsed);
                    status = failAck ? 503 : 200;
                    body = failAck ? "{\"error\":\"fixture:outage\"}" : "{\"success\":true}";
                } else {
                    status = 404; body = "{\"error\":\"fixture:unknown\"}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            http.start();
            plugin = new NamelessPlugin(directory, scheduler, QuietLogger::new, null, "fixture", "1.20.1");
            plugin.config().load();
            plugin.config().main().node("api", "url").set("http://127.0.0.1:" + http.getAddress().getPort() + "/api/");
            plugin.config().main().node("api", "key").set("synthetic-test-key-not-a-secret");
            plugin.config().main().node("api", "timeout").set("PT1S");
            plugin.config().main().node("api", "force-http-1").set(true);
            plugin.apiProvider().load(); scheduler.async();
            assertNotNull(plugin.apiProvider().apiIfCached());
            NamelessConsole console = new NamelessConsole(Audience.empty()) {
                @Override public void dispatchCommand(String command) { throw new AssertionError("Store must not use unverified queued dispatch"); }
                @Override public boolean dispatchStoreCommand(String command) {
                    assertTrue(scheduler.serverThread, "must execute on the native server thread");
                    dispatches.incrementAndGet();
                    // The write-ahead PREPARED receipt must already be durable before the native call.
                    assertTrue(Files.exists(directory.resolve("store-delivery/connection-1.json")));
                    JsonObject entries;
                    try { entries = JsonParser.parseString(Files.readString(directory.resolve("store-delivery/connection-1.json"))).getAsJsonObject().getAsJsonObject("entries"); }
                    catch (IOException error) { throw new AssertionError(error); }
                    assertTrue(entries.entrySet().stream().anyMatch(row -> row.getValue().getAsJsonObject().get("state").getAsString().equals("PREPARED")));
                    if (command.startsWith("grant ")) { grants.incrementAndGet(); return true; }
                    return false;
                }
            };
            plugin.setAudienceProvider(new AbstractAudienceProvider() {
                @Override public NamelessConsole console() { return console; }
                @Override public Audience broadcast() { return Audience.empty(); }
                @Override public NamelessPlayer player(UUID uuid) { return null; }
                @Override public NamelessPlayer playerByUsername(String username) { return null; }
                @Override public Collection<NamelessPlayer> onlinePlayers() { return List.of(); }
            });
            plugin.config().modules().node("store", "connection-id").set(1);
            plugin.config().modules().node("store", "command-executor", "enabled").set(true);
            plugin.config().modules().node("store", "command-executor", "interval").set("PT1S");
            store = new Store(plugin);
            store.load();
        }
        void poll() { store.retrievePendingCommands(); scheduler.drain(); }
        StoreDeliveryLedger.State state(int id) throws Exception {
            return StoreDeliveryLedger.State.valueOf(JsonParser.parseString(Files.readString(directory.resolve("store-delivery/connection-1.json")))
                    .getAsJsonObject().getAsJsonObject("entries").getAsJsonObject(Integer.toString(id)).get("state").getAsString());
        }
        PendingCommandsResponse pending() throws Exception { return plugin.apiProvider().apiIfCached().store().pendingCommands(1); }
        @Override public void close() { store.unload(); http.stop(0); requests.shutdownNow(); }
    }

    static JsonObject response(int id, String command, boolean online) {
        JsonObject action = new JsonObject();
        action.addProperty("id", id); action.addProperty("order_id", 9000 + id);
        action.addProperty("command", command); action.addProperty("require_online", online);
        JsonArray actions = new JsonArray(); actions.add(action);
        JsonObject customer = new JsonObject();
        customer.addProperty("customer_id", 7000 + id); customer.addProperty("user_id", 1);
        customer.addProperty("identifier", UUID_TEXT); customer.addProperty("username", "FixtureBuyer"); customer.add("commands", actions);
        JsonArray customers = new JsonArray(); customers.add(customer);
        JsonObject root = new JsonObject(); root.addProperty("online_mode", true); root.add("customers", customers);
        return root;
    }

    @Test void executionAndDurableSuccessPrecedeAcknowledgementAndDuplicatesNeverRegrant() throws Exception {
        try (Fixture fixture = new Fixture("sequence")) {
            fixture.store.retrievePendingCommands(); fixture.scheduler.async();
            assertEquals(0, fixture.dispatches.get()); assertEquals(0, fixture.acks.get());
            fixture.scheduler.sync();
            assertEquals(1, fixture.grants.get()); assertEquals(StoreDeliveryLedger.State.EXECUTED, fixture.state(101));
            assertEquals(0, fixture.acks.get(), "website must not be acknowledged before native success");
            fixture.scheduler.async();
            assertEquals(StoreDeliveryLedger.State.ACKNOWLEDGED, fixture.state(101));
            assertEquals(List.of(101), fixture.acknowledged);
            fixture.poll();
            assertEquals(1, fixture.grants.get()); assertEquals(1, fixture.dispatches.get());
        }
    }

    @Test void failedAcknowledgementRetriesWithoutDispatchingTheSuccessfulCommandAgain() throws Exception {
        try (Fixture fixture = new Fixture("ack-outage")) {
            fixture.failAck = true; fixture.poll();
            assertEquals(StoreDeliveryLedger.State.EXECUTED, fixture.state(101));
            assertEquals(1, fixture.grants.get());
            fixture.failAck = false; fixture.poll();
            assertEquals(1, fixture.grants.get()); assertEquals(1, fixture.dispatches.get());
            assertEquals(2, fixture.acks.get()); assertEquals(StoreDeliveryLedger.State.ACKNOWLEDGED, fixture.state(101));
        }
    }

    @Test void invalidNativeCommandsAreHeldAndNeverAcknowledgedOrAutomaticallyRetried() throws Exception {
        try (Fixture fixture = new Fixture("invalid")) {
            fixture.response = response(102, "not_a_real_command", false); fixture.poll(); fixture.poll();
            assertEquals(1, fixture.dispatches.get()); assertEquals(0, fixture.acks.get());
            assertEquals(StoreDeliveryLedger.State.FAILED, fixture.state(102));
        }
    }

    @Test void onlineOnlyOfflineCustomersRemainPendingWithoutAPreparedReceipt() throws Exception {
        try (Fixture fixture = new Fixture("offline")) {
            fixture.response = response(103, "grant {username}", true); fixture.poll();
            assertEquals(0, fixture.dispatches.get()); assertEquals(0, fixture.acks.get());
            assertFalse(Files.exists(fixture.directory.resolve("store-delivery/connection-1.json")));
        }
    }

    @Test void queuedDeliveryFromBeforeReloadCannotExecuteAfterReload() throws Exception {
        try (Fixture fixture = new Fixture("reload")) {
            fixture.store.retrievePendingCommands(); fixture.scheduler.async();
            fixture.store.unload(); fixture.store.load(); fixture.scheduler.sync();
            assertEquals(0, fixture.dispatches.get()); assertEquals(0, fixture.acks.get());
            fixture.poll(); assertEquals(1, fixture.grants.get());
        }
    }

    @Test void preparedCrashAmbiguityIsHeldAcrossNewLedgerInstances() throws Exception {
        try (Fixture fixture = new Fixture("prepared")) {
            var pending = fixture.pending(); var customer = pending.customers().get(0); var command = customer.pendingCommands().get(0);
            String fingerprint = Store.fingerprint(1, customer, command);
            assertTrue(new StoreDeliveryLedger(fixture.directory, 1).prepare(101, fingerprint));
            fixture.store.unload(); fixture.store.load(); fixture.poll();
            assertEquals(0, fixture.dispatches.get()); assertEquals(0, fixture.acks.get());
            assertEquals(StoreDeliveryLedger.State.PREPARED, fixture.state(101));
        }
    }

    @Test void durableExecutedRecoveryAcknowledgesWithoutReexecutingAfterReload() throws Exception {
        try (Fixture fixture = new Fixture("executed")) {
            var pending = fixture.pending(); var customer = pending.customers().get(0); var command = customer.pendingCommands().get(0);
            String fingerprint = Store.fingerprint(1, customer, command);
            StoreDeliveryLedger ledger = new StoreDeliveryLedger(fixture.directory, 1);
            ledger.prepare(101, fingerprint); ledger.executed(101, fingerprint);
            fixture.store.unload(); fixture.store.load(); fixture.poll();
            assertEquals(0, fixture.dispatches.get()); assertEquals(List.of(101), fixture.acknowledged);
            assertEquals(StoreDeliveryLedger.State.ACKNOWLEDGED, fixture.state(101));
        }
    }

    @Test void changedPayloadForAnExistingIdIsHeldInsteadOfExecutingAnotherGrant() throws Exception {
        try (Fixture fixture = new Fixture("changed")) {
            fixture.poll(); fixture.response = response(101, "grant changed", false); fixture.poll();
            assertEquals(1, fixture.grants.get()); assertEquals(1, fixture.acks.get());
        }
    }

    @Test void duplicateLedgerInstancesCannotBothPrepareTheSameDelivery() throws Exception {
        Path directory = temporary.resolve("concurrent"); Files.createDirectory(directory);
        StoreDeliveryLedger first = new StoreDeliveryLedger(directory, 1), second = new StoreDeliveryLedger(directory, 1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = workers.submit(() -> first.prepare(1, "a".repeat(64)));
            Future<Boolean> b = workers.submit(() -> second.prepare(1, "a".repeat(64)));
            assertNotEquals(a.get(2, TimeUnit.SECONDS), b.get(2, TimeUnit.SECONDS));
            assertEquals(StoreDeliveryLedger.State.PREPARED, new StoreDeliveryLedger(directory, 1).state(1, "a".repeat(64)));
        } finally { workers.shutdownNow(); }
    }

    @Test void malformedOrOversizedReceiptsFailClosed() throws Exception {
        Path directory = temporary.resolve("corrupt"); Files.createDirectory(directory);
        new StoreDeliveryLedger(directory, 1);
        Path journal = directory.resolve("store-delivery/connection-1.json");
        Files.writeString(journal, "{not-json");
        assertThrows(IOException.class, () -> new StoreDeliveryLedger(directory, 1));
        Files.writeString(journal, " ".repeat(8 * 1024 * 1024 + 1));
        assertThrows(IOException.class, () -> new StoreDeliveryLedger(directory, 1));
    }
}
