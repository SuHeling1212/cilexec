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
import com.follarce.fcl.FclCompileException;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.extension.JavaExtensionCatalog;
import com.follarce.extension.api.CilExecExtension;
import com.follarce.extension.api.ExtensionDescriptor;
import com.follarce.extension.api.ExtensionRegistrar;
import com.follarce.package_manager.PackageBuilder;
import com.follarce.package_manager.PackageManifest;
import com.follarce.persistence.sqlite.PackageDescriptor;
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
import java.util.concurrent.atomic.AtomicInteger;

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
    void batchesNonTerminalStatementsAndAtomicallyPersistsTheirFinalContinuation() {
        Fixture fixture = new Fixture("first = 1\nsecond = first + 1\n");

        fixture.executor.executeSlice(fixture.claim);

        CilProcess committed = fixture.persistence.processes.current;
        assertEquals(1, fixture.persistence.userTransactions);
        assertEquals(fixture.ownerId, fixture.persistence.lastUser);
        assertEquals(CilProcess.Status.TERMINATED, committed.status());
        assertEquals(8, committed.stateVersion());
        assertEquals(fixture.claim.executionEpoch(), committed.executionEpoch());
        assertEquals(2, committed.continuation().programCounter());
        assertEquals(1, fixture.persistence.processes.updates);
        assertEquals(1, fixture.persistence.scheduler.heartbeats);
        assertEquals(1, fixture.persistence.scheduler.releases);
        assertTrue(committed.continuation().globalVariables()
                .containsKey(FclPersistenceBridge.ENVELOPE_KEY));
        assertFalse(committed.continuation().scopeStack().isEmpty());

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(committed.continuation());
        assertEquals(1L, restored.scope().get("first"));
        assertEquals(2L, restored.scope().get("second"));
    }

    @Test
    void restoresACompletedV002ContinuationAfterTheV003Upgrade() {
        Fixture fixture = new Fixture("value = 1\n");
        FclContinuation legacyRuntime = new FclContinuation(FclProgramCodec.LEGACY_FORMAT_VERSION);
        legacyRuntime.scope().put("survives", 7L);
        FclContinuationCodec codec = new FclContinuationCodec();
        Continuation legacy = new Continuation(fixture.program.programId(), fixture.program.programHash(), 0,
                List.of(), List.of(new Continuation.ScopeFrame(UUID.randomUUID(), Optional.empty(), Map.of(
                "survives", new Continuation.PersistedValue("long", codec.valueToJson(7L))))), List.of(), List.of(), Optional.empty(), Map.of(
                FclPersistenceBridge.ENVELOPE_KEY, new Continuation.PersistedValue(
                        FclPersistenceBridge.LEGACY_ENVELOPE_TYPE,
                        codec.toJson(legacyRuntime))), Map.of(),
                fixture.program.languageVersion(), Integer.toString(FclProgramCodec.LEGACY_FORMAT_VERSION));

        FclContinuation restored = new FclPersistenceBridge(codec).restore(legacy);

        assertEquals(FclProgramCodec.LEGACY_FORMAT_VERSION, restored.formatVersion());
        assertEquals(7L, restored.scope().get("survives"));
    }

    @Test
    void memoryListsVisibleSymbolsAndDestroysOnlyCurrentProcessValues() {
        Fixture fixture = new Fixture("""
                value = 7
                func hi() { return value }
                symbols = memory.list(true)
                allSymbols = memory.list({includeRuntime: true})
                removedValue = memory.destroy(value)
                missing = memory.destroy(absent)
                """, com.follarce.extension.SourceExtensionIndex.catalog());

        while (fixture.persistence.processes.current.status() == CilProcess.Status.RUNNING
                || fixture.persistence.processes.current.status() == CilProcess.Status.READY) {
            fixture.executor.executeSlice(fixture.claim);
            CilProcess current = fixture.persistence.processes.current;
            if (current.status() == CilProcess.Status.READY) {
                CilProcess claimed = current.claim(current.executionEpoch() + 1, NOW);
                fixture.persistence.processes.current = claimed;
                fixture.claim = claim(fixture.processUid, fixture.ownerId, claimed.executionEpoch());
                fixture.persistence.scheduler.lease = fixture.claim;
            }
        }

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals(true, restored.scope().get("removedValue"));
        assertEquals(false, restored.scope().get("missing"));
        assertFalse(restored.scope().contains("value"));
        @SuppressWarnings("unchecked")
        Map<String, Object> symbols = (Map<String, Object>) restored.scope().get("symbols");
        assertEquals(7L, ((Map<?, ?>) symbols.get("variables")).get("value"));
        assertTrue(((List<?>) symbols.get("functions")).stream().anyMatch(value ->
                value instanceof Map<?, ?> function && "hi".equals(function.get("name"))
                        && Boolean.FALSE.equals(function.get("mutable"))));
        assertFalse(((List<?>) symbols.get("functions")).stream().anyMatch(value ->
                value instanceof Map<?, ?> function && "io.print".equals(function.get("name"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> allSymbols = (Map<String, Object>) restored.scope().get("allSymbols");
        assertTrue(((List<?>) allSymbols.get("functions")).stream().anyMatch(value ->
                value instanceof Map<?, ?> function && "io.print".equals(function.get("name"))));
    }

    @Test
    void memoryDestroyDeletesTopLevelVariablesAndReportsBooleans() {
        Fixture fixture = new Fixture("""
                a = 1
                missing = memory.destroy(absent)
                ok = memory.destroy(a)
                presentAfter = memory.destroy(a)
                """, com.follarce.extension.SourceExtensionIndex.catalog());

        runToCompletion(fixture);

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals(false, restored.scope().get("missing"),
                "destroying an absent top-level symbol returns false");
        assertEquals(true, restored.scope().get("ok"));
        assertEquals(false, restored.scope().get("presentAfter"),
                "destroying an already-deleted variable returns false");
        assertFalse(restored.scope().contains("a"));
    }

    @Test
    void memoryDestroyRemovesArrayElementsWithRealShiftAndNoHoles() {
        Fixture fixture = new Fixture("""
                a = [10, 20, 30]
                mid = memory.destroy(a[1])
                midSize = #a
                first = memory.destroy(a[0])
                firstSize = #a
                last = memory.destroy(a[0])
                lastSize = #a
                """, com.follarce.extension.SourceExtensionIndex.catalog());

        runToCompletion(fixture);

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals(true, restored.scope().get("mid"));
        assertEquals(2L, restored.scope().get("midSize"));
        assertEquals(true, restored.scope().get("first"));
        assertEquals(1L, restored.scope().get("firstSize"));
        assertEquals(true, restored.scope().get("last"));
        assertEquals(0L, restored.scope().get("lastSize"));
        assertTrue(restored.scope().get("a") instanceof List<?> list && list.isEmpty());
    }

    @Test
    void memoryDestroyRemovesMapEntriesAndHandlesMissingKeys() {
        Fixture fixture = new Fixture("""
                m = {"a": 1, "b": 2}
                removed = memory.destroy(m["a"])
                remaining = m
                missing = memory.destroy(m["zzz"])
                """, com.follarce.extension.SourceExtensionIndex.catalog());

        runToCompletion(fixture);

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals(true, restored.scope().get("removed"));
        assertEquals(false, restored.scope().get("missing"),
                "destroying an absent map key returns false");
        Map<?, ?> remaining = (Map<?, ?>) restored.scope().get("remaining");
        assertEquals(1, remaining.size());
        assertEquals(2L, remaining.get("b"));
        assertFalse(remaining.containsKey("a"));
    }

    @Test
    void memoryDestroyRemovesNestedElementsThroughRealContainers() {
        Fixture fixture = new Fixture("""
                people = [{"name": "Alice", "age": 18}]
                nestedMap = memory.destroy(people[0]["name"])
                grids = [[1, 2, 3], [4, 5, 6]]
                nestedList = memory.destroy(grids[0][1])
                deep = [{"x": [1, 2, 3]}]
                deepRemoved = memory.destroy(deep[0]["x"][1])
                """, com.follarce.extension.SourceExtensionIndex.catalog());

        runToCompletion(fixture);

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals(true, restored.scope().get("nestedMap"));
        assertEquals(true, restored.scope().get("nestedList"));
        assertEquals(true, restored.scope().get("deepRemoved"));
        List<?> people = (List<?>) restored.scope().get("people");
        assertEquals(Map.of("age", 18L), people.getFirst());
        List<?> grids = (List<?>) restored.scope().get("grids");
        assertEquals(List.of(1L, 3L), grids.get(0));
        assertEquals(List.of(4L, 5L, 6L), grids.get(1));
        List<?> deep = (List<?>) restored.scope().get("deep");
        assertEquals(Map.of("x", List.of(1L, 3L)), deep.getFirst());
    }

    @Test
    void memoryDestroyPreservesValueSemanticsCopies() {
        Fixture fixture = new Fixture("""
                a = [1, 2, 3]
                b = a
                destroyed = memory.destroy(a[1])
                c = {"x": 1}
                d = c
                whole = memory.destroy(c)
                """, com.follarce.extension.SourceExtensionIndex.catalog());

        runToCompletion(fixture);

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals(true, restored.scope().get("destroyed"));
        assertEquals(true, restored.scope().get("whole"));
        assertEquals(List.of(1L, 3L), restored.scope().get("a"));
        assertEquals(List.of(1L, 2L, 3L), restored.scope().get("b"),
                "a copied value must not be affected by destroying the source element");
        assertEquals(Map.of("x", 1L), restored.scope().get("d"),
                "a copied value must survive destroying the source variable");
        assertFalse(restored.scope().contains("c"));
    }

    @Test
    void memoryDestroyRemovesOnlyOneObjectValueAndLeavesItsCopyUsable() {
        Fixture fixture = new Fixture("""
                class Counter {
                    value = 0
                    func increment() { this.value++ }
                }
                a = new Counter()
                b = a
                b.increment()
                removed = memory.destroy(a)
                answer = b.value
                """, com.follarce.extension.SourceExtensionIndex.catalog());
        runToCompletion(fixture);

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals(true, restored.scope().get("removed"));
        assertFalse(restored.scope().contains("a"));
        assertEquals(1L, restored.scope().get("answer"));
    }

    @Test
    void memoryDestroyingAnyLinkedNameAlsoDestroysItsSourceAndEveryLinkedName() {
        Fixture fixture = new Fixture("""
                a = 10
                b link a
                removedLink = memory.destroy(b)
                removedSource = memory.destroy(a)
                """, com.follarce.extension.SourceExtensionIndex.catalog());
        runToCompletion(fixture);

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals(true, restored.scope().get("removedLink"));
        assertEquals(false, restored.scope().get("removedSource"));
        assertFalse(restored.scope().contains("a"));
        assertFalse(restored.scope().contains("b"));
    }

    @Test
    void memoryDestroyPersistsInEnvelopeAndNormalizedScopeProjection() {
        Fixture fixture = new Fixture("""
                a = [1, 2, 3]
                destroyed = memory.destroy(a[1])
                """, com.follarce.extension.SourceExtensionIndex.catalog());

        runToCompletion(fixture);

        Continuation persisted = fixture.persistence.processes.current.continuation();
        FclContinuation envelope = new FclContinuationCodec().fromJson(
                persisted.globalVariables()
                        .get(FclPersistenceBridge.ENVELOPE_KEY).canonicalPayload());
        assertEquals(List.of(1L, 3L), envelope.scope().get("a"));
        assertEquals(true, envelope.scope().get("destroyed"));
        Continuation.ScopeFrame normalized = persisted.scopeStack().getLast();
        assertEquals(List.of(1L, 3L), new FclContinuationCodec().valueFromJson(
                normalized.variables().get("a").canonicalPayload()));
    }

    @Test
    void memoryDestroyRejectsInvalidTargetsAtCompileTime() {
        for (String source : List.of(
                "memory.destroy(\"a\")\n",
                "memory.destroy(1)\n",
                "memory.destroy(a + b)\n",
                "memory.destroy([1, 2, 3])\n",
                "memory.destroy(func())\n",
                "memory.destroy()\n",
                "memory.destroy(a, b)\n")) {
            assertThrows(FclCompileException.class, () -> new Fixture(source,
                            com.follarce.extension.SourceExtensionIndex.catalog()),
                    "must reject non-target argument: " + source.strip());
        }
    }

    @Test
    void memoryDestroyFailsOnStringElementAndOutOfRangeIndexesWithoutPartialDeletion() {
        Fixture stringElement = new Fixture("""
                text = "abc"
                bad = memory.destroy(text[1])
                """, com.follarce.extension.SourceExtensionIndex.catalog());
        runToFailure(stringElement);
        FclContinuation stringFailure = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(stringElement.persistence.processes.current.continuation());
        assertTrue(stringFailure.failed());

        Fixture outOfRange = new Fixture("""
                a = [1]
                bad = memory.destroy(a[5])
                """, com.follarce.extension.SourceExtensionIndex.catalog());
        runToFailure(outOfRange);
        FclContinuation rangeFailure = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(outOfRange.persistence.processes.current.continuation());
        assertTrue(rangeFailure.failed());
        assertEquals(List.of(1L), rangeFailure.scope().get("a"),
                "an out-of-range destroy must leave the array untouched");
    }

    @Test
    void memoryDestroyRejectsReservedRuntimeState() {
        Fixture fixture = new Fixture("""
                reserved = memory.destroy(cilexec.fcl.disabledFunctions)
                """, com.follarce.extension.SourceExtensionIndex.catalog());
        runToFailure(fixture);

    }

    @Test
    void execCompilesAVfsPathAndPreservesTheExistingProcessIdentity() {
        Fixture fixture = new Fixture("process.exec(\"/next.fcl\")\noldTail = 99\n",
                com.follarce.extension.SourceExtensionIndex.catalog());
        StoredObject source = StoredObject.create(new BinaryContent(
                "func afterExec() { return retained + 2 }\nreplacement = plusOne(retained)\n".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)),
                ProgramService.SOURCE_MEDIA_TYPE, NOW);
        fixture.persistence.vfs.saveObject(source);
        com.follarce.domain.vfs.VfsNode root = new com.follarce.domain.vfs.VfsNode(
                UUID.randomUUID(), Optional.empty(), fixture.ownerId, "/",
                com.follarce.domain.vfs.VfsNode.Type.DIRECTORY, Optional.empty(),
                java.util.Set.of(), false, NOW, NOW);
        fixture.persistence.vfs.insertNode(root);
        fixture.persistence.vfs.insertNode(new com.follarce.domain.vfs.VfsNode(
                UUID.randomUUID(), Optional.of(root.nodeId()), fixture.ownerId, "next.fcl",
                com.follarce.domain.vfs.VfsNode.Type.FILE, Optional.of(source.objectHash()),
                java.util.Set.of(), false, NOW, NOW));
        FclContinuation terminalRuntime = new FclContinuation();
        terminalRuntime.scope().put(TerminalReplService.TERMINAL_PROCESS_SCOPE_KEY, true);
        terminalRuntime.scope().put(TerminalReplService.TERMINAL_SESSION_SCOPE_KEY,
                UUID.randomUUID().toString());
        terminalRuntime.scope().put(com.follarce.fcl.FclPath.SCOPE_KEY, "/");
        terminalRuntime.scope().put("retained", 41L);
        terminalRuntime.scope().put(TerminalReplService.LIBRARY_SCOPE_KEY,
                "func plusOne(value) { return value + 1 }\n");
        terminalRuntime.scope().put(ProcessInbox.EFFECT_RESULT, "transient");
        Continuation terminalContinuation = new FclPersistenceBridge(
                new FclContinuationCodec()).persist(fixture.processUid, fixture.program,
                initial(fixture.program), terminalRuntime);
        CilProcess seeded = fixture.persistence.processes.current;
        fixture.persistence.processes.current = new CilProcess(seeded.identity(), seeded.ownerId(),
                seeded.status(), seeded.stateVersion(), seeded.executionEpoch(),
                terminalContinuation, seeded.parentProcessUid(), seeded.createdAt(),
                seeded.updatedAt());
        ProcessIdentity originalIdentity = fixture.persistence.processes.current.identity();
        UUID originalProgramId = fixture.program.programId();

        fixture.executor.executeSlice(fixture.claim);

        CilProcess replaced = fixture.persistence.processes.current;
        assertEquals(originalIdentity, replaced.identity(),
                "exec must retain PID and process UID");
        assertNotEquals(originalProgramId, replaced.continuation().programId());
        assertEquals(0, replaced.continuation().programCounter());
        assertEquals(CilProcess.Status.READY, replaced.status());
        FclContinuation replacementState = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(replaced.continuation());
        assertEquals(true, replacementState.scope()
                .get(TerminalReplService.TERMINAL_PROCESS_SCOPE_KEY));
        assertEquals("/", replacementState.scope().get(com.follarce.fcl.FclPath.SCOPE_KEY));
        assertEquals(41L, replacementState.scope().get("retained"));
        assertFalse(replacementState.scope().contains(ProcessInbox.EFFECT_RESULT));
        assertFalse(replacementState.scope().contains("oldTail"));

        int sliceBudget = 1_000;
        CilProcess claimed = fixture.persistence.processes.current;
        while (claimed.status() == CilProcess.Status.READY && sliceBudget-- > 0) {
            claimed = claimed.claim(claimed.executionEpoch() + 1, NOW);
            fixture.persistence.processes.current = claimed;
            fixture.claim = claim(fixture.processUid, fixture.ownerId, claimed.executionEpoch());
            fixture.persistence.scheduler.lease = fixture.claim;
            fixture.executor.executeSlice(fixture.claim);
            claimed = fixture.persistence.processes.current;
        }
        assertTrue(sliceBudget > 0, "executed program did not finish within the slice budget");

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals(42L, restored.scope().get("replacement"));
        assertEquals(41L, restored.scope().get("retained"));
        assertFalse(restored.scope().contains("oldTail"));
        assertTrue(TerminalReplService.librarySource(restored).contains("func afterExec"));
        assertEquals(originalIdentity, fixture.persistence.processes.current.identity());
        assertEquals(CilProcess.Status.PAUSED,
                fixture.persistence.processes.current.status());
    }

    @Test
    void execResolvesARelativePathAgainstTheProcessWorkingDirectory() {
        Fixture fixture = new Fixture("process.exec(\"./next.fcl\")\noldTail = 99\n",
                com.follarce.extension.SourceExtensionIndex.catalog());
        StoredObject source = StoredObject.create(new BinaryContent(
                "replacement = 42\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                ProgramService.SOURCE_MEDIA_TYPE, NOW);
        fixture.persistence.vfs.saveObject(source);
        com.follarce.domain.vfs.VfsNode root = new com.follarce.domain.vfs.VfsNode(
                UUID.randomUUID(), Optional.empty(), fixture.ownerId, "/",
                com.follarce.domain.vfs.VfsNode.Type.DIRECTORY, Optional.empty(),
                java.util.Set.of(), false, NOW, NOW);
        fixture.persistence.vfs.insertNode(root);
        com.follarce.domain.vfs.VfsNode market = new com.follarce.domain.vfs.VfsNode(
                UUID.randomUUID(), Optional.of(root.nodeId()), fixture.ownerId, "market",
                com.follarce.domain.vfs.VfsNode.Type.DIRECTORY, Optional.empty(),
                java.util.Set.of(), false, NOW, NOW);
        fixture.persistence.vfs.insertNode(market);
        fixture.persistence.vfs.insertNode(new com.follarce.domain.vfs.VfsNode(
                UUID.randomUUID(), Optional.of(market.nodeId()), fixture.ownerId, "next.fcl",
                com.follarce.domain.vfs.VfsNode.Type.FILE, Optional.of(source.objectHash()),
                java.util.Set.of(), false, NOW, NOW));
        FclContinuation runtime = new FclContinuation();
        runtime.scope().put(com.follarce.fcl.FclPath.SCOPE_KEY, "/market");
        Continuation seeded = new FclPersistenceBridge(new FclContinuationCodec())
                .persist(fixture.processUid, fixture.program, initial(fixture.program), runtime);
        CilProcess original = fixture.persistence.processes.current;
        fixture.persistence.processes.current = new CilProcess(original.identity(),
                original.ownerId(), original.status(), original.stateVersion(),
                original.executionEpoch(), seeded, original.parentProcessUid(),
                original.createdAt(), original.updatedAt());
        UUID originalProgramId = fixture.program.programId();

        fixture.executor.executeSlice(fixture.claim);

        CilProcess replaced = fixture.persistence.processes.current;
        assertNotEquals(originalProgramId, replaced.continuation().programId(),
                "the relative path must resolve to /market/next.fcl");
        assertEquals(0, replaced.continuation().programCounter());
        assertEquals(CilProcess.Status.READY, replaced.status());
        FclContinuation replacementState = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(replaced.continuation());
        assertFalse(replacementState.scope().contains("oldTail"));
    }

    @Test
    void fileReadResolvesARelativePathAgainstTheProcessWorkingDirectory() {
        Fixture fixture = new Fixture("content = file.read(\"index.json\")\n",
                com.follarce.extension.SourceExtensionIndex.catalog());
        StoredObject content = StoredObject.create(new BinaryContent(
                "market index".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "application/json", NOW);
        fixture.persistence.vfs.saveObject(content);
        com.follarce.domain.vfs.VfsNode root = new com.follarce.domain.vfs.VfsNode(
                UUID.randomUUID(), Optional.empty(), fixture.ownerId, "/",
                com.follarce.domain.vfs.VfsNode.Type.DIRECTORY, Optional.empty(),
                java.util.Set.of(), false, NOW, NOW);
        fixture.persistence.vfs.insertNode(root);
        com.follarce.domain.vfs.VfsNode market = new com.follarce.domain.vfs.VfsNode(
                UUID.randomUUID(), Optional.of(root.nodeId()), fixture.ownerId, "market",
                com.follarce.domain.vfs.VfsNode.Type.DIRECTORY, Optional.empty(),
                java.util.Set.of(), false, NOW, NOW);
        fixture.persistence.vfs.insertNode(market);
        fixture.persistence.vfs.insertNode(new com.follarce.domain.vfs.VfsNode(
                UUID.randomUUID(), Optional.of(market.nodeId()), fixture.ownerId, "index.json",
                com.follarce.domain.vfs.VfsNode.Type.FILE, Optional.of(content.objectHash()),
                java.util.Set.of(), false, NOW, NOW));
        FclContinuation runtime = new FclContinuation();
        runtime.scope().put(com.follarce.fcl.FclPath.SCOPE_KEY, "/market");
        Continuation seeded = new FclPersistenceBridge(new FclContinuationCodec())
                .persist(fixture.processUid, fixture.program, initial(fixture.program), runtime);
        CilProcess original = fixture.persistence.processes.current;
        fixture.persistence.processes.current = new CilProcess(original.identity(),
                original.ownerId(), original.status(), original.stateVersion(),
                original.executionEpoch(), seeded, original.parentProcessUid(),
                original.createdAt(), original.updatedAt());

        fixture.executor.executeSlice(fixture.claim);

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals("market index", restored.scope().get("content"));
    }

    @Test
    void createFileRejectsAnExistingFile() {
        Fixture fixture = new Fixture("""
                first = file.createFile("/note.txt", "first")
                second = file.createFile("/note.txt", "second")
                """, com.follarce.extension.SourceExtensionIndex.catalog());
        com.follarce.domain.vfs.VfsNode root = new com.follarce.domain.vfs.VfsNode(
                UUID.randomUUID(), Optional.empty(), fixture.ownerId, "/",
                com.follarce.domain.vfs.VfsNode.Type.DIRECTORY, Optional.empty(),
                java.util.Set.of(), false, NOW, NOW);
        fixture.persistence.vfs.insertNode(root);

        fixture.executor.executeSlice(fixture.claim);
        assertEquals(CilProcess.Status.FAILED, fixture.persistence.processes.current.status());
        com.follarce.domain.vfs.VfsNode created = fixture.persistence.vfs.findChild(
                fixture.ownerId, Optional.of(root.nodeId()), "note.txt").orElseThrow();
        assertEquals("first", new String(fixture.persistence.vfs.findObject(
                created.currentObjectHash().orElseThrow()).orElseThrow().content().bytes(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void failsAnUnknownHashImportWithoutPermanentWaits() {
        Fixture unresolved = new Fixture("import \"" + "f".repeat(64)
                + "\"\nvalue = 1\n");
        unresolved.executor.executeSlice(unresolved.claim);
        assertEquals(CilProcess.Status.FAILED,
                unresolved.persistence.processes.current.status());
        assertEquals(1, unresolved.persistence.timers.processDeletes);
        assertTrue(new FclPersistenceBridge(new FclContinuationCodec())
                .restore(unresolved.persistence.processes.current.continuation()).failed());

        Fixture failed = new Fixture("value = 1 / 0\n");
        failed.executor.executeSlice(failed.claim);
        assertEquals(CilProcess.Status.FAILED,
                failed.persistence.processes.current.status());
        assertEquals(1, failed.persistence.timers.processDeletes);
        FclContinuation failure = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(failed.persistence.processes.current.continuation());
        assertTrue(failure.halted());
        assertTrue(failure.failed());
        assertFalse(failure.exceptionStack().isEmpty());
    }

    @Test
    void compiledJavaExtensionsAreAlreadyRegisteredWithoutImport() {
        JavaExtensionCatalog extensions = JavaExtensionCatalog.compile(List.of(
                new CilExecExtension() {
                    @Override public ExtensionDescriptor descriptor() {
                        return new ExtensionDescriptor("test.greeting", "1.0.0", "test");
                    }

                    @Override public void register(ExtensionRegistrar registrar) {
                        registrar.function("greeting", "hello", context -> "hello");
                    }
                }));
        Fixture fixture = new Fixture("message = greeting.hello()\n", extensions);

        fixture.executor.executeSlice(fixture.claim);

        assertEquals(CilProcess.Status.TERMINATED, fixture.persistence.processes.current.status());
    }

    @Test
    void directlySignalsEffectWorkersAfterAnEffectRequestCommits() {
        AtomicInteger schedulerWakes = new AtomicInteger();
        AtomicInteger effectWakes = new AtomicInteger();
        Fixture fixture = new Fixture("io.print(1)\n",
                com.follarce.extension.SourceExtensionIndex.catalog(),
                schedulerWakes::incrementAndGet, effectWakes::incrementAndGet);

        fixture.executor.executeSlice(fixture.claim);

        assertEquals(CilProcess.Status.WAITING_EFFECT,
                fixture.persistence.processes.current.status());
        assertEquals(0, schedulerWakes.get());
        assertEquals(1, effectWakes.get());
    }

    @Test
    void linksAliasedPackageExportsAndPersistsTheExactHash() {
        PackageManifest manifest = new PackageManifest("demo", "hello", "1.0.0", "fcl-0.0.2",
                List.of(new PackageManifest.Module("main", "main.fcl")), List.of(), List.of(),
                List.of(new PackageManifest.Entrypoint("run", "main", "run")),
                List.of(new PackageManifest.Export("greet", "main", "greet")), List.of());
        byte[] database = new PackageBuilder().build(manifest, path -> """
                func prefix(value) { return "Hello, " + value }
                func greet(value) { return prefix(value) }
                func run() { return greet("package") }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var descriptor = new SqlitePackageReader().inspect(database);
        Fixture fixture = new Fixture("import \"" + descriptor.databaseFileHash()
                + "\" as \"helloPackage\"\nresult = helloPackage.greet(\"CilExec\")\n");
        StoredObject object = StoredObject.create(new BinaryContent(database),
                "application/vnd.sqlite3", NOW);
        fixture.persistence.vfs.saveObject(object);
        PackageRelease release = new PackageRelease(new PackageRelease.Coordinate("demo", "hello",
                "1.0.0"), new PackageRelease.Hash(new ObjectHash(descriptor.packageHash())),
                object.objectHash(), object.objectHash(),
                NOW);
        fixture.persistence.packages.releases.put(release.packageHash(), release);
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
            fixture.executor.executeSlice(fixture.claim);
        }

        assertEquals(CilProcess.Status.TERMINATED,
                fixture.persistence.processes.current.status());
        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals("Hello, CilExec", restored.scope().get("result"));
        assertEquals(release.packageHash().value(), fixture.persistence.processes.current
                .continuation().packageBindings().get("helloPackage"));
    }

    @Test
    void transitivelyLinksDependencyExportsByDatabaseFileSha256() {
        PackageManifest dependencyManifest = new PackageManifest("demo", "base", "1.0.0",
                "fcl-0.0.2", com.follarce.domain.packageinfo.PackageKind.LIBRARY,
                List.of(new PackageManifest.Module("main", "main.fcl")), List.of(), List.of(),
                List.of(), List.of(new PackageManifest.Export("value", "main", "value")),
                List.of());
        byte[] dependencyDatabase = new PackageBuilder().build(dependencyManifest, path ->
                "func value() { return 42 }\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var dependencyDescriptor = new SqlitePackageReader().inspect(dependencyDatabase);
        String dependencyHash = dependencyDescriptor.databaseFileHash();

        PackageManifest parentManifest = new PackageManifest("demo", "parent", "1.0.0",
                "fcl-0.0.2", com.follarce.domain.packageinfo.PackageKind.APPLICATION,
                List.of(new PackageManifest.Module("main", "main.fcl")), List.of(),
                List.of(new PackageManifest.Dependency(dependencyHash, false)),
                List.of(new PackageManifest.Entrypoint("run", "main", "run")),
                List.of(new PackageManifest.Export("answer", "main", "answer")), List.of());
        byte[] parentDatabase = new PackageBuilder().build(parentManifest, path -> ("func answer() { return "
                + dependencyHash + ".value() }\nfunc run() { return null }\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var parentDescriptor = new SqlitePackageReader().inspect(parentDatabase);
        Fixture fixture = new Fixture("import \"" + parentDescriptor.databaseFileHash()
                + "\"\nresult = " + parentDescriptor.databaseFileHash() + ".answer()\n");

        PackageRelease dependencyRelease = saveRelease(fixture, dependencyDatabase,
                dependencyDescriptor);
        PackageRelease parentRelease = saveRelease(fixture, parentDatabase, parentDescriptor);
        fixture.persistence.packages.releases.put(dependencyRelease.packageHash(),
                dependencyRelease);
        fixture.persistence.packages.releases.put(parentRelease.packageHash(), parentRelease);

        int steps = 0;
        while (!fixture.persistence.processes.current.isTerminal() && steps++ < 40) {
            if (fixture.persistence.processes.current.status() == CilProcess.Status.READY) {
                CilProcess claimed = fixture.persistence.processes.current.claim(
                        fixture.persistence.processes.current.executionEpoch() + 1, NOW);
                fixture.persistence.processes.current = claimed;
                fixture.claim = claim(fixture.processUid, fixture.ownerId,
                        claimed.executionEpoch());
                fixture.persistence.scheduler.lease = fixture.claim;
            }
            fixture.executor.executeSlice(fixture.claim);
        }

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals(42L, restored.scope().get("result"));
    }

    private static PackageRelease saveRelease(Fixture fixture, byte[] database,
                                               com.follarce.persistence.sqlite.PackageDescriptor descriptor) {
        StoredObject object = StoredObject.create(new BinaryContent(database),
                "application/vnd.sqlite3", NOW);
        fixture.persistence.vfs.saveObject(object);
        return new PackageRelease(new PackageRelease.Coordinate(descriptor.namespace(),
                descriptor.name(), descriptor.version()),
                new PackageRelease.Hash(new ObjectHash(descriptor.packageHash())),
                object.objectHash(), object.objectHash(), NOW);
    }

    @Test
    void resolvesAnInstalledPackageByDatabaseFileSha256WithOptionalAlias() {
        ObjectHash fileHash = new ObjectHash("a".repeat(64));
        PackageRelease release = new PackageRelease(
                new PackageRelease.Coordinate("demo", "same-name", "2.0.0"),
                new PackageRelease.Hash(new ObjectHash("b".repeat(64))),
                fileHash, fileHash, NOW);

        Fixture aliased = new Fixture("import \"" + fileHash.value() + "\" as \"chosen\"\n");
        aliased.persistence.packages.releases.put(release.packageHash(), release);
        aliased.executor.executeSlice(aliased.claim);
        assertEquals(CilProcess.Status.READY, aliased.persistence.processes.current.status());
        assertTrue(aliased.persistence.packages.findProcessBinding(
                aliased.processUid, "chosen").isPresent());

        Fixture unaliased = new Fixture("import \"" + fileHash.value() + "\"\n");
        unaliased.persistence.packages.releases.put(release.packageHash(), release);
        unaliased.executor.executeSlice(unaliased.claim);
        assertEquals(CilProcess.Status.READY,
                unaliased.persistence.processes.current.status());
    }

    @Test
    void resolvesAnInstalledPackageByHashAndPinsItsExactHash() {
        PackageManifest manifest = new PackageManifest("demo", "hello", "1.0.0", "fcl-0.0.2",
                List.of(new PackageManifest.Module("main", "main.fcl")), List.of(), List.of(),
                List.of(new PackageManifest.Entrypoint("run", "main", "run")),
                List.of(new PackageManifest.Export("greet", "main", "greet")),
                List.of());
        byte[] database = new PackageBuilder().build(manifest, path ->
                ("func greet(value) { return \"Hello, \" + value }\n"
                        + "func run() { return null }\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var descriptor = new SqlitePackageReader().inspect(database);
        Fixture fixture = new Fixture("import \"" + descriptor.databaseFileHash()
                + "\" as \"hello\"\nresult = hello.greet(\"CilExec\")\n");
        StoredObject object = StoredObject.create(new BinaryContent(database),
                "application/vnd.sqlite3", NOW);
        fixture.persistence.vfs.saveObject(object);
        PackageRelease release = new PackageRelease(new PackageRelease.Coordinate(
                "demo", "hello", "1.0.0"),
                new PackageRelease.Hash(new ObjectHash(descriptor.packageHash())),
                object.objectHash(), object.objectHash(), NOW);
        fixture.persistence.packages.releases.put(release.packageHash(), release);

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
            fixture.executor.executeSlice(fixture.claim);
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
    void rebindingTheSameAliasRepinsTheProcessToTheNewestHash() {
        byte[] firstDatabase = buildEditorDatabase("first");
        byte[] secondDatabase = buildEditorDatabase("second");
        var firstDescriptor = new SqlitePackageReader().inspect(firstDatabase);
        var secondDescriptor = new SqlitePackageReader().inspect(secondDatabase);
        Fixture fixture = new Fixture("import \"" + firstDescriptor.databaseFileHash()
                + "\" as \"m\"\nimport \"" + secondDescriptor.databaseFileHash()
                + "\" as \"m\"\nresult = m.greet(\"ok\")\n");
        StoredObject firstObject = StoredObject.create(new BinaryContent(firstDatabase),
                "application/vnd.sqlite3", NOW);
        StoredObject secondObject = StoredObject.create(new BinaryContent(secondDatabase),
                "application/vnd.sqlite3", NOW);
        fixture.persistence.vfs.saveObject(firstObject);
        fixture.persistence.vfs.saveObject(secondObject);
        register(fixture, "1.0.0", firstDescriptor, firstObject);
        register(fixture, "1.1.0", secondDescriptor, secondObject);

        int steps = 0;
        while (!fixture.persistence.processes.current.isTerminal() && steps++ < 40) {
            if (fixture.persistence.processes.current.status() == CilProcess.Status.READY) {
                CilProcess claimed = fixture.persistence.processes.current.claim(
                        fixture.persistence.processes.current.executionEpoch() + 1, NOW);
                fixture.persistence.processes.current = claimed;
                fixture.claim = claim(fixture.processUid, fixture.ownerId,
                        claimed.executionEpoch());
                fixture.persistence.scheduler.lease = fixture.claim;
            }
            fixture.executor.executeSlice(fixture.claim);
        }

        assertEquals(CilProcess.Status.TERMINATED,
                fixture.persistence.processes.current.status());
        ProcessPackageBinding pinned = fixture.persistence.packages.findProcessBinding(
                fixture.processUid, "m").orElseThrow();
        assertEquals(new ObjectHash(secondDescriptor.packageHash()), pinned.packageHash().value(),
                "the last import of the same alias must win");
        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals("second:ok", restored.scope().get("result"));
    }

    private static byte[] buildEditorDatabase(String tag) {
        return new PackageBuilder().build(new PackageManifest("demo", "pkg", "1.0.0", "fcl-0.0.2",
                List.of(new PackageManifest.Module("main", "main.fcl")), List.of(), List.of(),
                List.of(new PackageManifest.Entrypoint("run", "main", "run")),
                List.of(new PackageManifest.Export("greet", "main", "greet")), List.of()),
                path -> ("func greet(value) { return \"" + tag + ":ok\" }\n"
                        + "func run() { return null }\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void register(Fixture fixture, String version,
                                 PackageDescriptor descriptor, StoredObject object) {
        PackageRelease release = new PackageRelease(new PackageRelease.Coordinate(
                "demo", "pkg", version),
                new PackageRelease.Hash(new ObjectHash(descriptor.packageHash())),
                object.objectHash(), object.objectHash(), NOW);
        fixture.persistence.packages.releases.put(release.packageHash(), release);
    }

    @Test
    void persistsAndResolvesCaseInsensitiveFclEnvironmentVariables() {
        Fixture fixture = new Fixture("""
                env.set("market_origin", "https://example.test")
                configured = env.get("MARKET_ORIGIN")
                visible = env.list()
                """, com.follarce.extension.SourceExtensionIndex.catalog());

        int steps = 0;
        while (!fixture.persistence.processes.current.isTerminal() && steps++ < 20) {
            if (fixture.persistence.processes.current.status() == CilProcess.Status.READY) {
                CilProcess claimed = fixture.persistence.processes.current.claim(
                        fixture.persistence.processes.current.executionEpoch() + 1, NOW);
                fixture.persistence.processes.current = claimed;
                fixture.claim = claim(fixture.processUid, fixture.ownerId,
                        claimed.executionEpoch());
                fixture.persistence.scheduler.lease = fixture.claim;
            }
            fixture.executor.executeSlice(fixture.claim);
        }

        assertEquals("https://example.test", fixture.persistence.environment
                .findUser(fixture.ownerId, "MARKET_ORIGIN").orElseThrow());
        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals("https://example.test", restored.scope().get("configured"));
        assertTrue(restored.scope().get("visible") instanceof Map<?, ?> values
                && "https://example.test".equals(values.get("MARKET_ORIGIN")));
    }

    @Test
    void exposesPwdOnlyThroughTheReadOnlyEnvironmentInterfaceInsideFunctions() {
        Fixture fixture = new Fixture("""
                func readWorkingDirectory() { return env.get("PWD") }
                current = readWorkingDirectory()
                runtimeUser = env.get("USER")
                runtimeUserId = env.get("USER_ID")
                runtimePid = env.get("PID")
                """, com.follarce.extension.SourceExtensionIndex.catalog());
        FclContinuation runtime = new FclContinuation();
        runtime.scope().put(com.follarce.fcl.FclPath.SCOPE_KEY, "/market");
        Continuation seededContinuation = new FclPersistenceBridge(new FclContinuationCodec())
                .persist(fixture.processUid, fixture.program, initial(fixture.program), runtime);
        CilProcess original = fixture.persistence.processes.current;
        fixture.persistence.processes.current = new CilProcess(original.identity(),
                original.ownerId(), original.status(), original.stateVersion(),
                original.executionEpoch(), seededContinuation, original.parentProcessUid(),
                original.createdAt(), original.updatedAt());

        int steps = 0;
        while (!fixture.persistence.processes.current.isTerminal() && steps++ < 20) {
            if (fixture.persistence.processes.current.status() == CilProcess.Status.READY) {
                CilProcess claimed = fixture.persistence.processes.current.claim(
                        fixture.persistence.processes.current.executionEpoch() + 1, NOW);
                fixture.persistence.processes.current = claimed;
                fixture.claim = claim(fixture.processUid, fixture.ownerId,
                        claimed.executionEpoch());
                fixture.persistence.scheduler.lease = fixture.claim;
            }
            fixture.executor.executeSlice(fixture.claim);
        }

        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(fixture.persistence.processes.current.continuation());
        assertEquals("/market", restored.scope().get("current"));
        assertEquals("test", restored.scope().get("runtimeUser"));
        assertEquals(fixture.ownerId.toString(), restored.scope().get("runtimeUserId"));
        assertEquals(Long.toString(fixture.persistence.processes.current.identity().pid()),
                restored.scope().get("runtimePid"));

        Fixture writeAttempt = new Fixture("env.set(\"PWD\", \"/changed\")\n",
                com.follarce.extension.SourceExtensionIndex.catalog());
        writeAttempt.executor.executeSlice(writeAttempt.claim);
        assertEquals(CilProcess.Status.FAILED,
                writeAttempt.persistence.processes.current.status());
    }

    @Test
    void persistsCompletionAndRemovesClaimInTheSameTransaction() {
        Fixture fixture = new Fixture("");

        fixture.executor.executeSlice(fixture.claim);

        assertEquals(CilProcess.Status.TERMINATED,
                fixture.persistence.processes.current.status());
        assertEquals(1, fixture.persistence.scheduler.releases);
        assertEquals(1, fixture.persistence.timers.processDeletes);
        assertEquals(fixture.processUid, fixture.persistence.timers.deletedProcess);
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
    void restoresNullVariablesFromNormalizedScopeProjection() {
        Fixture fixture = new Fixture("value = null\n");
        FclPersistenceBridge bridge = new FclPersistenceBridge(new FclContinuationCodec());
        FclContinuation runtime = new FclContinuation();
        runtime.scope().put("value", null);

        Continuation persisted = bridge.persist(fixture.processUid, fixture.program,
                initial(fixture.program), runtime);
        FclContinuation restored = bridge.restore(persisted);

        assertTrue(restored.scope().contains("value"));
        assertEquals(null, restored.scope().get("value"));
    }

    @Test
    void rejectsExpiredOrWrongEpochClaimsBeforeExecuting() {
        Fixture expired = new Fixture("value = 1\n");
        expired.claim = new SchedulerClaim(expired.processUid, expired.ownerId,
                UUID.randomUUID(), UUID.randomUUID(), 5, NOW.minusSeconds(10), NOW.minusSeconds(10),
                NOW.minusSeconds(1));
        expired.persistence.scheduler.lease = expired.claim;
        assertThrows(ProcessStatementExecutor.StaleClaimException.class,
                () -> expired.executor.executeSlice(expired.claim));
        assertEquals(0, expired.persistence.processes.updates);
        assertEquals(0, expired.persistence.scheduler.releases);

        Fixture wrongEpoch = new Fixture("value = 1\n");
        wrongEpoch.claim = claim(wrongEpoch.processUid, wrongEpoch.ownerId, 6);
        wrongEpoch.persistence.scheduler.lease = wrongEpoch.claim;
        assertThrows(ProcessStatementExecutor.StaleClaimException.class,
                () -> wrongEpoch.executor.executeSlice(wrongEpoch.claim));
        assertEquals(0, wrongEpoch.persistence.processes.updates);
        assertEquals(0, wrongEpoch.persistence.scheduler.releases);
    }

    @Test
    void doesNotReleaseQueueWhenCompareAndSetIsRejected() {
        Fixture fenced = new Fixture("value = 1\n");
        fenced.persistence.processes.forcedResult = ProcessRepository.UpdateResult.EPOCH_FENCED;
        assertThrows(ProcessStatementExecutor.StaleClaimException.class,
                () -> fenced.executor.executeSlice(fenced.claim));
        assertEquals(0, fenced.persistence.scheduler.releases);

        Fixture conflicted = new Fixture("value = 1\n");
        conflicted.persistence.processes.forcedResult =
                ProcessRepository.UpdateResult.VERSION_CONFLICT;
        RuntimeException failure = assertThrows(
                ProcessStatementExecutor.StatementConflictException.class,
                () -> conflicted.executor.executeSlice(conflicted.claim));
        assertInstanceOf(ProcessStatementExecutor.StatementConflictException.class, failure);
        assertEquals(0, conflicted.persistence.scheduler.releases);
    }

    @Test
    void consumesDurableInterruptAtTheStatementSafePoint() {
        Fixture fixture = new Fixture("value = 1\n");
        fixture.persistence.terminal.interrupt = true;

        fixture.executor.executeSlice(fixture.claim);

        assertEquals(CilProcess.Status.TERMINATED,
                fixture.persistence.processes.current.status());
        assertEquals(2, fixture.persistence.processes.updates);
        assertEquals(1, fixture.persistence.scheduler.releases);
        assertFalse(fixture.persistence.terminal.interrupt);
        assertEquals(0, fixture.persistence.processes.current.continuation().programCounter());
        assertEquals(1, fixture.persistence.timers.processDeletes);
        assertEquals(fixture.processUid, fixture.persistence.timers.deletedProcess);
    }

    private static SchedulerClaim claim(UUID processUid, UUID ownerId, long epoch) {        return new SchedulerClaim(processUid, ownerId, UUID.randomUUID(), UUID.randomUUID(), epoch,
                NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plus(Duration.ofMinutes(1)));
    }

    private static Continuation initial(Program program) {
        return new Continuation(program.programId(), program.programHash(), 0,
                List.of(), List.of(), List.of(), List.of(), Optional.empty(), Map.of(), Map.of(),
                program.languageVersion(), Integer.toString(program.runtimeFormatVersion()));
    }

    private static void runToCompletion(Fixture fixture) {
        while (fixture.persistence.processes.current.status() == CilProcess.Status.RUNNING
                || fixture.persistence.processes.current.status() == CilProcess.Status.READY) {
            fixture.executor.executeSlice(fixture.claim);
            CilProcess current = fixture.persistence.processes.current;
            if (current.status() == CilProcess.Status.READY) {
                CilProcess claimed = current.claim(current.executionEpoch() + 1, NOW);
                fixture.persistence.processes.current = claimed;
                fixture.claim = claim(fixture.processUid, fixture.ownerId, claimed.executionEpoch());
                fixture.persistence.scheduler.lease = fixture.claim;
            }
        }
        assertEquals(CilProcess.Status.TERMINATED,
                fixture.persistence.processes.current.status());
    }

    private static void runToFailure(Fixture fixture) {
        while (fixture.persistence.processes.current.status() == CilProcess.Status.RUNNING
                || fixture.persistence.processes.current.status() == CilProcess.Status.READY) {
            fixture.executor.executeSlice(fixture.claim);
            CilProcess current = fixture.persistence.processes.current;
            if (current.status() == CilProcess.Status.READY) {
                CilProcess claimed = current.claim(current.executionEpoch() + 1, NOW);
                fixture.persistence.processes.current = claimed;
                fixture.claim = claim(fixture.processUid, fixture.ownerId, claimed.executionEpoch());
                fixture.persistence.scheduler.lease = fixture.claim;
            }
        }
        assertEquals(CilProcess.Status.FAILED,
                fixture.persistence.processes.current.status());
    }

    private static final class Fixture {
        final ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        final UUID ownerId = UUID.randomUUID();
        final UUID processUid = UUID.randomUUID();
        final ProcessStatementExecutor executor;
        SchedulerClaim claim;
        final Program program;

        Fixture(String source) {
            this(source, null);
        }

        Fixture(String source, JavaExtensionCatalog extensions) {
            this(source, extensions, () -> { }, () -> { });
        }

        Fixture(String source, JavaExtensionCatalog extensions,
                Runnable schedulerWake, Runnable effectWake) {
            executor = extensions == null
                    ? new ProcessStatementExecutor(persistence,
                    new FclRuntime(FclBuiltins.pureRegistry()), new FclProgramCodec(),
                    new FclContinuationCodec(), CLOCK)
                    : new ProcessStatementExecutor(persistence, extensions, null,
                    new FclProgramCodec(), new FclContinuationCodec(), CLOCK,
                    schedulerWake, effectWake);
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
