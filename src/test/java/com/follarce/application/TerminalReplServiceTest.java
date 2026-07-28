package com.follarce.application;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.scheduler.SchedulerClaim;
import com.follarce.domain.terminal.TerminalSession;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclProgramCodec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalReplServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void executesEverySubmissionInTheSameDurableSuspendedProcess() {
        ProgramServiceTest.TestPersistence persistence = new ProgramServiceTest.TestPersistence();
        UUID owner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        persistence.terminal.saveSession(new TerminalSession(sessionId, owner,
                TerminalSession.Status.OPEN, 1, NOW, NOW, Optional.empty()));
        ProgramService programs = new ProgramService(persistence, new FclCompiler(),
                new FclProgramCodec(), CLOCK, UUID::randomUUID);
        TerminalReplService repl = new TerminalReplService(persistence, programs,
                new FclCompiler(), new FclContinuationCodec(), CLOCK);
        ProcessStatementExecutor executor = new ProcessStatementExecutor(persistence, null,
                new FclProgramCodec(), new FclContinuationCodec(), CLOCK);

        TerminalReplService.Submission assignment = repl.submit(owner, sessionId,
                "answer = 40 + 2");
        run(persistence, executor, owner);
        assertEquals(42L, repl.variables(owner, sessionId).get("answer"));
        assertTrue(assignment.source().contains("answer = 40 + 2"));
        assertEquals(CilProcess.Status.PAUSED,
                repl.active(owner, sessionId).orElseThrow().status());

        TerminalReplService.Submission expression = repl.submit(owner, sessionId,
                "answer + 1");
        assertEquals(assignment.process().identity(), expression.process().identity());
        assertEquals(2, persistence.processes.nextPid,
                "the terminal must allocate its PID exactly once");
        run(persistence, executor, owner);
        TerminalReplService.Snapshot result = repl.active(owner, sessionId).orElseThrow();
        assertEquals(CilProcess.Status.PAUSED, result.status());
        assertEquals(43L, result.result());
        assertEquals(42L, result.variables().get("answer"));

        repl.submit(owner, sessionId, "func plusOne(value) { return value + 1 }");
        run(persistence, executor, owner);
        repl.submit(owner, sessionId, "plusOne(answer)");
        run(persistence, executor, owner);
        assertEquals(43L, repl.active(owner, sessionId).orElseThrow().result());
        assertTrue(repl.variables(owner, sessionId).keySet().stream()
                .noneMatch(name -> name.startsWith("cilexec.repl")));

        TerminalReplService.Submission failed = repl.submit(owner, sessionId, "missingName");
        assertEquals(assignment.process().identity(), failed.process().identity());
        run(persistence, executor, owner);
        TerminalReplService.Snapshot failure = repl.active(owner, sessionId).orElseThrow();
        assertEquals(CilProcess.Status.PAUSED, failure.status());
        assertTrue(failure.failed());

        TerminalReplService.Submission recovered = repl.submit(owner, sessionId, "answer");
        assertEquals(assignment.process().identity(), recovered.process().identity());
        run(persistence, executor, owner);
        assertEquals(42L, repl.active(owner, sessionId).orElseThrow().result());
    }

    @Test
    void exposesRawKeyWaitModeForFullScreenFclPrograms() {
        ProgramServiceTest.TestPersistence persistence = new ProgramServiceTest.TestPersistence();
        UUID owner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        persistence.terminal.saveSession(new TerminalSession(sessionId, owner,
                TerminalSession.Status.OPEN, 1, NOW, NOW, Optional.empty()));
        ProgramService programs = new ProgramService(persistence, new FclCompiler(),
                new FclProgramCodec(), CLOCK, UUID::randomUUID);
        TerminalReplService repl = new TerminalReplService(persistence, programs,
                new FclCompiler(), new FclContinuationCodec(), CLOCK);
        ProcessStatementExecutor executor = new ProcessStatementExecutor(persistence, null,
                new FclProgramCodec(), new FclContinuationCodec(), CLOCK);

        repl.submit(owner, sessionId, "io.readKey()");
        CilProcess current = persistence.processes.current;
        CilProcess claimed = current.claim(current.executionEpoch() + 1, NOW);
        persistence.processes.current = claimed;
        SchedulerClaim claim = new SchedulerClaim(claimed.identity().processUid(), owner,
                UUID.randomUUID(), UUID.randomUUID(), claimed.executionEpoch(), NOW, NOW,
                NOW.plus(Duration.ofMinutes(1)));
        persistence.scheduler.lease = claim;
        executor.executeOne(claim);

        TerminalReplService.Snapshot snapshot = repl.active(owner, sessionId).orElseThrow();
        assertEquals(CilProcess.Status.WAITING_INPUT, snapshot.status());
        assertTrue(snapshot.keyInput());
    }

    @Test
    void executesPureTerminalInstructionsInOneDurableSchedulerSlice() {
        ProgramServiceTest.TestPersistence persistence = new ProgramServiceTest.TestPersistence();
        UUID owner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        persistence.terminal.saveSession(new TerminalSession(sessionId, owner,
                TerminalSession.Status.OPEN, 1, NOW, NOW, Optional.empty()));
        ProgramService programs = new ProgramService(persistence, new FclCompiler(),
                new FclProgramCodec(), CLOCK, UUID::randomUUID);
        TerminalReplService repl = new TerminalReplService(persistence, programs,
                new FclCompiler(), new FclContinuationCodec(), CLOCK);
        ProcessStatementExecutor executor = new ProcessStatementExecutor(persistence, null,
                new FclProgramCodec(), new FclContinuationCodec(), CLOCK);

        repl.submit(owner, sessionId, "first = 1\nsecond = first + 1");
        CilProcess current = persistence.processes.current;
        CilProcess claimed = current.claim(current.executionEpoch() + 1, NOW);
        persistence.processes.current = claimed;
        SchedulerClaim claim = new SchedulerClaim(claimed.identity().processUid(), owner,
                UUID.randomUUID(), UUID.randomUUID(), claimed.executionEpoch(), NOW, NOW,
                NOW.plus(Duration.ofMinutes(1)));
        persistence.scheduler.lease = claim;

        executor.executeOne(claim);

        TerminalReplService.Snapshot snapshot = repl.active(owner, sessionId).orElseThrow();
        assertEquals(CilProcess.Status.PAUSED, snapshot.status());
        assertEquals(1L, snapshot.variables().get("first"));
        assertEquals(2L, snapshot.variables().get("second"));
        assertEquals(1, persistence.scheduler.releases);
    }

    private static void run(ProgramServiceTest.TestPersistence persistence,
                            ProcessStatementExecutor executor, UUID owner) {
        int steps = 0;
        while (persistence.processes.current.status() != CilProcess.Status.PAUSED
                && steps++ < 30) {
            CilProcess current = persistence.processes.current;
            if (current.status() == CilProcess.Status.READY) {
                CilProcess claimed = current.claim(current.executionEpoch() + 1, NOW);
                persistence.processes.current = claimed;
                SchedulerClaim claim = new SchedulerClaim(claimed.identity().processUid(), owner,
                        UUID.randomUUID(), UUID.randomUUID(), claimed.executionEpoch(), NOW, NOW,
                        NOW.plus(Duration.ofMinutes(1)));
                persistence.scheduler.lease = claim;
                executor.executeOne(claim);
            }
        }
        assertEquals(CilProcess.Status.PAUSED, persistence.processes.current.status());
    }
}
