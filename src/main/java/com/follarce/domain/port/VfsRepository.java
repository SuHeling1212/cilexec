package com.follarce.domain.port;

import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.FileRevision;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsMount;
import com.follarce.domain.vfs.VfsNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VfsRepository {
    void saveObject(StoredObject object);

    Optional<StoredObject> findObject(ObjectHash objectHash);

    Optional<VfsNode> findNode(UUID nodeId);

    Optional<VfsNode> findChild(UUID ownerId, Optional<UUID> parentNodeId, String name);

    void insertNode(VfsNode node);

    boolean replaceContent(
            UUID nodeId,
            Optional<ObjectHash> expectedObjectHash,
            ObjectHash replacementObjectHash,
            Instant updatedAt
    );

    FileRevision appendRevision(
            UUID revisionId,
            UUID nodeId,
            UUID ownerId,
            ObjectHash objectHash,
            UUID createdBy,
            Instant createdAt
    );

    Optional<FileRevision> findRevision(UUID nodeId, long revisionNumber);

    List<FileRevision> findRevisions(UUID nodeId);

    void insertMount(VfsMount mount);

    Optional<VfsMount> findMount(UUID mountId);

    List<VfsMount> findMounts(UUID ownerId);

    boolean disableMount(UUID mountId, UUID ownerId);
}
