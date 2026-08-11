package com.follarce.health;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HealthMonitorTest {
    @Test
    void refreshesDatabaseAndComponentStatus() {
        HealthState state = readyState();
        AtomicBoolean database = new AtomicBoolean(true);
        AtomicBoolean effects = new AtomicBoolean(true);
        AtomicBoolean listener = new AtomicBoolean(true);
        try (HealthMonitor monitor = new HealthMonitor(state, database::get, () -> true,
                effects::get, () -> true, listener::get, () -> true,
                Duration.ofSeconds(1))) {
            monitor.probe();
            assertTrue(state.snapshot().ready());
            assertNotNull(state.snapshot().databaseCheckedAt());

            database.set(false);
            effects.set(false);
            listener.set(false);
            monitor.probe();

            assertFalse(state.snapshot().database());
            assertFalse(state.snapshot().effectWorkers());
            assertFalse(state.snapshot().workListener());
            assertFalse(state.snapshot().ready());
        }
    }

    private static HealthState readyState() {
        HealthState state = new HealthState();
        state.phase(HealthState.RuntimePhase.READY);
        state.schema(true);
        state.controlLock(true);
        state.recovery(true);
        return state;
    }
}
