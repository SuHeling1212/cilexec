package com.follarce.app;

import com.follarce.persistence.postgres.error.PersistenceFailure;

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
     * The maintenance batch is {@code fireDue + cleanup}; wire the cleanup supplier to
     * purge fired/expired durable rows (e.g. {@code timers::deleteFiredExpired}) so the
     * fired-timer retention runs inside the same maintenance cycle.
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
                || failure instanceof PersistenceFailure persistence
                && (persistence.kind() == PersistenceFailure.Kind.DATABASE_UNAVAILABLE
                || persistence.kind() == PersistenceFailure.Kind.RUNTIME_FENCED);
    }

    /** Recomputes the nearest durable deadline without periodically scanning at high frequency. */
    public void wake() {
        if (running.get() && changed.availablePermits() == 0) changed.release();
    }

    /** Returns whether the loop waited; a past deadline means nothing to wait for. */
    private boolean awaitChangeOrDeadline() throws InterruptedException {
        Optional<Instant> deadline = nextWakeAt.get();
        if (deadline.isEmpty()) {
            changed.acquire();
            return true;
        }
        Duration delay = Duration.between(Instant.now(), deadline.orElseThrow());
        if (delay.isNegative() || delay.isZero()) return false;
        changed.tryAcquire(1, Math.max(1, delay.toMillis()), TimeUnit.MILLISECONDS);
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
