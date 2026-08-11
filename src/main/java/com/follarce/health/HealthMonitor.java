package com.follarce.health;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Periodically refreshes disposable component and database health projections. */
public final class HealthMonitor implements AutoCloseable {
    private final HealthState state;
    private final BooleanSupplier database;
    private final BooleanSupplier scheduler;
    private final BooleanSupplier effects;
    private final BooleanSupplier timer;
    private final BooleanSupplier listener;
    private final BooleanSupplier terminal;
    private final long intervalMillis;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().daemon().name("cilexec-health-monitor")
                    .unstarted(runnable));

    public HealthMonitor(HealthState state, BooleanSupplier database,
                         BooleanSupplier scheduler, BooleanSupplier effects,
                         BooleanSupplier timer, BooleanSupplier listener,
                         BooleanSupplier terminal, Duration interval) {
        this.state = Objects.requireNonNull(state, "state");
        this.database = Objects.requireNonNull(database, "database");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(interval, "interval");
        intervalMillis = interval.toMillis();
        if (intervalMillis < 1) throw new IllegalArgumentException("interval must be positive");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Health monitor already started");
        }
        executor.scheduleWithFixedDelay(this::probe, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    void probe() {
        if (executor.isShutdown()) return;
        state.schedulerLoop(safeValue(scheduler));
        state.effectWorkers(safeValue(effects));
        state.timerLoop(safeValue(timer));
        state.workListener(safeValue(listener));
        state.terminalServer(safeValue(terminal));
        state.database(safeValue(database), Instant.now());
    }

    private static boolean safeValue(BooleanSupplier probe) {
        try {
            return probe.getAsBoolean();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdownNow();
    }
}
