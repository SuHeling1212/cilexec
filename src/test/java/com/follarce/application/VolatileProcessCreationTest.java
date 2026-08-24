package com.follarce.application;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolatileProcessCreationTest {
    @Test
    void requestsVolatileWorkWithoutCreatingDurableProcessProgramOrAuditState() {
        ProgramServiceTest.TestPersistence persistence = new ProgramServiceTest.TestPersistence();
        UUID ownerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-22T00:00:00Z");
        createSourceFile(persistence, ownerId, now, "calculation.fcl", """
                value = 0
                while value < 10 {
                    value++
                }
                """);

        var parentProgram = new ProgramService(persistence).create(ownerId,
                "accepted = process.run(\"/calculation.fcl\", [])\n");
        CilProcess parent = new ProcessService(persistence).create(ownerId, parentProgram,
                Optional.empty());
        int programsBefore = persistence.programs.byId.size();
        int objectsBefore = persistence.vfs.objects.size();
        int auditBefore = persistence.audit.events.size();
        List<VolatileProcessRequest> requests = new ArrayList<>();

        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(FclRuntimeFunctions.create(persistence, parent,
                parentProgram, continuation, Clock.systemUTC().instant(),
                com.follarce.extension.SourceExtensionIndex.catalog(), requests::add));
        var compiled = new FclCompiler().compile(
                "accepted = process.run(\"/calculation.fcl\", [])\n");
        assertEquals(FclStepResult.Status.WAITING, runtime.executeOne(compiled, continuation)
                .status());

        assertEquals(1, requests.size());
        assertEquals("/calculation.fcl", requests.getFirst().sourcePath());
        assertEquals(parent.identity().processUid(), persistence.processes.current.identity().processUid());
        assertEquals(programsBefore, persistence.programs.byId.size());
        assertEquals(objectsBefore, persistence.vfs.objects.size());
        assertEquals(auditBefore, persistence.audit.events.size());
    }

    private static void createSourceFile(ProgramServiceTest.TestPersistence persistence,
                                         UUID ownerId, Instant now, String name, String source) {
        VfsNode root = new VfsNode(UUID.randomUUID(), Optional.empty(), ownerId, "/",
                VfsNode.Type.DIRECTORY, Optional.empty(), Set.of(), false, now, now);
        StoredObject object = StoredObject.create(new BinaryContent(
                source.getBytes(StandardCharsets.UTF_8)), "text/x-fcl; charset=utf-8", now);
        VfsNode file = new VfsNode(UUID.randomUUID(), Optional.of(root.nodeId()), ownerId, name,
                VfsNode.Type.FILE, Optional.of(object.objectHash()), Set.of(), false, now, now);
        persistence.vfs.insertNode(root);
        persistence.vfs.saveObject(object);
        persistence.vfs.insertNode(file);
    }
}
