package com.namelessmc.plugin.common;

import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import com.namelessmc.plugin.common.command.AbstractScheduler;
import com.namelessmc.plugin.common.logger.AbstractLogger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Loopback-only HTTP and concurrency regression tests; no website or game is contacted. */
class CoreHardeningTest {
    @TempDir Path temporary;

    static final class QueuedScheduler extends AbstractScheduler {
        final BlockingQueue<Runnable> asynchronous = new LinkedBlockingQueue<>();
        volatile Runnable timer;
        @Override public void runAsync(Runnable runnable) { asynchronous.add(runnable); }
        @Override public void runSync(Runnable runnable) { runnable.run(); }
        @Override public AbstractScheduledTask runTimer(Runnable runnable, Duration interval) { timer = runnable; return null; }
        @Override public AbstractScheduledTask runDelayed(Runnable runnable, Duration delay) { return null; }
        Runnable next() throws InterruptedException {
            Runnable next = asynchronous.poll(10, TimeUnit.SECONDS);
            assertNotNull(next, "expected a bounded background initialization task");
            return next;
        }
    }

    static final class QuietLogger extends AbstractLogger {
        QuietLogger(ConfigurationHandler config) { super(config); }
        @Override public void info(String value) {}
        @Override public void warning(String value) {}
        @Override public void severe(String value) {}
        @Override public void info(Supplier<String> value) {}
        @Override public void warning(Supplier<String> value) {}
        @Override public void severe(Supplier<String> value) {}
    }

    static final class MockWebsite implements AutoCloseable {
        final HttpServer server;
        final ExecutorService handlers = Executors.newFixedThreadPool(2);
        final AtomicInteger requests = new AtomicInteger();
        final CountDownLatch oldEntered = new CountDownLatch(1);
        final CountDownLatch releaseOld = new CountDownLatch(1);
        final CountDownLatch usersEntered = new CountDownLatch(1);
        final CountDownLatch releaseUsers = new CountDownLatch(1);
        volatile boolean blockOld;
        volatile boolean blockUsers;

        MockWebsite() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                boolean old = exchange.getRequestURI().getPath().startsWith("/old/");
                boolean users = exchange.getRequestURI().getPath().contains("users");
                if (old && blockOld) {
                    oldEntered.countDown();
                    try { releaseOld.await(30, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                if (users && blockUsers) {
                    usersEntered.countDown();
                    try { releaseUsers.await(30, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                String response = users
                        ? "{\"users\":[{\"id\":1,\"username\":\"FixtureCitizen\",\"integrations\":[{\"integration\":\"Minecraft\",\"identifier\":\"00000000000000000000000000000001\",\"username\":\"FixturePlayer\",\"verified\":true,\"linked_date\":0,\"show_publicly\":true}]}]}"
                        : "{\"nameless_version\":\"" + (old ? "2.1.0" : "2.1.2") + "\",\"modules\":[],\"locale\":\"en_UK\"}";
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (java.io.IOException ignored) {
                    // The timeout test intentionally closes the HTTP request first.
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }
        String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        @Override public void close() {
            releaseOld.countDown();
            releaseUsers.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    ConfigurationHandler config(String name) {
        ConfigurationHandler config = new ConfigurationHandler(temporary.resolve(name));
        config.load();
        return config;
    }

    static void configure(ConfigurationHandler config, String url, String timeout) throws Exception {
        config.main().node("api", "url").set(url);
        config.main().node("api", "key").set("loopback-test-key-never-a-real-secret");
        config.main().node("api", "timeout").set(timeout);
        config.main().node("api", "bypass-version-check").set(true);
        config.main().node("api", "force-http-1").set(true);
    }

    @Test void aDelayedResponseFromBeforeReloadCannotReplaceTheNewClient() throws Exception {
        try (MockWebsite website = new MockWebsite()) {
            website.blockOld = true;
            QueuedScheduler scheduler = new QueuedScheduler();
            ConfigurationHandler config = config("reload");
            // Coordination waits allow parallel native builds; nonblocking and
            // bounded HTTP timeout behavior have separate unchanged assertions.
            configure(config, website.url("/old/"), "PT15S");
            ApiProvider provider = new ApiProvider(scheduler, new QuietLogger(config), config);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                provider.load();
                Future<?> previous = workers.submit(scheduler.next());
                assertTrue(website.oldEntered.await(10, TimeUnit.SECONDS));
                assertTimeout(Duration.ofMillis(200), () -> assertNull(provider.apiIfCached()));

                provider.unload();
                configure(config, website.url("/new/"), "PT15S");
                provider.load();
                workers.submit(scheduler.next()).get(10, TimeUnit.SECONDS);
                NamelessAPI current = provider.apiIfCached();
                assertNotNull(current);
                assertEquals("/new/", current.apiUrl().getPath());
                website.releaseOld.countDown();
                previous.get(10, TimeUnit.SECONDS);
                assertSame(current, provider.apiIfCached(), "old HTTP completion must not republish stale credentials/client");
            } finally {
                website.releaseOld.countDown();
                workers.shutdownNow();
            }
        }
    }

    @Test void timeoutIsBoundedAndRepeatedCommandsDoNotHammerAnUnavailableWebsite() throws Exception {
        try (MockWebsite website = new MockWebsite()) {
            website.blockOld = true;
            QueuedScheduler scheduler = new QueuedScheduler();
            ConfigurationHandler config = config("timeout");
            configure(config, website.url("/old/"), "PT0.15S");
            ApiProvider provider = new ApiProvider(scheduler, new QuietLogger(config), config);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                provider.load();
                worker.submit(scheduler.next()).get(2, TimeUnit.SECONDS);
                assertNull(provider.apiIfCached());
                assertTrue(website.requests.get() <= 1);
                for (int i = 0; i < 8; i++) assertNull(worker.submit((Callable<NamelessAPI>) provider::api).get(1, TimeUnit.SECONDS));
                assertTrue(website.requests.get() <= 1, "transient failures must have a retry cooldown");
                website.releaseOld.countDown();
                // Advance the retry deadline without making the regression suite sleep 30 seconds.
                Field generationField = ApiProvider.class.getDeclaredField("generation");
                generationField.setAccessible(true);
                Object generation = generationField.get(provider);
                Field retryField = generation.getClass().getDeclaredField("retryAtNanos");
                retryField.setAccessible(true);
                retryField.setLong(generation, System.nanoTime() - 1);
                assertNotNull(worker.submit((Callable<NamelessAPI>) provider::api).get(2, TimeUnit.SECONDS),
                        "temporary outages must recover after cooldown without a manual reload");
            } finally {
                website.releaseOld.countDown();
                worker.shutdownNow();
            }
        }
    }

    @Test void announcementTimerDoesNotInitializeHttpOnTheServerThread() throws Exception {
        try (MockWebsite website = new MockWebsite()) {
            QueuedScheduler scheduler = new QueuedScheduler();
            NamelessPlugin plugin = new NamelessPlugin(temporary.resolve("announcement"), scheduler,
                    QuietLogger::new, null, "forge-test", "1.20.1");
            plugin.config().load();
            configure(plugin.config(), website.url("/new/"), "PT1S");
            plugin.apiProvider().load(); // Deliberately leave its asynchronous initialization queued.
            assertTimeout(Duration.ofMillis(200), () -> new AnnouncementTask(plugin).run());
            assertEquals(0, website.requests.get(), "timer must only read apiIfCached, never initialize HTTP");
        }
    }

    @Test void truncatedWebsendLogReadTerminatesInsteadOfSpinningAtEof() throws Exception {
        Path log = temporary.resolve("truncated.log");
        Files.writeString(log, "line\n");
        Method read = Websend.class.getDeclaredMethod("readToString", Path.class, int.class, int.class);
        read.setAccessible(true);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertEquals("line\n", read.invoke(null, log, 0, 50_000)));
    }

    @Test void olderGroupFailuresCannotEraseNewerGroupsOrAChangedReloadGeneration() throws Exception {
        GroupSync groups = new GroupSync(null); // This test exercises retry state without a server or API.
        Field cacheField = GroupSync.class.getDeclaredField("playerGroups");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentMap<UUID, Set<String>> cache = (ConcurrentMap<UUID, Set<String>>) cacheField.get(groups);
        Field epochField = GroupSync.class.getDeclaredField("generation");
        epochField.setAccessible(true);
        AtomicLong epoch = (AtomicLong) epochField.get(groups);
        UUID player = UUID.randomUUID();
        Set<String> older = Set.of("member"), newer = Set.of("staff");
        cache.put(player, newer);
        groups.requeue(epoch.get(), Map.of(player, older));
        assertEquals(newer, cache.get(player));
        long previousEpoch = epoch.get();
        groups.unload();
        cache.put(player, older);
        groups.requeue(previousEpoch, Map.of(player, older));
        assertEquals(older, cache.get(player), "stale reload callbacks cannot invalidate fresh retry state");
        groups.requeue(epoch.get(), Map.of(player, older));
        assertFalse(cache.containsKey(player), "current failed groups are retried on the next timer");
    }

    @Test void groupAcknowledgementRequiresTheSameObservedSnapshotAndReloadGeneration() throws Exception {
        GroupSync groups = new GroupSync(null);
        Field observedField = GroupSync.class.getDeclaredField("latestGroups");
        Field confirmedField = GroupSync.class.getDeclaredField("playerGroups");
        Field epochField = GroupSync.class.getDeclaredField("generation");
        observedField.setAccessible(true); confirmedField.setAccessible(true); epochField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentMap<UUID, Set<String>> observed = (ConcurrentMap<UUID, Set<String>>) observedField.get(groups);
        @SuppressWarnings("unchecked")
        ConcurrentMap<UUID, Set<String>> confirmed = (ConcurrentMap<UUID, Set<String>>) confirmedField.get(groups);
        AtomicLong epoch = (AtomicLong) epochField.get(groups);
        UUID player = UUID.randomUUID();
        observed.put(player, Set.of("staff"));
        groups.acknowledge(epoch.get(), Map.of(player, Set.of("member")));
        assertFalse(confirmed.containsKey(player), "a stale or queued send must not mark the newer groups delivered");
        groups.acknowledge(epoch.get(), Map.of(player, Set.of("staff")));
        assertEquals(Set.of("staff"), confirmed.get(player));
        long oldEpoch = epoch.get();
        groups.unload();
        observed.put(player, Set.of("staff"));
        groups.acknowledge(oldEpoch, Map.of(player, Set.of("staff")));
        assertFalse(confirmed.containsKey(player), "a completion from before reload must not acknowledge current state");
    }

    @Test void anOldUserListRequestCannotRepopulateEitherCacheAfterReload() throws Exception {
        try (MockWebsite website = new MockWebsite()) {
            QueuedScheduler scheduler = new QueuedScheduler();
            NamelessPlugin plugin = new NamelessPlugin(temporary.resolve("users"), scheduler,
                    QuietLogger::new, null, "forge-test", "1.20.1");
            plugin.config().load();
            configure(plugin.config(), website.url("/new/"), "PT15S");
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                plugin.apiProvider().load();
                worker.submit(scheduler.next()).get(10, TimeUnit.SECONDS);
                assertNotNull(plugin.apiProvider().apiIfCached());
                UserCache cache = plugin.userCache();
                website.blockUsers = true;
                cache.load();
                scheduler.timer.run();
                Future<?> previous = worker.submit(scheduler.next());
                assertTrue(website.usersEntered.await(10, TimeUnit.SECONDS));
                cache.unload();
                website.blockUsers = false;
                website.releaseUsers.countDown();
                previous.get(10, TimeUnit.SECONDS);
                assertTrue(cache.usernames().isEmpty());
                assertTrue(cache.minecraftUsernames().isEmpty());
                cache.load();
                scheduler.timer.run();
                worker.submit(scheduler.next()).get(2, TimeUnit.SECONDS);
                assertEquals(java.util.List.of("FixtureCitizen"), cache.usernames());
                assertEquals(java.util.List.of("FixturePlayer"), cache.minecraftUsernames());
                assertThrows(UnsupportedOperationException.class, () -> cache.usernames().add("mutation"));
                cache.unload();
                assertTrue(cache.usernames().isEmpty());
                assertTrue(cache.minecraftUsernames().isEmpty());
            } finally {
                website.releaseUsers.countDown();
                worker.shutdownNow();
            }
        }
    }
}
