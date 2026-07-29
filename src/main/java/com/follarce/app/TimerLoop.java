package com.follarce.app;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Lightweight wake-up loop; durable timer rows remain the source of truth. */
public final class TimerLoop implements AutoCloseable {
    private final IntSupplier fireDue;
    private final Supplier<Optional<Instant>> nextWakeAt;
    private final Consumer<Throwable> fatalFailure;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Semaphore changed = new Semaphore(0);
    private volatile Thread worker;

    public TimerLoop(IntSupplier fireDue, Supplier<Optional<Instant>> nextWakeAt,
                     Consumer<Throwable> fatalFailure) {
        this.fireDue = Objects.requireNonNull(fireDue, "fireDue");
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
                fireDue.getAsInt();
                awaitChangeOrDeadline();
            } catch (Throwable failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (running.compareAndSet(true, false)) fatalFailure.accept(failure);
                return;
            }
        }
    }

    /** Recomputes the nearest durable deadline without periodically scanning at high frequency. */
    public void wake() {
        if (running.get() && changed.availablePermits() == 0) changed.release();
    }

    private void awaitChangeOrDeadline() throws InterruptedException {
        Optional<Instant> deadline = nextWakeAt.get();
        if (deadline.isEmpty()) {
            changed.acquire();
            return;
        }
        Duration delay = Duration.between(Instant.now(), deadline.orElseThrow());
        if (delay.isNegative() || delay.isZero()) return;
        changed.tryAcquire(1, Math.max(1, delay.toMillis()), TimeUnit.MILLISECONDS);
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
