package com.follarce.vfs;

import com.follarce.auth.Authorization;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.domain.vfs.VfsFileLimits;

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
    private static final String CHUNK_MANIFEST_MEDIA_TYPE =
            "application/vnd.cilexec.chunk-manifest;version=1";
    private static final String CHUNK_MANIFEST_HEADER = "CILEXEC-CHUNK-MANIFEST-V1";

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
            parentNodeId.ifPresent(parent -> VfsNodeChecks.requireDirectory(transaction.vfs(),
                    parent, targetUserId, "VFS node belongs to a different target user"));
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
            VfsNode node = VfsNodeChecks.requireContent(transaction.vfs(), nodeId, targetUserId,
                    "VFS node belongs to a different target user");
            ObjectHash hash = node.currentObjectHash().orElseThrow();
            StoredObject stored = transaction.vfs()
                    .findObjectByAdministrator(hash)
                    .orElseThrow(() -> new IllegalStateException(
                            "VFS node references a missing object"));
            // The stored object may be a small chunk-manifest whose content is the
            // descriptor, not the logical file bytes. This path runs on the trusted
            // runtime role (no per-user claim is available), so the manifest chain is
            // reconstructed from the immutable descriptors instead of the user-scoped
            // range-read functions.
            StoredObject logical = CHUNK_MANIFEST_MEDIA_TYPE.equals(stored.mediaType())
                    ? materializeChunkedObject(transaction, stored)
                    : stored;
            transaction.audit().append(audit(administratorId, targetUserId, "vfs.admin.read",
                    "vfs.node", nodeId.toString(), Map.of(
                            "name", node.name(), "bytes", Long.toString(logical.byteSize())),
                    clock.instant()));
            return logical;
        });
    }

    /**
     * Reassembles the logical bytes of a chunked object. Each manifest descriptor is
     * {@code CILEXEC-CHUNK-MANIFEST-V1\n<base-or-previous>\n<tail>\n<total>\n}; walking
     * the {@code base-or-previous} link down to a plain object and appending every tail
     * in append order yields the logical content.
     */
    private static StoredObject materializeChunkedObject(TransactionContext transaction,
                                                         StoredObject manifest) {
        java.util.ArrayDeque<StoredObject> chain = new java.util.ArrayDeque<>();
        StoredObject current = manifest;
        long logicalBytes = 0;
        while (CHUNK_MANIFEST_MEDIA_TYPE.equals(current.mediaType())) {
            String[] lines = descriptor(current);
            chain.push(current);
            logicalBytes = Long.parseLong(lines[3]);
            current = transaction.vfs().findObjectByAdministrator(new ObjectHash(lines[1]))
                    .orElseThrow(() -> new IllegalStateException(
                            "Chunk manifest references a missing base object"));
        }
        VfsFileLimits.requireWithinLimit(logicalBytes);
        java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream(
                Math.toIntExact(logicalBytes));
        content.writeBytes(current.content().bytes());
        while (!chain.isEmpty()) {
            StoredObject step = chain.pop();
            String[] lines = descriptor(step);
            StoredObject tail = transaction.vfs().findObjectByAdministrator(
                    new ObjectHash(lines[2])).orElseThrow(() -> new IllegalStateException(
                    "Chunk manifest references a missing tail object"));
            content.writeBytes(tail.content().bytes());
        }
        byte[] logical = content.toByteArray();
        if (logical.length != logicalBytes) {
            throw new IllegalStateException("Chunk manifest size does not match its content");
        }
        return StoredObject.create(new BinaryContent(logical), manifest.mediaType(),
                manifest.createdAt());
    }

    private static String[] descriptor(StoredObject manifest) {
        String[] lines = new String(manifest.content().bytes(), java.nio.charset.StandardCharsets.US_ASCII)
                .split("\n", -1);
        if (lines.length < 4 || !CHUNK_MANIFEST_HEADER.equals(lines[0])
                || !lines[1].matches("[0-9a-f]{64}") || !lines[2].matches("[0-9a-f]{64}")
                || !lines[3].matches("[0-9]+")) {
            throw new IllegalStateException("Corrupt chunk manifest in VFS object");
        }
        return lines;
    }

    public VfsNode replaceContent(UUID administratorId, UUID targetUserId, UUID nodeId,
                                  byte[] replacement, String mediaType) {
        VfsFileLimits.requireWithinLimit(replacement.length);
        Instant now = clock.instant();
        StoredObject object = StoredObject.create(new BinaryContent(replacement), mediaType, now);
        return transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            requireAccess(transaction, administratorId, targetUserId);
            VfsNode current = VfsNodeChecks.requireContent(transaction.vfs(), nodeId,
                    targetUserId, "VFS node belongs to a different target user");
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
            parentNodeId.ifPresent(parent -> VfsNodeChecks.requireDirectory(transaction.vfs(),
                    parent, targetUserId, "VFS node belongs to a different target user"));
            VfsNodeChecks.requireUnused(transaction.vfs(), targetUserId, parentNodeId, name);
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
        VfsFileLimits.requireWithinLimit(content.length);
        Instant now = clock.instant();
        StoredObject object = StoredObject.create(new BinaryContent(content), mediaType, now);
        return transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            requireAccess(transaction, administratorId, targetUserId);
            VfsNodeChecks.requireDirectory(transaction.vfs(), parentNodeId, targetUserId,
                    "VFS node belongs to a different target user");
            VfsNodeChecks.requireUnused(transaction.vfs(), targetUserId,
                    Optional.of(parentNodeId), name);
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
            VfsNode node = VfsNodeChecks.requireOwned(
                    VfsNodeChecks.requireNode(transaction.vfs(), nodeId), targetUserId,
                    "VFS node belongs to a different target user");
            if (node.parentNodeId().isEmpty()) {
                throw new IllegalArgumentException("The target user's root cannot be renamed");
            }
            VfsNodeChecks.requireSafeNodeName(replacementName);
            VfsNode sibling = transaction.vfs().findChild(targetUserId, node.parentNodeId(),
                    replacementName).orElse(null);
            if (sibling != null && !sibling.nodeId().equals(nodeId)) {
                throw new IllegalArgumentException(
                        "A VFS node with that name already exists");
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
            VfsNode node = VfsNodeChecks.requireOwned(
                    VfsNodeChecks.requireNode(transaction.vfs(), nodeId), targetUserId,
                    "VFS node belongs to a different target user");
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
