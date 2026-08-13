package com.follarce.application;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessServiceTest {
    @Test
    void pausedWaitSurvivesAndResumeRestoresItsWaitingStatus() {
        Fixture fixture = new Fixture(CilProcess.Status.WAITING_TIMER,
                Optional.of(new Continuation.WaitState(Continuation.WaitKind.TIMER,
                        Optional.of(UUID.randomUUID()), Optional.empty())), Map.of());

        CilProcess paused = fixture.service.pause(fixture.ownerId, 42);
        assertEquals(CilProcess.Status.PAUSED, paused.status());
        assertTrue(paused.continuation().waitState().isPresent());
        assertEquals(1, fixture.persistence.scheduler.releases);

        CilProcess resumed = fixture.service.resume(fixture.ownerId, 42);
        assertEquals(CilProcess.Status.WAITING_TIMER, resumed.status());
        assertEquals(0, fixture.persistence.scheduler.enqueues);
    }

    @Test
    void terminateClearsWaitAndTransientInboxBeforeTerminalCommit() {
        Fixture fixture = new Fixture(CilProcess.Status.WAITING_EFFECT,
                Optional.of(new Continuation.WaitState(Continuation.WaitKind.EFFECT,
                        Optional.of(UUID.randomUUID()), Optional.empty())),
                Map.of(ProcessInbox.EFFECT_RESULT,
                        new Continuation.PersistedValue("string", "result")));

        CilProcess terminated = fixture.service.terminate(fixture.ownerId, 42);

        assertEquals(CilProcess.Status.TERMINATED, terminated.status());
        assertTrue(terminated.continuation().waitState().isEmpty());
        assertTrue(terminated.continuation().globalVariables().isEmpty());
        assertEquals(1, fixture.persistence.scheduler.releases);
        assertEquals(1, fixture.persistence.timers.processDeletes);
        assertEquals(terminated.identity().processUid(), fixture.persistence.timers.deletedProcess);
    }

    @Test
    void resumingOrdinaryPausedProcessMakesItRunnable() {
        Fixture fixture = new Fixture(CilProcess.Status.PAUSED, Optional.empty(), Map.of());

        CilProcess resumed = fixture.service.resume(fixture.ownerId, 42);

        assertEquals(CilProcess.Status.READY, resumed.status());
        assertEquals(1, fixture.persistence.scheduler.enqueues);
    }

    private static final class Fixture {
        final ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        final UUID ownerId = UUID.randomUUID();
        final ProcessService service = new ProcessService(persistence);

        Fixture(CilProcess.Status status, Optional<Continuation.WaitState> wait,
                Map<String, Continuation.PersistedValue> globals) {
            Instant now = Instant.now().minusSeconds(2);
            ObjectHash hash = ObjectHash.sha256(new BinaryContent(
                    "program".getBytes(StandardCharsets.UTF_8)));
            Program program = new Program(UUID.randomUUID(), hash, "fcl-1", 1,
                    hash, Optional.empty(), 0, now);
            persistence.programs.byId.put(program.programId(), program);
            persistence.programs.byHash.put(program.programHash(), program);
            Continuation continuation = new Continuation(program.programId(), hash, 0,
                    List.of(), List.of(), List.of(), List.of(), wait, globals, Map.of(),
                    "fcl-1", "1");
            persistence.processes.current = new CilProcess(
                    new ProcessIdentity(UUID.randomUUID(), 42), ownerId, status,
                    3, 7, continuation, Optional.empty(), now, now);
        }
    }
}
