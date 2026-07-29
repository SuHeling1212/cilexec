package com.follarce.domain;

import com.follarce.domain.packageinfo.PackageBinding;
import com.follarce.domain.packageinfo.PackageEnvironment;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VfsPackageDomainTest {
    private static final Instant T0 = Instant.parse("2026-07-22T02:00:00Z");

    @Test
    void storedObjectDefensivelyOwnsBytesAndVerifiesHash() {
        byte[] mutable = "content".getBytes(StandardCharsets.UTF_8);
        BinaryContent content = new BinaryContent(mutable);
        StoredObject stored = StoredObject.create(content, "text/plain", T0);

        mutable[0] = 'X';
        byte[] returned = stored.content().bytes();
        returned[1] = 'Y';

        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), stored.content().bytes());
        assertEquals(stored.objectHash(), ObjectHash.sha256(stored.content()));
        assertThrows(IllegalArgumentException.class, () -> new StoredObject(
                hash("different"), stored.byteSize(), stored.mediaType(), stored.content(), T0));
    }

    @Test
    void fileNodeReplacesOnlyItsImmutableObjectPointer() {
        UUID nodeId = UUID.randomUUID();
        ObjectHash first = hash("first");
        ObjectHash second = hash("second");
        VfsNode node = new VfsNode(nodeId, Optional.of(UUID.randomUUID()), UUID.randomUUID(),
                "data.fcl", VfsNode.Type.FILE, Optional.of(first), Set.of("read", "write"),
                true, T0, T0);

        VfsNode replaced = node.replaceContent(second, T0.plusSeconds(1));

        assertEquals(Optional.of(first), node.currentObjectHash());
        assertEquals(Optional.of(second), replaced.currentObjectHash());
        assertEquals(nodeId, replaced.nodeId());
        assertThrows(IllegalArgumentException.class, () -> new VfsNode(
                UUID.randomUUID(), Optional.of(UUID.randomUUID()), UUID.randomUUID(), "..",
                VfsNode.Type.FILE, Optional.of(first), Set.of(), false, T0, T0));
    }

    @Test
    void directoryCannotCarryFileContentAndRootHasCanonicalName() {
        assertThrows(IllegalArgumentException.class, () -> new VfsNode(
                UUID.randomUUID(), Optional.of(UUID.randomUUID()), UUID.randomUUID(), "folder",
                VfsNode.Type.DIRECTORY, Optional.of(hash("illegal")), Set.of(), false, T0, T0));
        assertThrows(IllegalArgumentException.class, () -> new VfsNode(
                UUID.randomUUID(), Optional.empty(), UUID.randomUUID(), "root",
                VfsNode.Type.DIRECTORY, Optional.empty(), Set.of(), false, T0, T0));
    }

    @Test
    void packageReleaseSeparatesLogicalIdentityFromOriginalDatabaseBytes() {
        ObjectHash databaseHash = hash("sqlite-database-bytes");
        PackageRelease first = release("1.0.0", hash("logical-content"), databaseHash);
        PackageRelease same = release("1.0.0", first.packageHash().value(), databaseHash);

        assertEquals("std/network/1.0.0", first.coordinate().key());
        assertEquals(first.packageHash(), same.packageHash());
        assertEquals(first.databaseObjectHash(), first.databaseFileHash());
        assertNotEquals(first.packageHash().value(), first.databaseFileHash());
        assertThrows(IllegalArgumentException.class, () -> new PackageRelease(
                first.coordinate(), first.packageHash(), databaseHash, hash("other-bytes"),
                T0));
    }

    @Test
    void environmentsAndProcessBindingsPinExactPackageHashes() {
        UUID owner = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        PackageEnvironment environment = new PackageEnvironment(environmentId, owner, "default",
                Optional.empty(), PackageEnvironment.Status.ACTIVE, T0);
        PackageRelease.Hash versionOne = new PackageRelease.Hash(hash("v1"));
        PackageRelease.Hash versionTwo = new PackageRelease.Hash(hash("v2"));
        PackageBinding current = new PackageBinding(environmentId, "network", versionOne, T0);
        ProcessPackageBinding pinned = new ProcessPackageBinding(UUID.randomUUID(), "network",
                environmentId, current.packageHash(), T0);
        ProcessPackageBinding hashImported = new ProcessPackageBinding(UUID.randomUUID(),
                "a".repeat(64), environmentId, current.packageHash(), T0);
        PackageBinding upgraded = new PackageBinding(environmentId, "network", versionTwo,
                T0.plusSeconds(1));

        assertEquals(PackageEnvironment.Status.ACTIVE, environment.status());
        assertEquals(versionOne, pinned.packageHash());
        assertEquals("a".repeat(64), hashImported.importName());
        assertNotEquals(upgraded.packageHash(), pinned.packageHash(),
                "environment changes cannot mutate a running process binding");
        assertThrows(IllegalArgumentException.class, () -> new PackageBinding(
                environmentId, "bad-binding", versionOne, T0));
        assertThrows(IllegalArgumentException.class, () -> new ProcessPackageBinding(
                UUID.randomUUID(), "0" + "A".repeat(63), environmentId, versionOne, T0));
    }

    @Test
    void packageCoordinatesRejectUnsafeOrEmptyComponents() {
        assertThrows(IllegalArgumentException.class,
                () -> new PackageRelease.Coordinate("../escape", "demo", "1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> new PackageRelease.Coordinate("std", " ", "1.0"));
    }

    private static PackageRelease release(
            String version,
            ObjectHash logicalHash,
            ObjectHash databaseHash
    ) {
        return new PackageRelease(new PackageRelease.Coordinate("std", "network", version),
                new PackageRelease.Hash(logicalHash), databaseHash, databaseHash, T0);
    }

    private static ObjectHash hash(String value) {
        return ObjectHash.sha256(new BinaryContent(value.getBytes(StandardCharsets.UTF_8)));
    }
}
