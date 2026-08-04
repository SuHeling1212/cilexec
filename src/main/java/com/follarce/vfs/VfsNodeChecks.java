package com.follarce.vfs;

import com.follarce.domain.port.VfsRepository;
import com.follarce.domain.vfs.VfsNode;

import java.util.Optional;
import java.util.UUID;

/** Shared, side-effect-free VFS node and namespace invariants. */
final class VfsNodeChecks {
    private VfsNodeChecks() {
    }

    static VfsNode requireNode(VfsRepository vfs, UUID nodeId) {
        return vfs.findNode(nodeId).orElseThrow(() ->
                new IllegalArgumentException("Unknown VFS node"));
    }

    static VfsNode requireOwned(VfsNode node, UUID ownerId, String message) {
        if (!node.ownerId().equals(ownerId)) throw new SecurityException(message);
        return node;
    }

    static VfsNode requireType(VfsNode node, VfsNode.Type type, String message) {
        if (node.type() != type) throw new IllegalArgumentException(message);
        return node;
    }

    static VfsNode requireDirectory(VfsRepository vfs, UUID nodeId, UUID ownerId,
                                    String ownerMessage) {
        return requireType(requireOwned(requireNode(vfs, nodeId), ownerId, ownerMessage),
                VfsNode.Type.DIRECTORY, "Parent node is not a directory");
    }

    static void requireUnused(VfsRepository vfs, UUID ownerId, Optional<UUID> parentNodeId,
                              String name) {
        if (vfs.findChild(ownerId, parentNodeId, name).isPresent()) {
            throw new IllegalArgumentException("A VFS node with that name already exists");
        }
    }

    /** Mirrors the SQL-side node-name rules (vfs.admin_rename_as) plus control-char rejection. */
    static String requireSafeNodeName(String name) {
        if (name == null || name.isBlank() || name.equals(".")
                || name.equals("..") || name.contains("/")
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid VFS node name: " + name);
        }
        return name;
    }

    static VfsNode requireContent(VfsRepository vfs, UUID nodeId, UUID ownerId,
                                  String ownerMessage) {
        VfsNode node = requireOwned(requireNode(vfs, nodeId), ownerId, ownerMessage);
        if (node.type() != VfsNode.Type.FILE && node.type() != VfsNode.Type.SYMLINK) {
            throw new IllegalArgumentException("VFS node does not contain an object");
        }
        return node;
    }
}
