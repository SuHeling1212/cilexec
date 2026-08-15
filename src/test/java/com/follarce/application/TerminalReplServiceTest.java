package com.follarce.application;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.port.Isolation;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalReplServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void prettyPrintsStructuredTerminalResultsAndJsonFileContent() {
        Map<String, Object> packageRecord = new java.util.LinkedHashMap<>();
        packageRecord.put("name", "editor");
        packageRecord.put("version", "1.1.2");
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("ok", true);
        value.put("packages", List.of(packageRecord));

        String structured = TerminalReplService.renderValue(value);
        String jsonFile = TerminalReplService.renderValue(
                "{\"apiVersion\":\"cilexec.market/v1\",\"packages\":[]}");

        assertEquals("""
                {
                  "ok": true,
                  "packages": [
                    {
                      "name": "editor",
                      "version": "1.1.2"
                    }
                  ]
                }""", structured);
        assertEquals("""
                {
                  "apiVersion": "cilexec.market/v1",
                  "packages": []
                }""", jsonFile);
    }

    @Test
    void keepsOrdinaryAndMalformedJsonLikeTextAsStrings() {
        assertEquals("\"hello\\nworld\"", TerminalReplService.renderValue("hello\nworld"));
        assertEquals("\"{not json}\"", TerminalReplService.renderValue("{not json}"));
    }

    @Test
    void wakesTheInProcessSchedulerAfterTheSubmissionCommits() {
        ProgramServiceTest.TestPersistence persistence = new ProgramServiceTest.TestPersistence();
        UUID owner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        persistence.terminal.saveSession(new TerminalSession(sessionId, owner,
                TerminalSession.Status.OPEN, 1, NOW, NOW, Optional.empty()));
        ProgramService programs = new ProgramService(persistence, new FclCompiler(),
                new FclProgramCodec(), CLOCK, UUID::randomUUID);
        AtomicInteger wakes = new AtomicInteger();
        TerminalReplService repl = new TerminalReplService(persistence, programs,
                new FclCompiler(), new FclContinuationCodec(), CLOCK, wakes::incrementAndGet);

        repl.submit(owner, sessionId, "answer = 42");

        assertEquals(1, wakes.get(),
                "same-JVM submissions must not depend solely on a lossy database notification");
        assertEquals(CilProcess.Status.READY, persistence.processes.current.status());
    }

    @Test
    void rawTerminalInputUsesReadCommittedProcessLocking() {
        ProgramServiceTest.TestPersistence persistence = new ProgramServiceTest.TestPersistence();
        UUID owner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        persistence.terminal.saveSession(new TerminalSession(sessionId, owner,
                TerminalSession.Status.OPEN, 1, NOW, NOW, Optional.empty()));

        new com.follarce.terminal.TerminalService(persistence, CLOCK)
                .submit(owner, sessionId, "x");

        assertEquals(Isolation.READ_COMMITTED, persistence.lastIsolation,
                "raw input must wait for the current process-row writer instead of aborting "
                        + "a serializable snapshot");
    }

    @Test
    void exposesPersistedNullVariablesWithoutThrowing() {
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

        repl.submit(owner, sessionId, "empty = null");
        run(persistence, executor, owner);

        Map<String, Object> variables = repl.variables(owner, sessionId);
        assertTrue(variables.containsKey("empty"));
        assertEquals(null, variables.get("empty"));
    }

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
        executor.executeSlice(claim);

        TerminalReplService.Snapshot snapshot = repl.active(owner, sessionId).orElseThrow();
        assertEquals(CilProcess.Status.WAITING_INPUT, snapshot.status());
        assertTrue(snapshot.keyInput());
        assertFalse(snapshot.coalesceTextInput());
    }

    @Test
    void exposesPersistedCoalescedTextWaitModeForOptedInPrograms() {
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

        repl.submit(owner, sessionId, "io.readKey(250, true)");
        CilProcess current = persistence.processes.current;
        CilProcess claimed = current.claim(current.executionEpoch() + 1, NOW);
        persistence.processes.current = claimed;
        SchedulerClaim claim = new SchedulerClaim(claimed.identity().processUid(), owner,
                UUID.randomUUID(), UUID.randomUUID(), claimed.executionEpoch(), NOW, NOW,
                NOW.plus(Duration.ofMinutes(1)));
        persistence.scheduler.lease = claim;
        executor.executeSlice(claim);

        TerminalReplService.Snapshot snapshot = repl.active(owner, sessionId).orElseThrow();
        assertEquals(CilProcess.Status.WAITING_INPUT, snapshot.status());
        assertTrue(snapshot.keyInput());
        assertTrue(snapshot.coalesceTextInput());
    }

    @Test
    void executesPureTerminalInstructionsAcrossBoundedSchedulerSlices() {
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
        run(persistence, executor, owner);

        TerminalReplService.Snapshot snapshot = repl.active(owner, sessionId).orElseThrow();
        assertEquals(CilProcess.Status.PAUSED, snapshot.status());
        assertEquals(1L, snapshot.variables().get("first"));
        assertEquals(2L, snapshot.variables().get("second"));
        assertTrue(persistence.scheduler.releases >= 1);
    }

    @Test
    void retainsAnImportFromAMixedInstallStyleSubmission() {
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

        String packageId = "a".repeat(64);
        com.follarce.domain.vfs.ObjectHash hash = new com.follarce.domain.vfs.ObjectHash(packageId);
        persistence.packages.releases.put(new com.follarce.domain.packageinfo.PackageRelease.Hash(hash),
                new com.follarce.domain.packageinfo.PackageRelease(
                        new com.follarce.domain.packageinfo.PackageRelease.Coordinate(
                                "demo", "pkg", "1.0.0"),
                        new com.follarce.domain.packageinfo.PackageRelease.Hash(hash),
                        hash, hash, NOW));

        TerminalReplService.Submission first =
                repl.submit(owner, sessionId, "value = 1; import \"" + packageId + "\" as \"m\"");
        com.follarce.fcl.FclContinuation restored =
                new com.follarce.application.FclPersistenceBridge(
                        new FclContinuationCodec()).restore(first.process().continuation());
        Object library = restored.scope().get(TerminalReplService.LIBRARY_SCOPE_KEY);
        assertTrue(library instanceof String text && text.contains(packageId)
                && text.contains("import"), String.valueOf(library));

    }

    @Test
    void rejectsAnUnresolvableImportWithoutWedgingTheSession() {
        ProgramServiceTest.TestPersistence persistence = new ProgramServiceTest.TestPersistence();
        UUID owner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        persistence.terminal.saveSession(new TerminalSession(sessionId, owner,
                TerminalSession.Status.OPEN, 1, NOW, NOW, Optional.empty()));
        ProgramService programs = new ProgramService(persistence, new FclCompiler(),
                new FclProgramCodec(), CLOCK, UUID::randomUUID);
        TerminalReplService repl = new TerminalReplService(persistence, programs,
                new FclCompiler(), new FclContinuationCodec(), CLOCK);

        String missing = "b".repeat(64);
        assertThrows(com.follarce.fcl.FclRuntimeException.class,
                () -> repl.submit(owner, sessionId, "import \"" + missing + "\" as \"e2\""));

        TerminalReplService.Submission next = repl.submit(owner, sessionId, "1 + 1");
        assertFalse(next.source().contains(missing), next.source());
        assertTrue(next.source().endsWith("return 1 + 1\n"), next.source());
    }

    @Test
    void keepsTheImportedAliasUsableAfterAFullScreenProcessFailure() {
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

        byte[] database = new com.follarce.package_manager.PackageBuilder().build(
                new com.follarce.package_manager.PackageManifest("demo", "pkg", "1.0.0",
                        "fcl-1",
                        java.util.List.of(new com.follarce.package_manager.PackageManifest.Module(
                                "main", "main.fcl")), java.util.List.of(), java.util.List.of(),
                        java.util.List.of(new com.follarce.package_manager.PackageManifest.Entrypoint(
                                "run", "main", "run")),
                        java.util.List.of(
                                new com.follarce.package_manager.PackageManifest.Export(
                                        "greet", "main", "greet"),
                                new com.follarce.package_manager.PackageManifest.Export(
                                        "crash", "main", "crash")), java.util.List.of(
                                new com.follarce.package_manager.PackageManifest.Capability(
                                        "terminal.raw_input", true, "Test key input"))),
                path -> ("func greet(value) { return \"Hello, \" + value }\n"
                        + "func crash() { event = io.readKey(); return event[\"key\"] }\n"
                        + "func run() { return null }\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var descriptor = new com.follarce.persistence.sqlite.SqlitePackageReader().inspect(database);
        String packageId = descriptor.databaseFileHash();
        var stored = com.follarce.domain.vfs.StoredObject.create(
                new com.follarce.domain.vfs.BinaryContent(database),
                "application/vnd.sqlite3", NOW);
        persistence.vfs.saveObject(stored);
        var objectHash = stored.objectHash();
        persistence.packages.releases.put(
                new com.follarce.domain.packageinfo.PackageRelease.Hash(
                        new com.follarce.domain.vfs.ObjectHash(descriptor.packageHash())),
                new com.follarce.domain.packageinfo.PackageRelease(
                        new com.follarce.domain.packageinfo.PackageRelease.Coordinate(
                                "demo", "pkg", "1.0.0"),
                        new com.follarce.domain.packageinfo.PackageRelease.Hash(
                                new com.follarce.domain.vfs.ObjectHash(descriptor.packageHash())),
                        objectHash, objectHash, NOW));

        repl.submit(owner, sessionId, "import \"" + packageId + "\" as \"m\"");
        try {
            run(persistence, executor, owner);
        } catch (AssertionError failure) {
            TerminalReplService.Snapshot snapshot = repl.active(owner, sessionId).orElseThrow();
            System.out.println("DEBUG after declaration: status=" + snapshot.status()
                    + " errors=" + snapshot.errors());
            throw failure;
        }

        // The editor failure mode: a full-screen submission links the imported alias,
        // suspends on io.readKey, then crashes on a mouse event map that has no "key".
        repl.submit(owner, sessionId, "m.crash()");
        int guard = 0;
        while (persistence.processes.current.status() != CilProcess.Status.WAITING_INPUT
                && guard++ < 30) {
            CilProcess waiting = persistence.processes.current;
            if (waiting.status() == CilProcess.Status.READY) {
                CilProcess claimedWaiting = waiting.claim(waiting.executionEpoch() + 1, NOW);
                persistence.processes.current = claimedWaiting;
                SchedulerClaim waitingClaim = new SchedulerClaim(
                        claimedWaiting.identity().processUid(), owner, UUID.randomUUID(),
                        UUID.randomUUID(), claimedWaiting.executionEpoch(), NOW, NOW,
                        NOW.plus(Duration.ofMinutes(1)));
                persistence.scheduler.lease = waitingClaim;
                executor.executeSlice(waitingClaim);
            }
        }
        assertEquals(CilProcess.Status.WAITING_INPUT,
                persistence.processes.current.status());


        new com.follarce.terminal.TerminalService(persistence, CLOCK).submit(owner, sessionId,
                "{\"kind\":\"mouse\",\"button\":\"LEFT\",\"action\":\"PRESS\",\"scroll\":0,"
                        + "\"x\":5,\"y\":3,\"shift\":false,\"alt\":false,\"ctrl\":false}");
        try {
            run(persistence, executor, owner);
        } catch (AssertionError failure) {
            TerminalReplService.Snapshot snapshot = repl.active(owner, sessionId).orElseThrow();
            System.out.println("DEBUG after mouse: status=" + snapshot.status()
                    + " errors=" + snapshot.errors());
            throw failure;
        }
        TerminalReplService.Snapshot afterMouse = repl.active(owner, sessionId).orElseThrow();
        assertTrue(afterMouse.failed(),
                "the mouse map must fail the submission exactly like the editor crash");

        // The durable library and process pin survive the failure on every later submission.
        for (int attempt = 0; attempt < 4; attempt++) {
            repl.submit(owner, sessionId, "m.greet(\"again" + attempt + "\")");
            run(persistence, executor, owner);
            assertEquals("Hello, again" + attempt,
                    repl.active(owner, sessionId).orElseThrow().result());
        }
    }

    @Test
    void rebindingAnAliasReplacesTheLibraryImportAndTheProcessPin() {
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

        PackageHashes first = registerPackage(persistence, "pkg", "1.0.0", "first");
        PackageHashes second = registerPackage(persistence, "pkg", "1.1.0", "second");
        String firstHash = first.fileHash;
        String secondHash = second.fileHash;

        repl.submit(owner, sessionId, "import \"" + firstHash + "\" as \"m\"");
        run(persistence, executor, owner);
        repl.submit(owner, sessionId, "import \"" + secondHash + "\" as \"m\"");
        com.follarce.fcl.FclProgram secondCompiled = new FclCompiler().compile(
                "import \"" + secondHash + "\" as \"m\"");
        run(persistence, executor, owner);

        com.follarce.fcl.FclContinuation restored =
                new com.follarce.application.FclPersistenceBridge(
                        new FclContinuationCodec()).restore(
                        persistence.processes.current.continuation());
        Object library = restored.scope().get(TerminalReplService.LIBRARY_SCOPE_KEY);
        assertTrue(library instanceof String text && text.contains(secondHash)
                        && !text.contains(firstHash),
                "re-importing the same alias must replace the old import line: "
                        + String.valueOf(library));
        assertEquals(1, countImportLines(String.valueOf(library)),
                "the library must keep exactly one binding for the alias: "
                        + String.valueOf(library));

        com.follarce.domain.packageinfo.ProcessPackageBinding pin =
                persistence.packages.findProcessBinding(
                        persistence.processes.current.identity().processUid(), "m")
                        .orElseThrow();
        assertEquals(second.packageHash, pin.packageHash().value().value(),
                "the process pin must follow the last import");

        repl.submit(owner, sessionId, "m.greet(\"ok\")");
        run(persistence, executor, owner);
        assertEquals("second:ok", repl.active(owner, sessionId).orElseThrow().result());
    }

    private record PackageHashes(String fileHash, String packageHash) { }

    private static PackageHashes registerPackage(ProgramServiceTest.TestPersistence persistence,
                                                 String name, String version, String tag) {
        byte[] database = new com.follarce.package_manager.PackageBuilder().build(
                new com.follarce.package_manager.PackageManifest("demo", name, version, "fcl-1",
                        java.util.List.of(new com.follarce.package_manager.PackageManifest.Module(
                                "main", "main.fcl")), java.util.List.of(), java.util.List.of(),
                        java.util.List.of(new com.follarce.package_manager.PackageManifest.Entrypoint(
                                "run", "main", "run")),
                        java.util.List.of(new com.follarce.package_manager.PackageManifest.Export(
                                "greet", "main", "greet")), java.util.List.of()),
                path -> ("func greet(value) { return \"" + tag + ":\" + value }\n"
                        + "func run() { return null }\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var descriptor = new com.follarce.persistence.sqlite.SqlitePackageReader().inspect(database);
        var stored = com.follarce.domain.vfs.StoredObject.create(
                new com.follarce.domain.vfs.BinaryContent(database),
                "application/vnd.sqlite3", NOW);
        persistence.vfs.saveObject(stored);
        var objectHash = stored.objectHash();
        persistence.packages.releases.put(
                new com.follarce.domain.packageinfo.PackageRelease.Hash(
                        new com.follarce.domain.vfs.ObjectHash(descriptor.packageHash())),
                new com.follarce.domain.packageinfo.PackageRelease(
                        new com.follarce.domain.packageinfo.PackageRelease.Coordinate(
                                "demo", name, version),
                        new com.follarce.domain.packageinfo.PackageRelease.Hash(
                                new com.follarce.domain.vfs.ObjectHash(descriptor.packageHash())),
                        objectHash, objectHash, NOW));
        return new PackageHashes(descriptor.databaseFileHash(), descriptor.packageHash());
    }

    private static int countImportLines(String library) {
        int count = 0;
        for (String line : library.split("\n")) {
            if (line.stripLeading().startsWith("import ")) count++;
        }
        return count;
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
                executor.executeSlice(claim);
            }
        }
        assertEquals(CilProcess.Status.PAUSED, persistence.processes.current.status());
    }
}
