package com.follarce.terminal;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.vfs.ObjectHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Suspension-based idle disconnect: only a long-PAUSED process closes the session. */
class DatabaseTerminalControlIdleTest {
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final long THRESHOLD = java.time.Duration.ofMinutes(60).toNanos();

    private static CilProcess process(CilProcess.Status status, Instant updatedAt) {
        return new CilProcess(new ProcessIdentity(UUID.randomUUID(), 1L), UUID.randomUUID(),
                status, 0L, 0L,
                new Continuation(UUID.randomUUID(), new ObjectHash("0".repeat(64)), 0,
                        List.of(), List.of(), List.of(), List.of(), Optional.empty(),
                        Map.of(), Map.of(), "fcl-0.0.2", "1"),
                Optional.empty(), updatedAt.minusSeconds(1), updatedAt);
    }

    @Test
    void neverClosesActiveProcessesOrUnattachedSessions() {
        assertEquals(Long.MAX_VALUE, DatabaseTerminalControl.idleRemainingNanos(
                null, NOW, THRESHOLD));
        assertEquals(Long.MAX_VALUE, DatabaseTerminalControl.idleRemainingNanos(
                process(CilProcess.Status.READY, NOW.minusSeconds(9_000)), NOW, THRESHOLD));
        assertEquals(Long.MAX_VALUE, DatabaseTerminalControl.idleRemainingNanos(
                process(CilProcess.Status.RUNNING, NOW.minusSeconds(9_000)), NOW, THRESHOLD));
        assertEquals(Long.MAX_VALUE, DatabaseTerminalControl.idleRemainingNanos(
                process(CilProcess.Status.WAITING_INPUT, NOW.minusSeconds(9_000)), NOW,
                THRESHOLD));
        assertEquals(Long.MAX_VALUE, DatabaseTerminalControl.idleRemainingNanos(
                process(CilProcess.Status.WAITING_TIMER, NOW.minusSeconds(9_000)), NOW,
                THRESHOLD));
    }

    @Test
    void closesOnlyAfterTheSuspendedProcessPassesTheThreshold() {
        long expected = THRESHOLD - java.time.Duration.ofMinutes(30).toNanos();
        assertEquals(expected, DatabaseTerminalControl.idleRemainingNanos(
                process(CilProcess.Status.PAUSED, NOW.minusSeconds(1_800)), NOW, THRESHOLD));
        assertEquals(0L, DatabaseTerminalControl.idleRemainingNanos(
                process(CilProcess.Status.PAUSED, NOW.minusSeconds(3_700)), NOW, THRESHOLD));
        assertEquals(0L, DatabaseTerminalControl.idleRemainingNanos(
                process(CilProcess.Status.PAUSED, NOW.minusSeconds(100_000)), NOW, THRESHOLD));
    }

    @Test
    void freshActivityRestartsTheSuspensionClock() {
        long remaining = DatabaseTerminalControl.idleRemainingNanos(
                process(CilProcess.Status.PAUSED, NOW.minusSeconds(5)), NOW, THRESHOLD);
        assertEquals(THRESHOLD - java.time.Duration.ofSeconds(5).toNanos(), remaining);
    }
}
