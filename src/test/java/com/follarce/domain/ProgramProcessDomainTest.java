package com.follarce.domain;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.PidSequence;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramProcessDomainTest {
    private static final Instant T0 = Instant.parse("2026-07-22T00:00:00Z");

    @Test
    void programIdentityIsImmutableAndContentBased() {
        ObjectHash source = hash("source");
        Program first = new Program(UUID.randomUUID(), hash("program"), "fcl-1", 1, source,
                Optional.empty(), 3, T0);
        Program sameContent = new Program(UUID.randomUUID(), first.programHash(), "fcl-1", 1, source,
                Optional.of(hash("compiled")), 3, T0.plusSeconds(1));
        Program changedLanguage = new Program(UUID.randomUUID(), first.programHash(), "fcl-2",
                1, source, Optional.empty(), 3, T0);

        assertTrue(first.hasSameIdentity(sameContent));
        assertFalse(first.hasSameIdentity(changedLanguage));
        assertThrows(IllegalArgumentException.class, () -> new Program(
                UUID.randomUUID(), hash("bad"), " ", 1, source, Optional.empty(), -1, T0));
    }

    @Test
    void pidSequenceOnlyIssuesStrictlyIncreasingNeverReusedValues() {
        PidSequence initial = new PidSequence(0);
        PidSequence.Allocation first = initial.issue();
        PidSequence.Allocation second = first.sequence().issue();

        assertEquals(1, first.pid());
        assertEquals(2, second.pid());
        assertEquals(0, initial.lastIssued(), "immutable cursor cannot move backwards or be reused");
        assertThrows(IllegalArgumentException.class, () -> new PidSequence(-1));
        assertThrows(IllegalStateException.class, () -> new PidSequence(Long.MAX_VALUE).issue());
    }

    @Test
    void continuationDefensivelyCapturesEveryRecoveryComponent() {
        UUID programId = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        List<Continuation.CallFrame> calls = new ArrayList<>(List.of(
                new Continuation.CallFrame(UUID.randomUUID(), "factorial", 12, scopeId)));
        Map<String, Continuation.PersistedValue> globals = new LinkedHashMap<>();
        globals.put("answer", value("number", "42"));
        Map<String, ObjectHash> packages = new LinkedHashMap<>();
        packages.put("math", hash("package"));

        Continuation continuation = new Continuation(
                programId,
                hash("program"),
                7,
                calls,
                List.of(new Continuation.ScopeFrame(scopeId, Optional.empty(),
                        Map.of("n", value("number", "5")))),
                List.of(new Continuation.ExceptionFrame(18, scopeId, Optional.empty())),
                List.of(new Continuation.ControlFrame(
                        Continuation.ControlKind.LOOP, 3, 20, scopeId)),
                Optional.of(new Continuation.WaitState(
                        Continuation.WaitKind.TIMER, Optional.of(UUID.randomUUID()),
                        Optional.empty())),
                globals,
                packages,
                "fcl-1",
                "runtime-1");

        calls.clear();
        globals.clear();
        packages.clear();

        assertEquals(1, continuation.callStack().size());
        assertEquals("42", continuation.globalVariables().get("answer").canonicalPayload());
        assertEquals(1, continuation.packageBindings().size());
        assertThrows(UnsupportedOperationException.class,
                () -> continuation.globalVariables().put("x", value("number", "1")));
        assertEquals(8, continuation.advanceTo(8).programCounter());
        assertTrue(continuation.advanceTo(8).waitState().isEmpty());
    }

    @Test
    void processStateMachineFencesStaleVersionsAndEpochs() {
        Continuation readyContinuation = continuation(Optional.empty());
        CilProcess ready = new CilProcess(
                new ProcessIdentity(UUID.randomUUID(), 41),
                UUID.randomUUID(),
                CilProcess.Status.READY,
                4,
                8,
                readyContinuation,
                Optional.empty(),
                T0,
                T0);

        CilProcess running = ready.claim(9, T0.plusSeconds(1));
        assertEquals(CilProcess.Status.RUNNING, running.status());
        assertEquals(5, running.stateVersion());
        assertEquals(9, running.executionEpoch());
        assertFalse(running.acceptsCommit(4, 9));
        assertFalse(running.acceptsCommit(5, 8));

        Continuation waiting = continuation(Optional.of(new Continuation.WaitState(
                Continuation.WaitKind.TIMER, Optional.of(UUID.randomUUID()), Optional.empty())));
        assertThrows(IllegalStateException.class, () -> running.commitStatement(
                waiting, CilProcess.Status.WAITING_TIMER, 4, 9, T0.plusSeconds(2)));

        CilProcess blocked = running.commitStatement(waiting, CilProcess.Status.WAITING_TIMER,
                5, 9, T0.plusSeconds(2));
        assertEquals(6, blocked.stateVersion());
        assertEquals(CilProcess.Status.WAITING_TIMER, blocked.status());
        assertThrows(IllegalStateException.class,
                () -> blocked.transitionTo(CilProcess.Status.CREATED, T0.plusSeconds(3)));
    }

    @Test
    void terminalProcessStatesCannotTransitionAgain() {
        CilProcess running = new CilProcess(
                new ProcessIdentity(UUID.randomUUID(), 9), UUID.randomUUID(),
                CilProcess.Status.RUNNING, 1, 1, continuation(Optional.empty()),
                Optional.empty(), T0, T0);
        CilProcess terminated = running.transitionTo(CilProcess.Status.TERMINATED,
                T0.plusSeconds(1));

        assertTrue(terminated.isTerminal());
        assertThrows(IllegalStateException.class,
                () -> terminated.transitionTo(CilProcess.Status.READY, T0.plusSeconds(2)));
        assertNotEquals(running.stateVersion(), terminated.stateVersion());
    }

    private static Continuation continuation(Optional<Continuation.WaitState> waitState) {
        return new Continuation(UUID.randomUUID(), hash("program"), 0, List.of(), List.of(),
                List.of(), List.of(), waitState, Map.of(), Map.of(), "fcl-1", "runtime-1");
    }

    private static Continuation.PersistedValue value(String type, String payload) {
        return new Continuation.PersistedValue(type, payload);
    }

    private static ObjectHash hash(String value) {
        return ObjectHash.sha256(new BinaryContent(value.getBytes(StandardCharsets.UTF_8)));
    }
}
