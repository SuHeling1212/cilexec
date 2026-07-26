package com.follarce.application;

import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.program.Program;
import com.follarce.domain.scheduler.SchedulerClaim;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.fcl.FclBuiltins;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.package_manager.PackageBuilder;
import com.follarce.package_manager.PackageEnvironments;
import com.follarce.package_manager.PackageManifest;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessStatementExecutorTest {
    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void executesOnlyOneStatementAndAtomicallyPersistsReadyContinuation() {
        Fixture fixture = new Fixture("first = 1\nsecond = first + 1\n");

        fixture.executor.executeOne(fixture.claim);

        CilProcess committed = fixture.persistence.processes.current;
        assertEquals(1, fixture.persistence.userTransactions);
        assertEquals(fixture.ownerId, fixture.persistence.lastUser);
        assertEquals(CilProcess.Status.READY, committed.status());
        assertEquals(8, committed.stateVersion());
        assertEquals(fixture.claim.executionEpoch(), committed.executionEpoch());
        assertEquals(1, committed.continuation().programCounter());
        assertEquals(1, fixture.persistence.processes.updates);
        assertEquals(1, fixture.persistence.scheduler.heartbeats);
        assertEquals(1, fixture.persistence.scheduler.releases);
        assertTrue(committed.continuation().globalVariables()
                .containsKey(FclPersistenceBridge.ENVELOPE_KEY));
        assertFalse(committed.continuation().scopeStack().isEmpty());

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(committed.continuation());
        assertEquals(1L, restored.scope().get("first"));
        assertFalse(restored.scope().contains("second"));
    }

    @Test
    void resolvesPureImportsAndFailsUnknownDirectivesWithoutPermanentWaits() {
        Fixture directive = new Fixture("import \"std.math\"\nvalue = 1\n");
        directive.executor.executeOne(directive.claim);
        CilProcess imported = directive.persistence.processes.current;
        assertEquals(CilProcess.Status.READY, imported.status());
        assertTrue(imported.continuation().waitState().isEmpty());
        assertEquals(1, directive.persistence.scheduler.releases);

        Fixture unresolved = new Fixture("import \"missing.package\"\nvalue = 1\n");
        unresolved.executor.executeOne(unresolved.claim);
        assertEquals(CilProcess.Status.FAILED,
                unresolved.persistence.processes.current.status());
        assertTrue(new FclPersistenceBridge(new FclContinuationCodec())
                .restore(unresolved.persistence.processes.current.continuation()).failed());

        Fixture include = new Fixture("include \"/lib/source.fcl\"\nvalue = 1\n");
        include.executor.executeOne(include.claim);
        assertEquals(CilProcess.Status.FAILED,
                include.persistence.processes.current.status());

        Fixture failed = new Fixture("value = 1 / 0\n");
        failed.executor.executeOne(failed.claim);
        assertEquals(CilProcess.Status.FAILED,
                failed.persistence.processes.current.status());
        FclContinuation failure = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(failed.persistence.processes.current.continuation());
        assertTrue(failure.halted());
        assertTrue(failure.failed());
        assertFalse(failure.exceptionStack().isEmpty());
    }

    @Test
    void linksPinnedPackageExportsAndPersistsTheExactHash() {
        Fixture fixture = new Fixture("""
                import "hello" as hello
                result = hello.greet("CilExec")
                """);
        PackageManifest manifest = new PackageManifest("demo", "hello", "1.0.0", "fcl-1",
                List.of(new PackageManifest.Module("main", "main.fcl")), List.of(), List.of(),
                List.of(new PackageManifest.Entrypoint("run", "main", "run")),
                List.of(new PackageManifest.Export("greet", "main", "greet")), List.of());
        byte[] database = new PackageBuilder().build(manifest, path -> """
                func prefix(value) { return "Hello, " + value }
                func greet(value) { return prefix(value) }
                func run() { return greet("package") }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var descriptor = new SqlitePackageReader().inspect(database);
        StoredObject object = StoredObject.create(new BinaryContent(database),
                "application/vnd.sqlite3", NOW);
        fixture.persistence.vfs.saveObject(object);
        PackageRelease release = new PackageRelease(new PackageRelease.Coordinate("demo", "hello",
                "1.0.0"), new PackageRelease.Hash(new ObjectHash(descriptor.packageHash())),
                object.objectHash(), object.objectHash(), PackageRelease.SignatureStatus.UNSIGNED,
                NOW);
        fixture.persistence.packages.releases.put(release.packageHash(), release);
        var environment = PackageEnvironments.ensureDefault(fixture.persistence.packages,
                fixture.ownerId, NOW);
        fixture.persistence.packages.saveBinding(new com.follarce.domain.packageinfo.PackageBinding(
                environment.environmentId(), "hello", release.packageHash(), NOW));

        int steps = 0;
        while (!fixture.persistence.processes.current.isTerminal() && steps++ < 30) {
            if (fixture.persistence.processes.current.status() == CilProcess.Status.READY) {
                CilProcess claimed = fixture.persistence.processes.current.claim(
                        fixture.persistence.processes.current.executionEpoch() + 1, NOW);
                fixture.persistence.processes.current = claimed;
                fixture.claim = claim(fixture.processUid, fixture.ownerId,
                        claimed.executionEpoch());
                fixture.persistence.scheduler.lease = fixture.claim;
            }
            fixture.executor.executeOne(fixture.claim);
        }

        assertEquals(CilProcess.Status.TERMINATED,
                fixture.persistence.processes.current.status());
        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals("Hello, CilExec", restored.scope().get("result"));
        assertEquals(release.packageHash().value(), fixture.persistence.processes.current
                .continuation().packageBindings().get("hello"));
    }

    @Test
    void persistsCompletionAndRemovesClaimInTheSameTransaction() {
        Fixture fixture = new Fixture("");

        fixture.executor.executeOne(fixture.claim);

        assertEquals(CilProcess.Status.TERMINATED,
                fixture.persistence.processes.current.status());
        assertEquals(1, fixture.persistence.scheduler.releases);
        assertTrue(new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation()).halted());
    }

    @Test
    void roundTripsTheCompleteInterpreterContinuationThroughTheDomainEnvelope() {
        Fixture fixture = new Fixture("""
                func identity(value) { return value }
                data = {number: 7, nested: [true, null]}
                answer = identity(data)
                """);
        var compiled = new FclCompiler().compile(new String(
                fixture.persistence.vfs.objects.get(fixture.program.sourceObjectHash())
                        .content().bytes(), java.nio.charset.StandardCharsets.UTF_8));
        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        FclContinuation continuation = new FclContinuation();
        FclStepResult step;
        do {
            step = runtime.executeOne(compiled, continuation);
        } while (step.status() != FclStepResult.Status.CALL_ENTERED);

        FclContinuationCodec codec = new FclContinuationCodec();
        FclPersistenceBridge bridge = new FclPersistenceBridge(codec);
        Continuation persisted = bridge.persist(fixture.processUid, fixture.program,
                initial(fixture.program), continuation);
        FclContinuation restored = bridge.restore(persisted);

        assertEquals(codec.toJson(continuation), codec.toJson(restored));
        assertEquals(1, persisted.callStack().size());
        assertEquals(2, persisted.scopeStack().size());
        assertTrue(persisted.scopeStack().stream()
                .flatMap(scope -> scope.variables().keySet().stream())
                .anyMatch(name -> name.equals("data") || name.equals("value")));
    }

    @Test
    void namespacesFrameAndScopeIdentityByProcessForSharedPrograms() {
        Fixture fixture = new Fixture("func id(value) { return value }\nanswer = id(1)\n");
        var compiled = new FclCompiler().compile(new String(
                fixture.persistence.vfs.objects.get(fixture.program.sourceObjectHash())
                        .content().bytes(), java.nio.charset.StandardCharsets.UTF_8));
        FclContinuation runtime = new FclContinuation();
        FclRuntime interpreter = new FclRuntime(FclBuiltins.pureRegistry());
        while (interpreter.executeOne(compiled, runtime).status()
                != FclStepResult.Status.CALL_ENTERED) {
            // Advance to a continuation that owns both a call and nested scopes.
        }
        FclPersistenceBridge bridge = new FclPersistenceBridge(new FclContinuationCodec());
        UUID otherProcess = UUID.randomUUID();

        Continuation first = bridge.persist(fixture.processUid, fixture.program,
                initial(fixture.program), runtime.snapshot());
        Continuation second = bridge.persist(otherProcess, fixture.program,
                initial(fixture.program), runtime.snapshot());

        assertNotEquals(first.callStack().getFirst().frameId(),
                second.callStack().getFirst().frameId());
        assertNotEquals(first.scopeStack().getFirst().scopeId(),
                second.scopeStack().getFirst().scopeId());
    }

    @Test
    void injectsDurableInboxExactlyOnceAcrossACommittedStatement() {
        Fixture fixture = new Fixture("value = 1\n");
        FclPersistenceBridge bridge = new FclPersistenceBridge(new FclContinuationCodec());
        FclContinuation suspendedRuntime = new FclContinuation();
        suspendedRuntime.waitFor("timer:" + UUID.randomUUID(), Map.of());
        Continuation suspended = bridge.persist(fixture.processUid, fixture.program,
                initial(fixture.program), suspendedRuntime);
        Map<String, Continuation.PersistedValue> globals =
                new java.util.LinkedHashMap<>(suspended.globalVariables());
        globals.put(ProcessInbox.TIMER_RESULT,
                new Continuation.PersistedValue("text/plain", "delivered"));
        Continuation woken = new Continuation(suspended.programId(), suspended.programHash(),
                suspended.programCounter(), suspended.callStack(), suspended.scopeStack(),
                suspended.exceptionStack(), suspended.controlStack(), Optional.empty(),
                Map.copyOf(globals), suspended.packageBindings(), suspended.languageVersion(),
                suspended.runtimeFormatVersion());

        FclContinuation firstRestore = bridge.restore(woken);
        assertEquals("delivered", firstRestore.scope().get(ProcessInbox.TIMER_RESULT));
        firstRestore.scope().put(ProcessInbox.TIMER_RESULT, "consumed");
        Continuation committed = bridge.persist(fixture.processUid, fixture.program, woken,
                firstRestore.snapshot());

        assertFalse(committed.globalVariables().containsKey(ProcessInbox.TIMER_RESULT));
        assertEquals("consumed",
                bridge.restore(committed).scope().get(ProcessInbox.TIMER_RESULT));
    }

    @Test
    void restoresVariableValuesFromNormalizedScopeProjection() {
        Fixture fixture = new Fixture("value = 1\n");
        FclPersistenceBridge bridge = new FclPersistenceBridge(new FclContinuationCodec());
        FclContinuation runtime = new FclContinuation();
        runtime.scope().put("value", 1L);
        Continuation persisted = bridge.persist(fixture.processUid, fixture.program,
                initial(fixture.program), runtime);
        Continuation.ScopeFrame root = persisted.scopeStack().getLast();
        Continuation.ScopeFrame changedRoot = new Continuation.ScopeFrame(
                root.scopeId(), root.parentScopeId(), Map.of("value",
                new Continuation.PersistedValue("long",
                        new FclContinuationCodec().valueToJson(99L))));
        Continuation normalized = new Continuation(persisted.programId(),
                persisted.programHash(), persisted.programCounter(), persisted.callStack(),
                List.of(changedRoot), persisted.exceptionStack(), persisted.controlStack(),
                persisted.waitState(), persisted.globalVariables(), persisted.packageBindings(),
                persisted.languageVersion(), persisted.runtimeFormatVersion());

        assertEquals(99L, bridge.restore(normalized).scope().get("value"));
    }

    @Test
    void rejectsExpiredOrWrongEpochClaimsBeforeExecuting() {
        Fixture expired = new Fixture("value = 1\n");
        expired.claim = new SchedulerClaim(expired.processUid, expired.ownerId,
                UUID.randomUUID(), UUID.randomUUID(), 5, NOW.minusSeconds(10), NOW.minusSeconds(10),
                NOW.minusSeconds(1));
        expired.persistence.scheduler.lease = expired.claim;
        assertThrows(ProcessStatementExecutor.StaleClaimException.class,
                () -> expired.executor.executeOne(expired.claim));
        assertEquals(0, expired.persistence.processes.updates);
        assertEquals(0, expired.persistence.scheduler.releases);

        Fixture wrongEpoch = new Fixture("value = 1\n");
        wrongEpoch.claim = claim(wrongEpoch.processUid, wrongEpoch.ownerId, 6);
        wrongEpoch.persistence.scheduler.lease = wrongEpoch.claim;
        assertThrows(ProcessStatementExecutor.StaleClaimException.class,
                () -> wrongEpoch.executor.executeOne(wrongEpoch.claim));
        assertEquals(0, wrongEpoch.persistence.processes.updates);
        assertEquals(0, wrongEpoch.persistence.scheduler.releases);
    }

    @Test
    void doesNotReleaseQueueWhenCompareAndSetIsRejected() {
        Fixture fenced = new Fixture("value = 1\n");
        fenced.persistence.processes.forcedResult = ProcessRepository.UpdateResult.EPOCH_FENCED;
        assertThrows(ProcessStatementExecutor.StaleClaimException.class,
                () -> fenced.executor.executeOne(fenced.claim));
        assertEquals(0, fenced.persistence.scheduler.releases);

        Fixture conflicted = new Fixture("value = 1\n");
        conflicted.persistence.processes.forcedResult =
                ProcessRepository.UpdateResult.VERSION_CONFLICT;
        RuntimeException failure = assertThrows(
                ProcessStatementExecutor.StatementConflictException.class,
                () -> conflicted.executor.executeOne(conflicted.claim));
        assertInstanceOf(ProcessStatementExecutor.StatementConflictException.class, failure);
        assertEquals(0, conflicted.persistence.scheduler.releases);
    }

    @Test
    void consumesDurableInterruptAtTheStatementSafePoint() {
        Fixture fixture = new Fixture("value = 1\n");
        fixture.persistence.terminal.interrupt = true;

        fixture.executor.executeOne(fixture.claim);

        assertEquals(CilProcess.Status.TERMINATED,
                fixture.persistence.processes.current.status());
        assertEquals(2, fixture.persistence.processes.updates);
        assertEquals(1, fixture.persistence.scheduler.releases);
        assertFalse(fixture.persistence.terminal.interrupt);
        assertEquals(0, fixture.persistence.processes.current.continuation().programCounter());
    }

    private static SchedulerClaim claim(UUID processUid, UUID ownerId, long epoch) {
        return new SchedulerClaim(processUid, ownerId, UUID.randomUUID(), UUID.randomUUID(), epoch,
                NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plus(Duration.ofMinutes(1)));
    }

    private static Continuation initial(Program program) {
        return new Continuation(program.programId(), program.programHash(), 0,
                List.of(), List.of(), List.of(), List.of(), Optional.empty(), Map.of(), Map.of(),
                program.languageVersion(), "1");
    }

    private static final class Fixture {
        final ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        final UUID ownerId = UUID.randomUUID();
        final UUID processUid = UUID.randomUUID();
        final ProcessStatementExecutor executor = new ProcessStatementExecutor(persistence,
                new FclRuntime(FclBuiltins.pureRegistry()), new FclProgramCodec(),
                new FclContinuationCodec(), CLOCK);
        SchedulerClaim claim;
        final Program program;

        Fixture(String source) {
            program = new ProgramService(persistence, new FclCompiler(),
                    new FclProgramCodec(), CLOCK, UUID::randomUUID).create(ownerId, source);
            persistence.runtimeTransactions = 0;
            persistence.userTransactions = 0;
            claim = claim(processUid, ownerId, 5);
            persistence.scheduler.lease = claim;
            persistence.processes.current = new CilProcess(
                    new ProcessIdentity(processUid, 41), ownerId, CilProcess.Status.RUNNING,
                    7, 5, initial(program), Optional.empty(), NOW.minusSeconds(60), NOW);
        }
    }
}
