package dev.mcrashcraft.namelessforge;

import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import com.namelessmc.plugin.common.command.AbstractScheduler;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Bounded website workers; Minecraft work always goes through its own executor. */
public final class ForgeScheduler extends AbstractScheduler implements AutoCloseable {
    private final Executor server;
    private final Consumer<Throwable> errors;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadLocal<Long> inheritedGeneration = new ThreadLocal<>();
    private final Set<ScheduledFuture<?>> timers = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor clock;
    private final AtomicLong lastRejectedNotice = new AtomicLong();

    public ForgeScheduler(Executor server, Consumer<Throwable> errors) {
        this.server = server;
        this.errors = errors;
        workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), factory("namelessforge-api"), new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
        clock = new ScheduledThreadPoolExecutor(1, factory("namelessforge-timer"));
        clock.setRemoveOnCancelPolicy(true);
        clock.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        clock.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    private static ThreadFactory factory(String name) {
        AtomicLong counter = new AtomicLong();
        return task -> {
            Thread thread = new Thread(task, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private long epoch() {
        Long inherited = inheritedGeneration.get();
        return inherited == null ? generation.get() : inherited;
    }

    private boolean current(long epoch) {
        return !closed.get() && generation.get() == epoch;
    }

    private Runnable guarded(Runnable task, long epoch) {
        return () -> {
            if (!current(epoch)) return;
            Long previous = inheritedGeneration.get();
            inheritedGeneration.set(epoch);
            try {
                task.run();
            } catch (Throwable error) {
                if (error instanceof VirtualMachineError fatal) throw fatal;
                if (current(epoch)) errors.accept(error);
            } finally {
                if (previous == null) inheritedGeneration.remove();
                else inheritedGeneration.set(previous);
            }
        };
    }

    @Override public void runAsync(Runnable task) {
        long epoch = epoch();
        if (!current(epoch)) return;
        try {
            workers.execute(guarded(task, epoch));
        } catch (RejectedExecutionException error) {
            // Never fall back to running HTTP on the calling (possibly server) thread.
            long now = System.nanoTime();
            long previous = lastRejectedNotice.get();
            if (!closed.get() && (previous == 0 || now - previous > TimeUnit.SECONDS.toNanos(30))
                    && lastRejectedNotice.compareAndSet(previous, now)) errors.accept(error);
        }
    }

    @Override public void runSync(Runnable task) {
        long epoch = epoch();
        if (current(epoch)) server.execute(guarded(task, epoch));
    }

    private static long delay(Duration duration, boolean allowZero) {
        if (duration == null || duration.isNegative() || (!allowZero && duration.isZero()))
            throw new IllegalArgumentException("Nameless task duration must be nonnegative; repeating intervals must be positive");
        return Math.max(50L, duration.toMillis());
    }

    @Override public AbstractScheduledTask runTimer(Runnable task, Duration interval) {
        long millis = delay(interval, false);
        long epoch = epoch();
        if (!current(epoch)) return null;
        AtomicBoolean pending = new AtomicBoolean();
        ScheduledFuture<?> future = clock.scheduleWithFixedDelay(() -> {
            // A busy server must not accumulate an unbounded backlog of timer callbacks.
            if (!current(epoch) || !pending.compareAndSet(false, true)) return;
            try {
                server.execute(() -> {
                    try { guarded(task, epoch).run(); }
                    finally { pending.set(false); }
                });
            } catch (RuntimeException error) {
                pending.set(false);
                if (current(epoch)) errors.accept(error);
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
        timers.add(future);
        return cancellation(future);
    }

    @Override public AbstractScheduledTask runDelayed(Runnable task, Duration duration) {
        long millis = delay(duration, true);
        long epoch = epoch();
        if (!current(epoch)) return null;
        ScheduledFuture<?> future = clock.schedule(() -> {
            if (current(epoch)) server.execute(guarded(task, epoch));
            timers.removeIf(Future::isDone);
        }, millis, TimeUnit.MILLISECONDS);
        timers.add(future);
        return cancellation(future);
    }

    private AbstractScheduledTask cancellation(ScheduledFuture<?> future) {
        return new AbstractScheduledTask() {
            @Override public void cancel() {
                future.cancel(false);
                timers.remove(future);
            }
        };
    }

    /** Called after core unload, before core reload. Old workers cannot enqueue new-world callbacks. */
    public void nextGeneration() {
        long next = generation.incrementAndGet();
        // A deliberate reload on the server thread must be allowed to initialize its new tasks.
        // Other in-flight HTTP threads retain their own old generation and remain invalidated.
        if (inheritedGeneration.get() != null) inheritedGeneration.set(next);
        timers.forEach(future -> future.cancel(false));
        timers.clear();
        workers.getQueue().clear();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        nextGeneration();
        clock.shutdownNow();
        workers.shutdownNow();
    }
}
