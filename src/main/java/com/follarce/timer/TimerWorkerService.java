package com.follarce.timer;

import com.follarce.persistence.postgres.error.PersistenceFailure;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** Polls durable due timers; the thread is disposable and never owns timer truth. */
public final class TimerWorkerService implements AutoCloseable {
    private final TimerService timers;
    private final int batchSize;
    private final Duration idlePoll;
    private final Consumer<Throwable> fatalFailure;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    public TimerWorkerService(TimerService timers, int batchSize, Duration idlePoll,
                              Consumer<Throwable> fatalFailure) {
        this.timers = java.util.Objects.requireNonNull(timers, "timers");
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
        this.idlePoll = java.util.Objects.requireNonNull(idlePoll, "idlePoll");
        if (idlePoll.isZero() || idlePoll.isNegative()) {
            throw new IllegalArgumentException("idlePoll must be positive");
        }
        this.fatalFailure = java.util.Objects.requireNonNull(fatalFailure, "fatalFailure");
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Timer worker already started");
        }
        UUID runnerId = UUID.randomUUID();
        worker = Thread.ofVirtual().name("cilexec-timer").start(() -> loop(runnerId));
    }

    public boolean isRunning() {
        return running.get() && worker != null && worker.isAlive();
    }

    private void loop(UUID runnerId) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                int fired = timers.fireDue(runnerId, batchSize);
                if (fired == 0) LockSupport.parkNanos(idlePoll.toNanos());
            } catch (Throwable failure) {
                if (!running.get()) return;
                if (isFatal(failure)) {
                    running.set(false);
                    fatalFailure.accept(failure);
                    return;
                }
                LockSupport.parkNanos(idlePoll.toNanos());
            }
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof Error
                || failure instanceof PersistenceFailure persistence
                && (persistence.kind() == PersistenceFailure.Kind.DATABASE_UNAVAILABLE
                || persistence.kind() == PersistenceFailure.Kind.RUNTIME_FENCED);
    }

    @Override
    public synchronized void close() {
        running.set(false);
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
