package com.follarce.timer;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.timer.ProcessTimer;
import com.follarce.domain.vfs.ObjectHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guard logic that decides whether a fired timer resumes a waiting terminal process. */
class TimerServiceTest {
    private static final UUID TIMER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    private static Continuation continuation(Continuation.WaitKind kind,
                                             Optional<UUID> targetId) {
        return new Continuation(UUID.randomUUID(), new ObjectHash("0".repeat(64)), 0,
                List.of(), List.of(), List.of(), List.of(),
                Optional.of(new Continuation.WaitState(kind, targetId, Optional.empty())),
                Map.of(), Map.of(), "fcl-1", "1");
    }

    private static CilProcess process(CilProcess.Status status, Continuation.WaitState wait) {
        return new CilProcess(new ProcessIdentity(UUID.randomUUID(), 1L),
                UUID.randomUUID(), status, 0L, 0L,
                continuation(wait.kind(), wait.targetId()), Optional.empty(), NOW, NOW);
    }

    private static ProcessTimer timer(Continuation.PersistedValue payload) {
        return new ProcessTimer(TIMER_ID, UUID.randomUUID(), NOW,
                ProcessTimer.Status.SCHEDULED, NOW, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(payload));
    }

    @Test
    void recognizesTheTerminalInputTimeoutMarker() {
        assertTrue(TimerService.isTerminalInputTimeout(
                timer(new Continuation.PersistedValue("string",
                        TimerService.TERMINAL_INPUT_TIMEOUT))));
        assertFalse(TimerService.isTerminalInputTimeout(
                timer(new Continuation.PersistedValue("string", "other"))));
        assertFalse(TimerService.isTerminalInputTimeout(
                timer(new Continuation.PersistedValue("null", "null"))));
    }

    @Test
    void acceptsOnlyKeyModeInputWaitsForTimeoutDelivery() {
        UUID keyWait = UUID.nameUUIDFromBytes("input:key".getBytes());
        CilProcess waitingKey = process(CilProcess.Status.WAITING_INPUT,
                new Continuation.WaitState(Continuation.WaitKind.INPUT,
                        Optional.of(keyWait), Optional.empty()));
        assertTrue(TimerService.isWaitingForTerminalInput(waitingKey));

        CilProcess waitingLine = process(CilProcess.Status.WAITING_INPUT,
                new Continuation.WaitState(Continuation.WaitKind.INPUT,
                        Optional.empty(), Optional.empty()));
        assertFalse(TimerService.isWaitingForTerminalInput(waitingLine));

        CilProcess waitingTimer = process(CilProcess.Status.WAITING_TIMER,
                new Continuation.WaitState(Continuation.WaitKind.TIMER,
                        Optional.of(TIMER_ID), Optional.empty()));
        assertFalse(TimerService.isWaitingForTerminalInput(waitingTimer));

        CilProcess running = process(CilProcess.Status.RUNNING,
                new Continuation.WaitState(Continuation.WaitKind.INPUT,
                        Optional.of(keyWait), Optional.empty()));
        assertFalse(TimerService.isWaitingForTerminalInput(running));
    }

    @Test
    void timerMatchStillRequiresTheExactTimerId() {
        CilProcess waitingKey = process(CilProcess.Status.WAITING_INPUT,
                new Continuation.WaitState(Continuation.WaitKind.INPUT,
                        Optional.of(UUID.nameUUIDFromBytes("input:key".getBytes())),
                        Optional.empty()));
        assertFalse(TimerService.isWaitingFor(waitingKey, TIMER_ID),
                "an input wait is not waiting for a timer");

        CilProcess waitingTimer = process(CilProcess.Status.WAITING_TIMER,
                new Continuation.WaitState(Continuation.WaitKind.TIMER,
                        Optional.of(TIMER_ID), Optional.empty()));
        assertTrue(TimerService.isWaitingFor(waitingTimer, TIMER_ID));
    }
}
