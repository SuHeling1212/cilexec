package com.follarce.health;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HealthStateTest {
    @Test
    void readinessRequiresEveryDatabaseInvariant() {
        HealthState state = new HealthState();
        state.schedulerLoop(true);
        state.effectWorkers(true);
        state.timerLoop(true);
        state.workListener(true);
        state.phase(HealthState.RuntimePhase.READY);
        state.database(true);
        state.schema(true);
        state.controlLock(true);
        assertFalse(state.snapshot().ready());
        state.recovery(true);
        assertTrue(state.snapshot().ready());
        state.controlLock(false);
        assertFalse(state.snapshot().ready());
        assertTrue(state.snapshot().live());
    }

    @Test
    void enabledTerminalParticipatesInReadiness() {
        HealthState state = readyState();
        state.terminalEnabled(true);

        assertFalse(state.snapshot().ready());
        state.terminalServer(true);
        assertTrue(state.snapshot().ready());
    }

    private static HealthState readyState() {
        HealthState state = new HealthState();
        state.phase(HealthState.RuntimePhase.READY);
        state.database(true);
        state.schema(true);
        state.controlLock(true);
        state.recovery(true);
        state.schedulerLoop(true);
        state.effectWorkers(true);
        state.timerLoop(true);
        state.workListener(true);
        return state;
    }
}
