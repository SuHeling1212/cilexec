package com.follarce.health;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Disposable projection used by health endpoints; PostgreSQL remains authoritative. */
public final class HealthState {
    private final Instant startedAt = Instant.now();
    private final AtomicReference<RuntimePhase> phase = new AtomicReference<>(RuntimePhase.STARTING);
    private final AtomicBoolean database = new AtomicBoolean();
    private final AtomicBoolean schema = new AtomicBoolean();
    private final AtomicBoolean controlLock = new AtomicBoolean();
    private final AtomicBoolean recovery = new AtomicBoolean();
    private final AtomicBoolean schedulerLoop = new AtomicBoolean();
    private final AtomicBoolean effectWorkers = new AtomicBoolean();
    private final AtomicBoolean timerLoop = new AtomicBoolean();
    private final AtomicBoolean workListener = new AtomicBoolean();
    private final AtomicBoolean terminalEnabled = new AtomicBoolean();
    private final AtomicBoolean terminalServer = new AtomicBoolean();
    private final AtomicReference<Instant> databaseCheckedAt = new AtomicReference<>();

    public void phase(RuntimePhase value) {
        phase.set(Objects.requireNonNull(value, "value"));
    }

    public void database(boolean value) {
        database(value, Instant.now());
    }

    public void database(boolean value, Instant checkedAt) {
        database.set(value);
        databaseCheckedAt.set(Objects.requireNonNull(checkedAt, "checkedAt"));
    }

    public void schema(boolean value) {
        schema.set(value);
    }

    public void controlLock(boolean value) {
        controlLock.set(value);
        if (!value && phase.get() == RuntimePhase.READY) {
            phase.compareAndSet(RuntimePhase.READY, RuntimePhase.FENCED);
        }
    }

    public void recovery(boolean value) {
        recovery.set(value);
    }

    public void schedulerLoop(boolean value) {
        schedulerLoop.set(value);
    }

    public void effectWorkers(boolean value) {
        effectWorkers.set(value);
    }

    public void timerLoop(boolean value) {
        timerLoop.set(value);
    }

    public void workListener(boolean value) {
        workListener.set(value);
    }

    public void terminalEnabled(boolean value) {
        terminalEnabled.set(value);
        if (!value) terminalServer.set(false);
    }

    public void terminalServer(boolean value) {
        terminalServer.set(value);
    }

    public Snapshot snapshot() {
        RuntimePhase current = phase.get();
        boolean live = current != RuntimePhase.STOPPED && schedulerLoop.get();
        boolean ready = current == RuntimePhase.READY && database.get() && schema.get()
                && controlLock.get() && recovery.get() && schedulerLoop.get()
                && effectWorkers.get() && timerLoop.get() && workListener.get()
                && (!terminalEnabled.get() || terminalServer.get());
        return new Snapshot(live, ready, current, database.get(), schema.get(), controlLock.get(),
                recovery.get(), schedulerLoop.get(), effectWorkers.get(), timerLoop.get(),
                workListener.get(), terminalEnabled.get(), terminalServer.get(),
                databaseCheckedAt.get(), startedAt);
    }

    public enum RuntimePhase {
        STARTING,
        RECOVERING,
        READY,
        DRAINING,
        FENCED,
        STOPPED
    }

    public record Snapshot(
            boolean live,
            boolean ready,
            RuntimePhase phase,
            boolean database,
            boolean schema,
            boolean controlLock,
            boolean recoveryComplete,
            boolean schedulerLoop,
            boolean effectWorkers,
            boolean timerLoop,
            boolean workListener,
            boolean terminalEnabled,
            boolean terminalServer,
            Instant databaseCheckedAt,
            Instant startedAt
    ) {
    }
}
