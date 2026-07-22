package com.follarce.app;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Lightweight wake-up loop; durable timer rows remain the source of truth. */
public final class TimerLoop implements AutoCloseable {
    private final IntSupplier fireDue;
    private final Duration pollInterval;
    private final Consumer<Throwable> fatalFailure;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    public TimerLoop(IntSupplier fireDue, Duration pollInterval,
                     Consumer<Throwable> fatalFailure) {
        this.fireDue = Objects.requireNonNull(fireDue, "fireDue");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
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
            } catch (Throwable failure) {
                if (running.compareAndSet(true, false)) fatalFailure.accept(failure);
                return;
            }
            LockSupport.parkNanos(pollInterval.toNanos());
        }
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
