package com.follarce.domain.vfs;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable namespace node pointing at immutable content when applicable. */
public record VfsNode(
        UUID nodeId,
        Optional<UUID> parentNodeId,
        UUID ownerId,
        String name,
        Type type,
        Optional<ObjectHash> currentObjectHash,
        Set<String> capabilities,
        boolean revisionEnabled,
        Instant createdAt,
        Instant updatedAt
) {
    public VfsNode {
        Invariant.required(nodeId, "nodeId");
        parentNodeId = Invariant.required(parentNodeId, "parentNodeId");
        Invariant.check(parentNodeId.isEmpty() || !parentNodeId.get().equals(nodeId),
                "node cannot be its own parent");
        Invariant.required(ownerId, "ownerId");
        name = Invariant.text(name, "name");
        if (parentNodeId.isEmpty()) {
            Invariant.check(name.equals("/"), "root node name must be /");
        } else {
            Invariant.check(name.codePointCount(0, name.length()) <= 255,
                    "child node name is too long");
            Invariant.check(!name.contains("/") && !name.equals(".") && !name.equals(".."),
                    "child node name must be a single safe path component");
            Invariant.check(name.codePoints().noneMatch(codePoint ->
                            Character.isISOControl(codePoint)
                                    || Character.getType(codePoint) == Character.FORMAT),
                    "child node name contains terminal control characters");
        }
        Invariant.required(type, "type");
        currentObjectHash = Invariant.required(currentObjectHash, "currentObjectHash");
        capabilities = Set.copyOf(Invariant.required(capabilities, "capabilities"));
        boolean contentNode = type == Type.FILE || type == Type.SYMLINK;
        Invariant.check(contentNode == currentObjectHash.isPresent(),
                "only file and symlink nodes must reference content");
        Invariant.check(!revisionEnabled || type == Type.FILE,
                "only file nodes can retain revisions");
        Invariant.required(createdAt, "createdAt");
        Invariant.required(updatedAt, "updatedAt");
        Invariant.check(!updatedAt.isBefore(createdAt), "updatedAt must not precede createdAt");
    }

    public VfsNode replaceContent(ObjectHash replacement, Instant at) {
        if (type != Type.FILE && type != Type.SYMLINK) {
            throw new IllegalStateException("node type cannot reference content");
        }
        Invariant.check(!at.isBefore(updatedAt), "node update time must not move backwards");
        return new VfsNode(nodeId, parentNodeId, ownerId, name, type,
                Optional.of(Invariant.required(replacement, "replacement")), capabilities,
                revisionEnabled, createdAt, at);
    }

    public enum Type {
        DIRECTORY,
        FILE,
        SYMLINK,
        MOUNT
    }
}
