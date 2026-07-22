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

    public void phase(RuntimePhase value) {
        phase.set(Objects.requireNonNull(value, "value"));
    }

    public void database(boolean value) {
        database.set(value);
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

    public Snapshot snapshot() {
        RuntimePhase current = phase.get();
        boolean live = current != RuntimePhase.STOPPED && schedulerLoop.get();
        boolean ready = current == RuntimePhase.READY && database.get() && schema.get()
                && controlLock.get() && recovery.get();
        return new Snapshot(live, ready, current, database.get(), schema.get(), controlLock.get(),
                recovery.get(), schedulerLoop.get(), startedAt);
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
            Instant startedAt
    ) {
    }
}
