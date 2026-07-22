package com.follarce.vfs;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.auth.Authorization;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.FileRevision;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsMount;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Atomic VFS use cases backed by immutable content-addressed objects. */
public final class VfsService {
    private final UserTransactionExecutor transactions;
    private final Clock clock;
    private final Set<String> hostSourceKeys;

    public VfsService(UserTransactionExecutor transactions, Clock clock) {
        this(transactions, clock, Set.of());
    }

    public VfsService(UserTransactionExecutor transactions, Clock clock,
                      Set<String> hostSourceKeys) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        java.util.Objects.requireNonNull(hostSourceKeys, "hostSourceKeys");
        hostSourceKeys.forEach(VfsMount::validateHostSourceKey);
        this.hostSourceKeys = Set.copyOf(hostSourceKeys);
    }

    public VfsNode createDirectory(
            UUID ownerId,
            Optional<UUID> parentNodeId,
            String name,
            Set<String> capabilities
    ) {
        Instant now = clock.instant();
        VfsNode node = new VfsNode(UUID.randomUUID(), parentNodeId, ownerId, name,
                VfsNode.Type.DIRECTORY, Optional.empty(), capabilities, false, now, now);
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.VFS_WRITE);
            parentNodeId.ifPresent(parent -> requireDirectory(
                    transaction.vfs().findNode(parent), ownerId));
            requireUnusedName(transaction.vfs().findChild(ownerId, parentNodeId, name));
            transaction.vfs().insertNode(node);
            transaction.audit().append(audit(ownerId, "vfs.directory.create", node, now));
            return node;
        });
    }

    public VfsNode createFile(
            UUID ownerId,
            UUID parentNodeId,
            String name,
            byte[] content,
            String mediaType,
            Set<String> capabilities,
            boolean revisionEnabled
    ) {
        Instant now = clock.instant();
        StoredObject object = StoredObject.create(new BinaryContent(content), mediaType, now);
        VfsNode node = new VfsNode(UUID.randomUUID(), Optional.of(parentNodeId), ownerId, name,
                VfsNode.Type.FILE, Optional.of(object.objectHash()), capabilities,
                revisionEnabled, now, now);
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.VFS_WRITE);
            VfsNode parent = transaction.vfs().findNode(parentNodeId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown parent node"));
            if (parent.type() != VfsNode.Type.DIRECTORY) {
                throw new IllegalArgumentException("Parent node is not a directory");
            }
            if (!parent.ownerId().equals(ownerId)) {
                throw new SecurityException("VFS parent belongs to a different owner");
            }
            requireUnusedName(transaction.vfs().findChild(ownerId,
                    Optional.of(parentNodeId), name));
            transaction.vfs().saveObject(object);
            transaction.vfs().insertNode(node);
            if (node.revisionEnabled()) {
                transaction.vfs().appendRevision(UUID.randomUUID(), node.nodeId(), ownerId,
                        object.objectHash(), ownerId, now);
            }
            transaction.audit().append(audit(ownerId, "vfs.file.create", node, now));
            return node;
        });
    }

    public StoredObject readFile(UUID ownerId, UUID nodeId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.VFS_READ);
            VfsNode node = transaction.vfs().findNode(nodeId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown VFS node"));
            requireOwner(node, ownerId);
            if (node.type() != VfsNode.Type.FILE && node.type() != VfsNode.Type.SYMLINK) {
                throw new IllegalArgumentException("VFS node does not contain an object");
            }
            return transaction.vfs().findObject(node.currentObjectHash().orElseThrow())
                    .orElseThrow(() -> new IllegalStateException("VFS node references a missing object"));
        });
    }

    public VfsNode replaceContent(
            UUID ownerId,
            UUID nodeId,
            ObjectHash expectedHash,
            byte[] replacement,
            String mediaType
    ) {
        Instant now = clock.instant();
        StoredObject object = StoredObject.create(new BinaryContent(replacement), mediaType, now);
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.VFS_WRITE);
            VfsNode current = transaction.vfs().findNode(nodeId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown VFS node"));
            if (!current.ownerId().equals(ownerId)) {
                throw new SecurityException("Only the owner may replace VFS content");
            }
            if (!current.currentObjectHash().equals(Optional.of(expectedHash))) {
                throw new IllegalStateException("VFS object version conflict");
            }
            transaction.vfs().saveObject(object);
            if (!transaction.vfs().replaceContent(nodeId, Optional.of(expectedHash),
                    object.objectHash(), now)) {
                throw new IllegalStateException("Concurrent VFS content update rejected");
            }
            if (current.revisionEnabled()) {
                transaction.vfs().appendRevision(UUID.randomUUID(), current.nodeId(), ownerId,
                        object.objectHash(), ownerId, now);
            }
            VfsNode changed = current.replaceContent(object.objectHash(), now);
            transaction.audit().append(audit(ownerId, "vfs.content.replace", changed, now));
            return changed;
        });
    }

    public List<FileRevision> fileRevisions(UUID ownerId, UUID nodeId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.VFS_READ);
            VfsNode node = transaction.vfs().findNode(nodeId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown VFS node"));
            requireOwner(node, ownerId);
            if (node.type() != VfsNode.Type.FILE || !node.revisionEnabled()) {
                throw new IllegalArgumentException("VFS node does not retain file revisions");
            }
            List<FileRevision> revisions = transaction.vfs().findRevisions(nodeId);
            revisions.forEach(revision -> {
                if (!revision.nodeId().equals(nodeId) || !revision.ownerId().equals(ownerId)) {
                    throw new IllegalStateException(
                            "File revision history crossed its node or owner boundary");
                }
            });
            return List.copyOf(revisions);
        });
    }

    public StoredObject readRevision(UUID ownerId, UUID nodeId, long revisionNumber) {
        if (revisionNumber < 1) {
            throw new IllegalArgumentException("revisionNumber must be positive");
        }
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.VFS_READ);
            VfsNode node = transaction.vfs().findNode(nodeId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown VFS node"));
            requireOwner(node, ownerId);
            if (node.type() != VfsNode.Type.FILE || !node.revisionEnabled()) {
                throw new IllegalArgumentException("VFS node does not retain file revisions");
            }
            FileRevision revision = transaction.vfs().findRevision(nodeId, revisionNumber)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown file revision"));
            if (!revision.ownerId().equals(ownerId)) {
                throw new SecurityException("File revision belongs to a different owner");
            }
            return transaction.vfs().findObject(revision.objectHash())
                    .orElseThrow(() -> new IllegalStateException(
                            "File revision references a missing object"));
        });
    }

    public VfsMount declareMount(
            UUID ownerId,
            UUID parentNodeId,
            String name,
            String hostSourceKey,
            String containerPath,
            Set<String> nodeCapabilities
    ) {
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.SERIALIZABLE, transaction -> {
            Authorization.require(transaction, ownerId, Capability.VFS_MOUNT_HOST);
            Authorization.require(transaction, ownerId, Capability.VFS_WRITE);
            String sourceKey = VfsMount.validateHostSourceKey(hostSourceKey);
            if (!hostSourceKeys.contains(sourceKey)) {
                throw new SecurityException("Host source key is not configured: " + sourceKey);
            }
            VfsNode parent = requireDirectory(transaction.vfs().findNode(parentNodeId), ownerId);
            requireUnusedName(transaction.vfs().findChild(ownerId,
                    Optional.of(parent.nodeId()), name));
            VfsNode node = new VfsNode(UUID.randomUUID(), Optional.of(parent.nodeId()), ownerId,
                    name, VfsNode.Type.MOUNT, Optional.empty(), nodeCapabilities,
                    false, now, now);
            VfsMount mount = VfsMount.declareReadOnly(UUID.randomUUID(), node.nodeId(), ownerId,
                    sourceKey, containerPath, now);
            transaction.vfs().insertNode(node);
            transaction.vfs().insertMount(mount);
            transaction.audit().append(mountAudit(ownerId, "vfs.mount.declare", mount, now));
            return mount;
        });
    }

    public VfsMount disableMount(UUID ownerId, UUID mountId) {
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.SERIALIZABLE, transaction -> {
            Authorization.require(transaction, ownerId, Capability.VFS_MOUNT_HOST);
            VfsMount current = transaction.vfs().findMount(mountId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown VFS mount"));
            requireMountOwner(current, ownerId);
            if (current.status() == VfsMount.Status.DISABLED) return current;
            if (!transaction.vfs().disableMount(mountId, ownerId)) {
                throw new IllegalStateException("Concurrent VFS mount update rejected");
            }
            VfsMount disabled = current.disable();
            transaction.audit().append(mountAudit(ownerId, "vfs.mount.disable", disabled, now));
            return disabled;
        });
    }

    public Optional<VfsMount> findMount(UUID ownerId, UUID mountId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.VFS_MOUNT_HOST);
            Optional<VfsMount> found = transaction.vfs().findMount(mountId);
            found.ifPresent(mount -> requireMountOwner(mount, ownerId));
            return found;
        });
    }

    public List<VfsMount> mounts(UUID ownerId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.VFS_MOUNT_HOST);
            List<VfsMount> mounts = transaction.vfs().findMounts(ownerId);
            mounts.forEach(mount -> requireMountOwner(mount, ownerId));
            return List.copyOf(mounts);
        });
    }

    private static void requireUnusedName(Optional<VfsNode> existing) {
        if (existing.isPresent()) {
            throw new IllegalArgumentException("A VFS node with that name already exists");
        }
    }

    private static VfsNode requireDirectory(Optional<VfsNode> found, UUID ownerId) {
        VfsNode parent = found.orElseThrow(() ->
                new IllegalArgumentException("Unknown parent node"));
        requireOwner(parent, ownerId);
        if (parent.type() != VfsNode.Type.DIRECTORY) {
            throw new IllegalArgumentException("Parent node is not a directory");
        }
        return parent;
    }

    private static void requireOwner(VfsNode node, UUID ownerId) {
        if (!node.ownerId().equals(ownerId)) {
            throw new SecurityException("VFS node belongs to a different owner");
        }
    }

    private static void requireMountOwner(VfsMount mount, UUID ownerId) {
        if (!mount.ownerId().equals(ownerId)) {
            throw new SecurityException("VFS mount belongs to a different owner");
        }
    }

    private static AuditEvent audit(UUID ownerId, String action, VfsNode node, Instant at) {
        return new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER, ownerId.toString(),
                action, "vfs.node", node.nodeId().toString(), AuditEvent.Result.SUCCEEDED,
                Map.of("name", node.name(), "type", node.type().name()), at);
    }

    private static AuditEvent mountAudit(UUID ownerId, String action, VfsMount mount,
                                         Instant at) {
        return new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER, ownerId.toString(),
                action, "vfs.mount", mount.mountId().toString(), AuditEvent.Result.SUCCEEDED,
                Map.of("hostSourceKey", mount.hostSourceKey(),
                        "containerPath", mount.containerPath(),
                        "readOnly", Boolean.toString(mount.readOnly()),
                        "status", mount.status().name()), at);
    }
}
