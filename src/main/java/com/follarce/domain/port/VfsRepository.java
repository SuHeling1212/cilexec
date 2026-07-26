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

    /** Reads immutable bytes from the trusted runtime administrator path. */
    default Optional<StoredObject> findObjectByAdministrator(ObjectHash objectHash) {
        throw new UnsupportedOperationException("Administrator object read is not implemented");
    }

    Optional<VfsNode> findNode(UUID nodeId);

    Optional<VfsNode> findChild(UUID ownerId, Optional<UUID> parentNodeId, String name);

    /** Lists one owner's direct children. RLS still scopes calls made as a user principal. */
    default List<VfsNode> findChildren(UUID ownerId, Optional<UUID> parentNodeId) {
        throw new UnsupportedOperationException("VFS child listing is not implemented");
    }

    /** Lists every node owned by one user. Intended for the administrator application path. */
    default List<VfsNode> findAllNodes(UUID ownerId) {
        throw new UnsupportedOperationException("VFS owner listing is not implemented");
    }

    /** Capability-checked cross-user listing in the caller's current user transaction. */
    default List<VfsNode> findAllNodesByAdministrator(UUID administratorId, UUID ownerId,
                                                      UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Atomic administrator VFS listing is not implemented");
    }

    /** Capability-checked cross-user content read in the current user transaction. */
    default StoredObject readFileByAdministrator(UUID administratorId, UUID ownerId,
                                                 UUID nodeId, UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Atomic administrator VFS read is not implemented");
    }

    void insertNode(VfsNode node);

    default boolean renameNode(UUID nodeId, UUID ownerId, String replacementName,
                               Instant updatedAt) {
        throw new UnsupportedOperationException("VFS rename is not implemented");
    }

    default boolean deleteNode(UUID nodeId, UUID ownerId) {
        throw new UnsupportedOperationException("VFS deletion is not implemented");
    }

    boolean replaceContent(
            UUID nodeId,
            Optional<ObjectHash> expectedObjectHash,
            ObjectHash replacementObjectHash,
            Instant updatedAt
    );

    /** Saves immutable content while a verified administrator is acting for another owner. */
    default void saveObjectByAdministrator(StoredObject object, UUID administratorId) {
        throw new UnsupportedOperationException("Administrator object write is not implemented");
    }

    /** Replaces cross-user content and appends history atomically with the FCL continuation. */
    default VfsNode replaceContentByAdministrator(UUID administratorId, UUID ownerId,
                                                  UUID nodeId, StoredObject object,
                                                  UUID revisionId, UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Atomic administrator VFS write is not implemented");
    }

    default VfsNode createDirectoryByAdministrator(UUID administratorId, UUID ownerId,
                                                   UUID nodeId, UUID parentNodeId, String name,
                                                   UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Atomic administrator directory creation is not implemented");
    }

    default VfsNode createFileByAdministrator(UUID administratorId, UUID ownerId,
                                              UUID nodeId, UUID parentNodeId, String name,
                                              StoredObject object, boolean revisionEnabled,
                                              UUID revisionId, UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Atomic administrator file creation is not implemented");
    }

    default VfsNode renameByAdministrator(UUID administratorId, UUID ownerId, UUID nodeId,
                                          String replacementName, UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Atomic administrator VFS rename is not implemented");
    }

    default boolean deleteByAdministrator(UUID administratorId, UUID ownerId, UUID nodeId,
                                          UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Atomic administrator VFS delete is not implemented");
    }

    FileRevision appendRevision(
            UUID revisionId,
            UUID nodeId,
            UUID ownerId,
            ObjectHash objectHash,
            UUID createdBy,
            Instant createdAt
    );

    /** Appends a target owner's revision with the administrator retained as the actor. */
    default FileRevision appendRevisionByAdministrator(
            UUID revisionId,
            UUID nodeId,
            UUID ownerId,
            ObjectHash objectHash,
            UUID administratorId,
            Instant createdAt
    ) {
        throw new UnsupportedOperationException("Administrator revision append is not implemented");
    }

    Optional<FileRevision> findRevision(UUID nodeId, long revisionNumber);

    List<FileRevision> findRevisions(UUID nodeId);

    void insertMount(VfsMount mount);

    Optional<VfsMount> findMount(UUID mountId);

    List<VfsMount> findMounts(UUID ownerId);

    boolean disableMount(UUID mountId, UUID ownerId);

    default Optional<FileLock> acquireLock(UUID nodeId, UUID ownerId, UUID processUid,
                                           long executionEpoch, Instant leaseUntil, Instant at) {
        throw new UnsupportedOperationException("VFS locks are not implemented");
    }

    default Optional<FileLock> renewLock(UUID nodeId, UUID ownerId, UUID processUid,
                                         long executionEpoch, long fencingToken,
                                         Instant leaseUntil, Instant at) {
        throw new UnsupportedOperationException("VFS locks are not implemented");
    }

    default boolean releaseLock(UUID nodeId, UUID ownerId, UUID processUid,
                                long executionEpoch, long fencingToken) {
        throw new UnsupportedOperationException("VFS locks are not implemented");
    }

    record FileLock(long fencingToken, Instant leaseUntil) {}
}
