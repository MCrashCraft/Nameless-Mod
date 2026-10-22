package dev.mcrashcraft.namelessforge;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ForgeSchedulerTest {
    @Test void reloadInsideMinecraftCallbackCanInitializeNewWebsiteWorkersAndReplies() throws Exception {
        BlockingQueue<Runnable> minecraft = new LinkedBlockingQueue<>();
        CountDownLatch newWebsiteWorker = new CountDownLatch(1);
        AtomicInteger replies = new AtomicInteger();
        try (ForgeScheduler scheduler = new ForgeScheduler(minecraft::add, ignored -> {})) {
            scheduler.runSync(() -> {
                scheduler.nextGeneration();
                scheduler.runAsync(newWebsiteWorker::countDown);
                scheduler.runSync(replies::incrementAndGet);
            });
            minecraft.remove().run();
            assertTrue(newWebsiteWorker.await(3, TimeUnit.SECONDS), "Reload must start new-generation API initialization");
            minecraft.remove().run();
            assertEquals(1, replies.get(), "Reload confirmation must not be mistaken for a stale response");
        }
    }

    @Test void announcementsCanScheduleTheirFirstPlayerWithZeroDelay() throws Exception {
        BlockingQueue<Runnable> minecraft = new LinkedBlockingQueue<>();
        AtomicInteger callbacks = new AtomicInteger();
        try (ForgeScheduler scheduler = new ForgeScheduler(minecraft::add, ignored -> {})) {
            assertNotNull(scheduler.runDelayed(callbacks::incrementAndGet, Duration.ZERO));
            assertThrows(IllegalArgumentException.class,
                    () -> scheduler.runTimer(callbacks::incrementAndGet, Duration.ZERO));
            Runnable callback = minecraft.poll(3, TimeUnit.SECONDS);
            assertNotNull(callback);
            callback.run();
            assertEquals(1, callbacks.get());
        }
    }

    @Test void websiteWorkerCannotRunMinecraftCallbackOnItsOwnThread() throws Exception {
        BlockingQueue<Runnable> minecraft = new LinkedBlockingQueue<>();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        AtomicReference<String> websiteThread = new AtomicReference<>();
        AtomicReference<String> callbackThread = new AtomicReference<>();
        try (ForgeScheduler scheduler = new ForgeScheduler(minecraft::add, errors::add)) {
            scheduler.runAsync(() -> {
                websiteThread.set(Thread.currentThread().getName());
                scheduler.runSync(() -> callbackThread.set(Thread.currentThread().getName()));
            });
            Runnable callback = minecraft.poll(3, TimeUnit.SECONDS);
            assertNotNull(callback);
            assertNull(callbackThread.get(), "Minecraft callback must stay queued until server executes it");
            callback.run();
            assertTrue(websiteThread.get().startsWith("namelessforge-api-"));
            assertEquals(Thread.currentThread().getName(), callbackThread.get());
            assertTrue(errors.isEmpty());
        }
    }

    @Test void reloadDropsQueuedCallbacksAndResponsesFromAnOldWebsiteRequest() throws Exception {
        BlockingQueue<Runnable> minecraft = new LinkedBlockingQueue<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finishOldRequest = new CountDownLatch(1);
        CountDownLatch oldRequestFinished = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        try (ForgeScheduler scheduler = new ForgeScheduler(minecraft::add, ignored -> {})) {
            scheduler.runSync(callbacks::incrementAndGet);
            scheduler.runAsync(() -> {
                started.countDown();
                try { finishOldRequest.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                scheduler.runSync(callbacks::incrementAndGet);
                oldRequestFinished.countDown();
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            scheduler.nextGeneration();
            finishOldRequest.countDown();
            assertTrue(oldRequestFinished.await(3, TimeUnit.SECONDS));
            minecraft.forEach(Runnable::run);
            minecraft.clear();
            assertEquals(0, callbacks.get());
            scheduler.runSync(callbacks::incrementAndGet);
            minecraft.remove().run();
            assertEquals(1, callbacks.get(), "New generation remains usable after a reload");
        } finally { finishOldRequest.countDown(); }
    }

    @Test void fullWebsiteQueueNeverRunsHttpOnCallerThread() throws Exception {
        CountDownLatch activeWorkers = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger notices = new AtomicInteger();
        AtomicInteger callerRuns = new AtomicInteger();
        Thread caller = Thread.currentThread();
        try (ForgeScheduler scheduler = new ForgeScheduler(Runnable::run, ignored -> notices.incrementAndGet())) {
            Runnable blockedWebsite = () -> {
                activeWorkers.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            };
            scheduler.runAsync(blockedWebsite);
            scheduler.runAsync(blockedWebsite);
            assertTrue(activeWorkers.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 200; i++) scheduler.runAsync(() -> {
                if (Thread.currentThread() == caller) callerRuns.incrementAndGet();
            });
            assertEquals(0, callerRuns.get());
            assertTrue(notices.get() >= 1, "Overload must be reported without caller-runs fallback");
        } finally { release.countDown(); }
    }

    @Test void repeatingTaskDoesNotFloodAnUnresponsiveMinecraftExecutor() throws Exception {
        BlockingQueue<Runnable> minecraft = new LinkedBlockingQueue<>();
        AtomicInteger calls = new AtomicInteger();
        try (ForgeScheduler scheduler = new ForgeScheduler(minecraft::add, ignored -> {})) {
            var timer = scheduler.runTimer(calls::incrementAndGet, Duration.ofMillis(50));
            assertNotNull(timer);
            Runnable first = minecraft.poll(3, TimeUnit.SECONDS);
            assertNotNull(first);
            Thread.sleep(180);
            assertTrue(minecraft.isEmpty(), "Pending callback should coalesce additional timer ticks");
            first.run();
            Runnable next = minecraft.poll(3, TimeUnit.SECONDS);
            assertNotNull(next);
            timer.cancel();
            next.run();
            assertEquals(2, calls.get());
        }
    }
}
