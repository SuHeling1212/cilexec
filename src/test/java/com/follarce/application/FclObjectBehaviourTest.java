package com.follarce.application;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.program.Program;
import com.follarce.domain.ipc.IpcDelivery;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclSystemHandle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted 0.0.5 object checks that do not need PostgreSQL: rename survival, deletion
 * invalidation, per-call permission re-checks, lease expiry, and duplicate delivery consume.
 */
class FclObjectBehaviourTest {
    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");

    private record Fixture(ProgramServiceTest.TestPersistence persistence, UUID ownerId,
                           Program program, CilProcess process, FclContinuation continuation) {
        FclFunctionRegistry registry(Instant now) {
            return FclRuntimeFunctions.create(persistence, process, program, continuation, now);
        }

        FclFunctionRegistry.Invocation invocation(long expressionId) {
            return new FclFunctionRegistry.Invocation(expressionId, continuation);
        }
    }

    @Test
    void fileHandleSurvivesRenameButDiesWithTheNodeAndIsNotRevivedByANewPath() {
        Fixture fixture = fixture();
        FclFunctionRegistry registry = fixture.registry(NOW);

        FclSystemHandle file = (FclSystemHandle) registry.invoke("file.create",
                List.of("/note.txt", "body"), fixture.invocation(1));
        assertEquals("File", file.kind());
        assertEquals(true, registry.invokeMethod(file, "rename",
                List.of("renamed.txt"), fixture.invocation(2)));
        assertEquals("body", registry.invokeMethod(file, "read",
                List.of(), fixture.invocation(3)));

        assertEquals(true, registry.invokeMethod(file, "remove",
                List.of(), fixture.invocation(4)));
        FclRuntimeException stale = assertThrows(FclRuntimeException.class,
                () -> registry.invokeMethod(file, "read", List.of(), fixture.invocation(5)));
        assertEquals("StaleHandle", stale.type());

        FclSystemHandle replacement = (FclSystemHandle) registry.invoke("file.create",
                List.of("/note.txt", "new"), fixture.invocation(6));
        assertEquals("new", registry.invokeMethod(replacement, "read",
                List.of(), fixture.invocation(7)));
        assertThrows(FclRuntimeException.class,
                () -> registry.invokeMethod(file, "read", List.of(), fixture.invocation(8)));
    }


    @Test
    void systemProjectionIsInvisibleToNonAdministrators() {
        Fixture fixture = fixture();
        fixture.persistence.auth.capabilities.set(Set.of(
                com.follarce.domain.auth.Capability.VFS_READ,
                com.follarce.domain.auth.Capability.VFS_WRITE));
        FclFunctionRegistry registry = fixture.registry(NOW);

        FclRuntimeException hidden = assertThrows(FclRuntimeException.class,
                () -> registry.invoke("file.open",
                        List.of("/.cilexec/system/db"), fixture.invocation(1)));
        assertEquals("PathNotFound", hidden.type());
        FclSystemHandle projectedPath = (FclSystemHandle) registry.invoke("file.path",
                List.of("/.cilexec/system/db"), fixture.invocation(2));
        assertEquals(false, registry.invokeMethod(projectedPath, "exists",
                List.of(), fixture.invocation(2)));
        FclRuntimeException write = assertThrows(FclRuntimeException.class,
                () -> registry.invoke("file.create",
                        List.of("/.cilexec/system/db/auth/x.json", "{}"),
                        fixture.invocation(3)));
        assertEquals("PathNotFound", write.type());

        FclRuntimeException reserved = assertThrows(FclRuntimeException.class,
                () -> registry.invoke("file.create",
                        List.of("/.cilexec/ordinary.txt", "body"),
                        fixture.invocation(4)));
        assertEquals("ReadOnlyProjection", reserved.type());
    }

    @Test
    void realReservedNodeRejectsProjectionInsteadOfBeingHidden() {
        Fixture fixture = fixture();
        VfsNode root = fixture.persistence.vfs.findChild(fixture.ownerId,
                Optional.empty(), "/").orElseThrow();
        fixture.persistence.vfs.insertNode(new VfsNode(UUID.randomUUID(),
                Optional.of(root.nodeId()), fixture.ownerId, ".cilexec",
                VfsNode.Type.DIRECTORY, Optional.empty(), Set.of(), false, NOW, NOW));

        SystemProjection projection = new SystemProjection(fixture.persistence,
                fixture.ownerId, NOW);
        FclRuntimeException conflict = assertThrows(FclRuntimeException.class,
                () -> projection.exists("/.cilexec/system/db"));
        assertEquals("ProjectionConflict", conflict.type());
        FclRuntimeException open = assertThrows(FclRuntimeException.class,
                () -> fixture.registry(NOW).invoke("file.open",
                        List.of("/.cilexec/system/db"), fixture.invocation(1)));
        assertEquals("ProjectionConflict", open.type());
        FclRuntimeException shell = assertThrows(FclRuntimeException.class,
                () -> projection.isShellDirectory("/.cilexec"));
        assertEquals("ProjectionConflict", shell.type());
    }

    @Test
    void renameCannotCreateReservedRoot() {
        Fixture fixture = fixture();
        FclFunctionRegistry registry = fixture.registry(NOW);
        FclSystemHandle file = (FclSystemHandle) registry.invoke("file.create",
                List.of("/ordinary.txt", "body"), fixture.invocation(1));
        FclRuntimeException rejected = assertThrows(FclRuntimeException.class,
                () -> registry.invokeMethod(file, "rename", List.of(".cilexec"),
                        fixture.invocation(2)));
        assertEquals("ReadOnlyProjection", rejected.type());
    }

    @Test
    void eachMethodCallRechecksTheCurrentCapabilities() {
        Fixture fixture = fixture();
        FclFunctionRegistry registry = fixture.registry(NOW);
        FclSystemHandle file = (FclSystemHandle) registry.invoke("file.create",
                List.of("/note.txt", "body"), fixture.invocation(1));
        assertEquals("body", registry.invokeMethod(file, "read",
                List.of(), fixture.invocation(2)));

        fixture.persistence.auth.capabilities.set(Set.of());

        assertThrows(RuntimeException.class,
                () -> registry.invokeMethod(file, "read", List.of(), fixture.invocation(3)));
        assertThrows(RuntimeException.class,
                () -> registry.invokeMethod(file, "write", List.of("blocked"),
                        fixture.invocation(4)));

        fixture.persistence.auth.capabilities.set(
                Set.copyOf(java.util.EnumSet.allOf(com.follarce.domain.auth.Capability.class)));
        assertEquals("body", registry.invokeMethod(file, "read",
                List.of(), fixture.invocation(5)));
    }

    @Test
    void anExpiredFileLeaseCannotBeRenewed() {
        Fixture fixture = fixture();
        FclFunctionRegistry registry = fixture.registry(NOW);
        FclSystemHandle file = (FclSystemHandle) registry.invoke("file.create",
                List.of("/lease.txt", "body"), fixture.invocation(1));

        FclSystemHandle lease = (FclSystemHandle) registry.invokeMethod(file, "lock",
                List.of(60_000L), fixture.invocation(2));
        assertEquals("FileLease", lease.kind());
        assertEquals(lease, registry.invokeMethod(lease, "renew",
                List.of(60_000L), fixture.invocation(3)));

        FclFunctionRegistry later = fixture.registry(NOW.plusSeconds(120));
        FclRuntimeException expired = assertThrows(FclRuntimeException.class,
                () -> later.invokeMethod(lease, "renew", List.of(60_000L),
                        fixture.invocation(4)));
        assertEquals("ExpiredLease", expired.type());

        // A later registry can still read the node; the handle itself is not stale - only the
        // lease expired. The last minted token may still delete its own lock row, but it can no
        // longer renew the expired lease.
        assertEquals("body", later.invokeMethod(file, "read",
                List.of(), fixture.invocation(5)));
        assertEquals(true, later.invokeMethod(lease, "release",
                List.of(), fixture.invocation(6)));
    }

    @Test
    void swapPoolNamesAreValidatedBeforeAnyDatabaseAccess() {
        Fixture fixture = fixture();
        FclFunctionRegistry registry = fixture.registry(NOW);

        FclRuntimeException invalidPool = assertThrows(FclRuntimeException.class,
                () -> registry.invoke("swapPool.create", List.of("/pools/demo"),
                        fixture.invocation(1)));
        assertEquals("InvalidSwapPoolName", invalidPool.type());
        assertTrue(invalidPool.getMessage().contains("A-Za-z0-9_-"),
                invalidPool.getMessage());
    }

    @Test
    void aDeliveryConsumesAtMostOnceAndOnlyForItsReceiver() {
        Fixture fixture = fixture();
        FclFunctionRegistry registry = fixture.registry(NOW);
        UUID processUid = fixture.process.identity().processUid();
        UUID deliveryId = UUID.randomUUID();
        IpcDelivery reserved = IpcDelivery.pending(deliveryId, UUID.randomUUID(), processUid)
                .reserve(UUID.randomUUID(), NOW);
        fixture.persistence.ipc.deliveries.put(deliveryId, reserved);

        FclSystemHandle delivery = (FclSystemHandle) registry.invoke("ipc.delivery",
                List.of(deliveryId.toString()), fixture.invocation(1));
        assertEquals("Delivery", delivery.kind());
        assertEquals(true, registry.invokeMethod(delivery, "consume",
                List.of(), fixture.invocation(2)));
        assertEquals(false, registry.invokeMethod(delivery, "consume",
                List.of(), fixture.invocation(3)));

        UUID foreignId = UUID.randomUUID();
        fixture.persistence.ipc.deliveries.put(foreignId,
                IpcDelivery.pending(foreignId, UUID.randomUUID(), UUID.randomUUID())
                        .reserve(UUID.randomUUID(), NOW));
        assertThrows(FclRuntimeException.class, () -> registry.invoke("ipc.delivery",
                List.of(foreignId.toString()), fixture.invocation(4)));
        assertTrue(registry.qualifiedNames().contains("ipc.delivery"));
    }

    private static Fixture fixture() {
        ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        UUID ownerId = UUID.randomUUID();
        Program program = new ProgramService(persistence).create(ownerId, "value = 1\n");
        CilProcess process = new ProcessService(persistence).create(ownerId, program,
                Optional.empty());
        persistence.vfs.insertNode(new VfsNode(UUID.randomUUID(), Optional.empty(), ownerId,
                "/", VfsNode.Type.DIRECTORY, Optional.empty(), Set.of(), false, NOW, NOW));
        return new Fixture(persistence, ownerId, program, process, new FclContinuation());
    }
}
