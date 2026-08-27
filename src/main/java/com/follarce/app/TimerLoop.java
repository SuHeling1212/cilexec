package com.follarce.app;

import com.follarce.domain.port.DurableStorageFailure;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Lightweight wake-up loop; durable timer rows remain the source of truth. */
public final class TimerLoop implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TimerLoop.class);
    private static final long EMPTY_BATCH_BACKOFF_NANOS =
            Duration.ofMillis(50).toNanos();
    private static final long RETRY_BACKOFF_NANOS =
            Duration.ofMillis(100).toNanos();
    /**
     * Maximum time the loop sleeps without a durable deadline. The maintenance batch (lease
     * recovery, delivery sweeper) must run even when every wake notification is lost, so
     * the loop can never block indefinitely on a missing notification.
     */
    private static final long MAX_AWAIT_MILLIS = Duration.ofSeconds(5).toMillis();
    private final IntSupplier fireDue;
    private final IntSupplier cleanup;
    private final Supplier<Optional<Instant>> nextWakeAt;
    private final Consumer<Throwable> fatalFailure;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Semaphore changed = new Semaphore(0);
    private volatile Thread worker;

    public TimerLoop(IntSupplier fireDue, Supplier<Optional<Instant>> nextWakeAt,
                     Consumer<Throwable> fatalFailure) {
        this(fireDue, () -> 0, nextWakeAt, fatalFailure);
    }

    /**
     * The maintenance batch may perform a caller-provided explicit cleanup action. Runtime
     * startup supplies no cleanup action, so durable timer history is never time-deleted.
     */
    public TimerLoop(IntSupplier fireDue, IntSupplier cleanup,
                     Supplier<Optional<Instant>> nextWakeAt,
                     Consumer<Throwable> fatalFailure) {
        this.fireDue = Objects.requireNonNull(fireDue, "fireDue");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.nextWakeAt = Objects.requireNonNull(nextWakeAt, "nextWakeAt");
        this.fatalFailure = Objects.requireNonNull(fatalFailure, "fatalFailure");
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Timer loop already started");
        }
        worker = Thread.ofVirtual().name("cilexec-timer").start(this::run);
    }

    public boolean isRunning() {
        Thread current = worker;
        return running.get() && current != null && current.isAlive();
    }

    private void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                int completed = fireDue.getAsInt() + cleanup.getAsInt();
                if (!awaitChangeOrDeadline() && completed == 0) {
                    // The nearest durable deadline is already in the past but no work was
                    // completed; back off briefly instead of spinning against the database.
                    LockSupport.parkNanos(EMPTY_BATCH_BACKOFF_NANOS);
                }
            } catch (Throwable failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (isFatal(failure)) {
                    if (running.compareAndSet(true, false)) fatalFailure.accept(failure);
                    return;
                }
                // Benign contention (optimistic-lock CAS, serialization, deadlock) must not
                // fence the runtime; log, back off, and let the next batch try again.
                LOG.warn("Timer loop rejected a maintenance cycle", failure);
                LockSupport.parkNanos(RETRY_BACKOFF_NANOS);
            }
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof Error
                || failure instanceof DurableStorageFailure storage && storage.stopsRuntime();
    }

    /** Recomputes the nearest durable deadline without periodically scanning at high frequency. */
    public void wake() {
        if (running.get() && changed.availablePermits() == 0) changed.release();
    }

    /** Returns whether the loop waited; a past deadline means nothing to wait for. */
    private boolean awaitChangeOrDeadline() throws InterruptedException {
        Optional<Instant> deadline = nextWakeAt.get();
        long waitMillis = MAX_AWAIT_MILLIS;
        if (deadline.isPresent()) {
            Duration delay = Duration.between(Instant.now(), deadline.orElseThrow());
            if (delay.isNegative() || delay.isZero()) return false;
            waitMillis = Math.min(waitMillis, Math.max(1, delay.toMillis()));
        }
        changed.tryAcquire(1, waitMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public synchronized void close() {
        running.set(false);
        changed.release();
        if (worker == null) return;
        worker.interrupt();
        try {
            worker.join(Duration.ofSeconds(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            worker = null;
        }
    }
}
