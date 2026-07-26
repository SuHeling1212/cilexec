package com.follarce.vfs;

import com.follarce.auth.Authorization;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Audited cross-user VFS operations. This is the only application path that deliberately uses
 * the trusted runtime role after checking {@code SYSTEM_ADMIN}; ordinary user transactions remain
 * constrained by PostgreSQL RLS even when their account owns the administrator capability.
 */
public final class AdminVfsService {
    private final TransactionExecutor transactions;
    private final Clock clock;

    public AdminVfsService(TransactionExecutor transactions, Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public List<VfsNode> listNodes(UUID administratorId, UUID targetUserId) {
        return transactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            requireAccess(transaction, administratorId, targetUserId);
            List<VfsNode> nodes = transaction.vfs().findAllNodes(targetUserId);
            transaction.audit().append(audit(administratorId, targetUserId, "vfs.admin.list",
                    "auth.user_account", targetUserId.toString(), Map.of(
                            "nodeCount", Integer.toString(nodes.size())), clock.instant()));
            return List.copyOf(nodes);
        });
    }

    public List<VfsNode> listDirectory(UUID administratorId, UUID targetUserId,
                                       Optional<UUID> parentNodeId) {
        return transactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            requireAccess(transaction, administratorId, targetUserId);
            parentNodeId.ifPresent(parent -> requireNode(transaction, targetUserId, parent,
                    VfsNode.Type.DIRECTORY));
            List<VfsNode> nodes = transaction.vfs().findChildren(targetUserId, parentNodeId);
            transaction.audit().append(audit(administratorId, targetUserId, "vfs.admin.listdir",
                    "vfs.node", parentNodeId.map(UUID::toString).orElse("/"), Map.of(
                            "nodeCount", Integer.toString(nodes.size())), clock.instant()));
            return List.copyOf(nodes);
        });
    }

    public StoredObject readFile(UUID administratorId, UUID targetUserId, UUID nodeId) {
        return transactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            requireAccess(transaction, administratorId, targetUserId);
            VfsNode node = requireContentNode(transaction, targetUserId, nodeId);
            StoredObject object = transaction.vfs()
                    .findObjectByAdministrator(node.currentObjectHash().orElseThrow())
                    .orElseThrow(() -> new IllegalStateException(
                            "VFS node references a missing object"));
            transaction.audit().append(audit(administratorId, targetUserId, "vfs.admin.read",
                    "vfs.node", nodeId.toString(), Map.of(
                            "name", node.name(), "bytes", Long.toString(object.byteSize())),
                    clock.instant()));
            return object;
        });
    }

    public VfsNode replaceContent(UUID administratorId, UUID targetUserId, UUID nodeId,
                                  byte[] replacement, String mediaType) {
        Instant now = clock.instant();
        StoredObject object = StoredObject.create(new BinaryContent(replacement), mediaType, now);
        return transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            requireAccess(transaction, administratorId, targetUserId);
            VfsNode current = requireContentNode(transaction, targetUserId, nodeId);
            transaction.vfs().saveObjectByAdministrator(object, administratorId);
            if (!transaction.vfs().replaceContent(nodeId, current.currentObjectHash(),
                    object.objectHash(), now)) {
                throw new IllegalStateException("Concurrent administrator VFS update rejected");
            }
            if (current.revisionEnabled()) {
                transaction.vfs().appendRevisionByAdministrator(UUID.randomUUID(), nodeId,
                        targetUserId, object.objectHash(), administratorId, now);
            }
            VfsNode changed = current.replaceContent(object.objectHash(), now);
            transaction.audit().append(audit(administratorId, targetUserId, "vfs.admin.write",
                    "vfs.node", nodeId.toString(), Map.of(
                            "name", current.name(), "bytes", Long.toString(object.byteSize())), now));
            return changed;
        });
    }

    public VfsNode createDirectory(UUID administratorId, UUID targetUserId,
                                   Optional<UUID> parentNodeId, String name,
                                   Set<String> capabilities) {
        Instant now = clock.instant();
        return transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            requireAccess(transaction, administratorId, targetUserId);
            parentNodeId.ifPresent(parent -> requireNode(transaction, targetUserId, parent,
                    VfsNode.Type.DIRECTORY));
            requireUnused(transaction, targetUserId, parentNodeId, name);
            VfsNode node = new VfsNode(UUID.randomUUID(), parentNodeId, targetUserId, name,
                    VfsNode.Type.DIRECTORY, Optional.empty(), capabilities, false, now, now);
            transaction.vfs().insertNode(node);
            transaction.audit().append(audit(administratorId, targetUserId,
                    "vfs.admin.directory.create", "vfs.node", node.nodeId().toString(),
                    Map.of("name", name), now));
            return node;
        });
    }

    public VfsNode createFile(UUID administratorId, UUID targetUserId, UUID parentNodeId,
                              String name, byte[] content, String mediaType,
                              Set<String> capabilities, boolean revisionEnabled) {
        Instant now = clock.instant();
        StoredObject object = StoredObject.create(new BinaryContent(content), mediaType, now);
        return transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            requireAccess(transaction, administratorId, targetUserId);
            requireNode(transaction, targetUserId, parentNodeId, VfsNode.Type.DIRECTORY);
            requireUnused(transaction, targetUserId, Optional.of(parentNodeId), name);
            transaction.vfs().saveObjectByAdministrator(object, administratorId);
            VfsNode node = new VfsNode(UUID.randomUUID(), Optional.of(parentNodeId), targetUserId,
                    name, VfsNode.Type.FILE, Optional.of(object.objectHash()), capabilities,
                    revisionEnabled, now, now);
            transaction.vfs().insertNode(node);
            if (revisionEnabled) {
                transaction.vfs().appendRevisionByAdministrator(UUID.randomUUID(), node.nodeId(),
                        targetUserId, object.objectHash(), administratorId, now);
            }
            transaction.audit().append(audit(administratorId, targetUserId,
                    "vfs.admin.file.create", "vfs.node", node.nodeId().toString(),
                    Map.of("name", name, "bytes", Long.toString(object.byteSize())), now));
            return node;
        });
    }

    public VfsNode rename(UUID administratorId, UUID targetUserId, UUID nodeId,
                          String replacementName) {
        Instant now = clock.instant();
        return transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            requireAccess(transaction, administratorId, targetUserId);
            VfsNode node = transaction.vfs().findNode(nodeId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown VFS node"));
            if (!node.ownerId().equals(targetUserId)) {
                throw new SecurityException("VFS node belongs to a different target user");
            }
            if (node.parentNodeId().isEmpty()) {
                throw new IllegalArgumentException("The target user's root cannot be renamed");
            }
            if (!transaction.vfs().renameNode(nodeId, targetUserId, replacementName, now)) {
                throw new IllegalStateException("Concurrent administrator VFS rename rejected");
            }
            VfsNode changed = new VfsNode(node.nodeId(), node.parentNodeId(), node.ownerId(),
                    replacementName, node.type(), node.currentObjectHash(), node.capabilities(),
                    node.revisionEnabled(), node.createdAt(), now);
            transaction.audit().append(audit(administratorId, targetUserId, "vfs.admin.rename",
                    "vfs.node", nodeId.toString(), Map.of("name", replacementName), now));
            return changed;
        });
    }

    public boolean delete(UUID administratorId, UUID targetUserId, UUID nodeId) {
        Instant now = clock.instant();
        return transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            requireAccess(transaction, administratorId, targetUserId);
            VfsNode node = transaction.vfs().findNode(nodeId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown VFS node"));
            if (!node.ownerId().equals(targetUserId)) {
                throw new SecurityException("VFS node belongs to a different target user");
            }
            if (!transaction.vfs().deleteNode(nodeId, targetUserId)) {
                throw new IllegalStateException(
                        "Target is a root, non-empty directory, mount, or versioned file");
            }
            transaction.audit().append(audit(administratorId, targetUserId, "vfs.admin.delete",
                    "vfs.node", nodeId.toString(), Map.of("name", node.name()), now));
            return true;
        });
    }

    private static void requireAccess(TransactionContext transaction, UUID administratorId,
                                      UUID targetUserId) {
        UserAccount administrator = transaction.auth().findUser(administratorId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown administrator"));
        if (administrator.status() != UserAccount.Status.ACTIVE) {
            throw new SecurityException("Administrator account is not active");
        }
        transaction.auth().findUser(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown target user"));
        if (!transaction.auth().hasCapabilityByAdministrator(
                administratorId, com.follarce.domain.auth.Capability.SYSTEM_ADMIN)) {
            throw new SecurityException("Missing CilExec capability: SYSTEM_ADMIN");
        }
    }

    private static VfsNode requireContentNode(TransactionContext transaction, UUID ownerId,
                                              UUID nodeId) {
        VfsNode node = transaction.vfs().findNode(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown VFS node"));
        if (!node.ownerId().equals(ownerId)) {
            throw new SecurityException("VFS node belongs to a different target user");
        }
        if (node.type() != VfsNode.Type.FILE && node.type() != VfsNode.Type.SYMLINK) {
            throw new IllegalArgumentException("VFS node does not contain an object");
        }
        return node;
    }

    private static VfsNode requireNode(TransactionContext transaction, UUID ownerId, UUID nodeId,
                                       VfsNode.Type type) {
        VfsNode node = transaction.vfs().findNode(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown VFS node"));
        if (!node.ownerId().equals(ownerId)) {
            throw new SecurityException("VFS node belongs to a different target user");
        }
        if (node.type() != type) throw new IllegalArgumentException("Unexpected VFS node type");
        return node;
    }

    private static void requireUnused(TransactionContext transaction, UUID ownerId,
                                      Optional<UUID> parentNodeId, String name) {
        if (transaction.vfs().findChild(ownerId, parentNodeId, name).isPresent()) {
            throw new IllegalArgumentException("A VFS node with that name already exists");
        }
    }

    private static AuditEvent audit(UUID administratorId, UUID targetUserId, String action,
                                    String resourceType, String resourceId,
                                    Map<String, String> details, Instant at) {
        java.util.LinkedHashMap<String, String> allDetails = new java.util.LinkedHashMap<>(details);
        allDetails.put("targetUserId", targetUserId.toString());
        return new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.ADMINISTRATOR,
                administratorId.toString(), action, resourceType, resourceId,
                AuditEvent.Result.SUCCEEDED, Map.copyOf(allDetails), at);
    }
}
