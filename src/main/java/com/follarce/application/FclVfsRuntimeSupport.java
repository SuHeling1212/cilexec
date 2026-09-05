package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.port.DurableStorageFailure;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.domain.vfs.VfsFileLimits;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclPath;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclScope;
import com.follarce.extension.JavaExtensionCatalog;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Consumer;

/** Implements VFS path routing, authorization, and content operations for FCL registrars. */
abstract class FclVfsRuntimeSupport extends FclRuntimeFunctionSupport {
    FclVfsRuntimeSupport(FclVfsRuntimeSupport source) {
        super(source);
    }

    FclVfsRuntimeSupport(TransactionContext transaction, CilProcess process, Program program,
                         FclContinuation continuation, Instant now,
                         JavaExtensionCatalog extensions, FclFunctionRegistry registry,
                         Consumer<VolatileProcessRequest> volatileProcessRequests,
                         Consumer<ProcessOutput> processOutputs) {
        super(transaction, process, program, continuation, now, extensions, registry,
                volatileProcessRequests, processOutputs);
    }

    protected String readText(String path) {
        return readText(path, process.ownerId());
    }

    protected String readText(String path, UUID owner) {
        return decodeUtf8(readBytes(path, owner), "file.read");
    }

    protected VfsNode resolveFileNode(String path, UUID owner) {
        requireFileAccess(owner, Capability.VFS_READ);
        boolean administrative = !owner.equals(process.ownerId());
        Set<String> visited = new java.util.HashSet<>();
        VfsNode node = requireNode(path, owner);
        while (node.type() == VfsNode.Type.SYMLINK) {
            if (!visited.add(normalize(path))) {
                throw new FclRuntimeException("Symbolic link cycle at: " + normalize(path));
            }
            if (visited.size() >= MAX_SYMLINK_DEPTH) {
                throw new FclRuntimeException(
                        "Symbolic link chain exceeds " + MAX_SYMLINK_DEPTH + " links");
            }
            ObjectHash hash = node.currentObjectHash().orElseThrow();
            long size = administrative
                    ? transaction.vfs().logicalObjectSizeByAdministrator(
                    process.ownerId(), owner, hash)
                    : transaction.vfs().logicalObjectSize(hash);
            if (size > MAX_LINK_TARGET_BYTES) {
                throw new FclRuntimeException("Symbolic link target is too long");
            }
            java.io.ByteArrayOutputStream target = new java.io.ByteArrayOutputStream((int) size);
            long offset = 0;
            while (offset < size) {
                int request = (int) Math.min(DOWNLOAD_CHUNK_BYTES, size - offset);
                byte[] chunk = administrative
                        ? transaction.vfs().readObjectRangeByAdministrator(
                        process.ownerId(), owner, hash, offset, request)
                        : transaction.vfs().readObjectRange(hash, offset, request);
                if (chunk.length == 0) {
                    throw new FclRuntimeException("Symbolic link ended before its target");
                }
                target.writeBytes(chunk);
                offset += chunk.length;
            }
            path = normalize(decodeUtf8(target.toByteArray(), "file.link"));
            node = requireNode(path, owner);
            requireFileAccess(owner, Capability.VFS_READ);
        }
        if (node.type() != VfsNode.Type.FILE) {
            throw new FclRuntimeException("Path is not a file: " + path);
        }
        return node;
    }

    protected byte[] readBytes(String path) {
        return readBytes(path, process.ownerId());
    }

    protected byte[] readBytes(String path, UUID owner) {
        RoutedPath routed = route(path, owner);
        path = routed.path();
        owner = routed.ownerId();
        boolean administrative = !owner.equals(process.ownerId());
        VfsNode node = resolveFileNode(path, owner);
        ObjectHash hash = node.currentObjectHash().orElseThrow();
        long size = administrative
                ? transaction.vfs().logicalObjectSizeByAdministrator(
                process.ownerId(), owner, hash)
                : transaction.vfs().logicalObjectSize(hash);
        if (size > MAX_IN_MEMORY_READ_BYTES) throw new FclRuntimeException(
                "File exceeds the 16 MiB in-memory read limit; use file.readChunk");
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream((int) size);
        long offset = 0;
        while (offset < size) {
            int request = (int) Math.min(4L * 1024 * 1024, size - offset);
            byte[] chunk = administrative
                    ? transaction.vfs().readObjectRangeByAdministrator(
                    process.ownerId(), owner, hash, offset, request)
                    : transaction.vfs().readObjectRange(hash, offset, request);
            if (chunk.length == 0) throw new FclRuntimeException(
                    "File content ended before its declared size: " + path);
            result.writeBytes(chunk);
            offset += chunk.length;
        }
        if (administrative) {
            audit("vfs.admin.read", node.nodeId(), Map.of("path", normalize(path)));
        }
        return result.toByteArray();
    }

    protected byte[] readLogicalObject(ObjectHash hash, long maximumBytes, String field) {
        long size = transaction.vfs().logicalObjectSize(hash);
        if (size > maximumBytes || size > Integer.MAX_VALUE) {
            throw new FclRuntimeException(field + " exceeds the 64 MiB package limit");
        }
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream((int) size);
        long offset = 0;
        while (offset < size) {
            byte[] chunk = transaction.vfs().readObjectRange(hash, offset,
                    (int) Math.min(DOWNLOAD_CHUNK_BYTES, size - offset));
            if (chunk.length == 0) {
                throw new FclRuntimeException(field + " ended before its declared size");
            }
            result.writeBytes(chunk);
            offset += chunk.length;
        }
        return result.toByteArray();
    }

    protected boolean downloadedFileMatches(String path, String expectedSha256,
                                          long expectedBytes) {
        Optional<VfsNode> resolved = resolve(normalize(path));
        if (resolved.isEmpty() || resolved.orElseThrow().type() != VfsNode.Type.FILE) return false;
        Authorization.require(transaction, process.ownerId(), Capability.VFS_READ);
        ObjectHash hash = resolved.orElseThrow().currentObjectHash().orElse(null);
        if (hash == null || transaction.vfs().logicalObjectSize(hash) != expectedBytes) return false;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
        long offset = 0;
        while (offset < expectedBytes) {
            byte[] chunk = transaction.vfs().readObjectRange(hash, offset,
                    (int) Math.min(DOWNLOAD_CHUNK_BYTES, expectedBytes - offset));
            if (chunk.length == 0) return false;
            digest.update(chunk);
            offset += chunk.length;
        }
        return java.util.HexFormat.of().formatHex(digest.digest()).equals(expectedSha256);
    }

    protected byte[] readRange(String path, long offset, int maximum, UUID owner) {
        RoutedPath routed = route(path, owner);
        path = routed.path();
        owner = routed.ownerId();
        boolean administrative = !owner.equals(process.ownerId());
        VfsNode node = resolveFileNode(path, owner);
        byte[] chunk = administrative
                ? transaction.vfs().readObjectRangeByAdministrator(process.ownerId(), owner,
                node.currentObjectHash().orElseThrow(), offset, maximum)
                : transaction.vfs().readObjectRange(node.currentObjectHash().orElseThrow(),
                offset, maximum);
        if (administrative) {
            audit("vfs.admin.read", node.nodeId(), Map.of("path", normalize(path)));
        }
        return chunk;
    }

    protected byte[] readObjectByAdministrator(ObjectHash hash, UUID owner, String limitMessage) {
        long size = transaction.vfs().logicalObjectSizeByAdministrator(
                process.ownerId(), owner, hash);
        if (size > MAX_IN_MEMORY_READ_BYTES) throw new FclRuntimeException(limitMessage);
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream((int) size);
        long offset = 0;
        while (offset < size) {
            int request = (int) Math.min(4L * 1024 * 1024, size - offset);
            byte[] chunk = transaction.vfs().readObjectRangeByAdministrator(
                    process.ownerId(), owner, hash, offset, request);
            if (chunk.length == 0) throw new FclRuntimeException(
                    "File content ended before its declared size");
            result.writeBytes(chunk);
            offset += chunk.length;
        }
        return result.toByteArray();
    }

    protected String writeText(String source, String content, boolean append) {
        return writeText(source, content, append, process.ownerId());
    }

    protected String writeText(String source, String content, boolean append, UUID owner) {
        RoutedPath routed = route(source, owner);
        source = routed.path();
        owner = routed.ownerId();
        requireFileAccess(owner, Capability.VFS_WRITE);
        String path = normalize(source);
        Optional<VfsNode> existing = resolve(path, owner);
        if (existing.isEmpty()) {
            return createContentNode(path, content.getBytes(StandardCharsets.UTF_8),
                    VfsNode.Type.FILE, false, TEXT, owner).nodeId().toString();
        }
        VfsNode current = existing.orElseThrow();
        requireType(current, VfsNode.Type.FILE, "file.write");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        VfsFileLimits.requireWithinLimit(bytes.length);
        StoredObject object;
        if (!append) {
            object = StoredObject.create(new BinaryContent(bytes), TEXT, now);
        } else if (!owner.equals(process.ownerId())) {
            byte[] existingBytes = readObjectByAdministrator(
                    current.currentObjectHash().orElseThrow(), owner,
                    "Cross-user append is limited to files up to 16 MiB");
            VfsFileLimits.checkedAppendSize(existingBytes.length, bytes.length);
            byte[] combined = java.util.Arrays.copyOf(existingBytes,
                    Math.addExact(existingBytes.length, bytes.length));
            System.arraycopy(bytes, 0, combined, existingBytes.length, bytes.length);
            object = StoredObject.create(new BinaryContent(combined), TEXT, now);
        } else {
            VfsFileLimits.checkedAppendSize(transaction.vfs().logicalObjectSize(
                    current.currentObjectHash().orElseThrow()), bytes.length);
            object = transaction.vfs().appendChunkedObject(
                    current.currentObjectHash().orElseThrow(), bytes, TEXT, now);
        }
        if (owner.equals(process.ownerId())) {
            transaction.vfs().saveObject(object);
            if (!transaction.vfs().replaceContent(current.nodeId(), current.currentObjectHash(),
                    object.objectHash(), now)) {
                throw new FclRuntimeException("Concurrent file update rejected: " + path);
            }
            if (current.revisionEnabled()) {
                transaction.vfs().appendRevision(UUID.randomUUID(), current.nodeId(), owner,
                        object.objectHash(), process.ownerId(), now);
            }
        } else {
            transaction.vfs().replaceContentByAdministrator(process.ownerId(), owner,
                    current.nodeId(), object, UUID.randomUUID(), UUID.randomUUID(), now);
        }
        audit(append ? "vfs.append" : "vfs.write", current.nodeId(),
                Map.of("path", path, "bytes", Long.toString(
                        transaction.vfs().logicalObjectSize(object.objectHash()))));
        return current.nodeId().toString();
    }

    protected String createText(String source, String content, UUID owner) {
        RoutedPath routed = route(source, owner);
        source = routed.path();
        owner = routed.ownerId();
        requireFileAccess(owner, Capability.VFS_WRITE);
        if (resolve(source, owner).isPresent()) {
            throw new FclRuntimeException("Path already exists: " + normalize(source));
        }
        return createContentNode(source, content.getBytes(StandardCharsets.UTF_8),
                VfsNode.Type.FILE, false, TEXT, owner, true).nodeId().toString();
    }

    protected String writeBinary(String source, byte[] bytes, String mediaType) {
        return writeBinary(source, bytes, mediaType, "package.build");
    }

    protected String writeBinary(String source, byte[] bytes, String mediaType, String operation) {
        Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
        String path = normalize(source);
        Optional<VfsNode> existing = resolve(path);
        if (existing.isEmpty()) {
            return createContentNode(path, bytes, VfsNode.Type.FILE, false, mediaType)
                    .nodeId().toString();
        }
        VfsNode current = existing.orElseThrow();
        requireType(current, VfsNode.Type.FILE, operation);
        VfsFileLimits.requireWithinLimit(bytes.length);
        StoredObject object = StoredObject.create(new BinaryContent(bytes), mediaType, now);
        transaction.vfs().saveObject(object);
        if (!transaction.vfs().replaceContent(current.nodeId(), current.currentObjectHash(),
                object.objectHash(), now)) {
            throw new FclRuntimeException("Concurrent binary output update rejected: " + path);
        }
        if (current.revisionEnabled()) {
            transaction.vfs().appendRevision(UUID.randomUUID(), current.nodeId(),
                    process.ownerId(), object.objectHash(), process.ownerId(), now);
        }
        audit(operation + ".output", current.nodeId(), Map.of("path", path,
                "bytes", Integer.toString(bytes.length)));
        return current.nodeId().toString();
    }

    protected String attachDownloadedObject(String source, ObjectHash objectHash, String mediaType,
                                          long byteSize, String operation) {
        VfsFileLimits.requireWithinLimit(byteSize);
        Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
        String path = normalize(source);
        Optional<VfsNode> existing = resolve(path);
        if (existing.isEmpty()) {
            ParentAndName parent = parentAndName(path, process.ownerId());
            VfsNode node = new VfsNode(UUID.randomUUID(), Optional.of(parent.parent().nodeId()),
                    process.ownerId(), parent.name(), VfsNode.Type.FILE,
                    Optional.of(objectHash), Set.of(), false, now, now);
            transaction.vfs().insertNode(node);
            audit(operation + ".output", node.nodeId(), Map.of("path", path,
                    "bytes", Long.toString(byteSize), "mediaType", mediaType));
            return node.nodeId().toString();
        }
        VfsNode current = existing.orElseThrow();
        requireType(current, VfsNode.Type.FILE, operation);
        if (!transaction.vfs().replaceContent(current.nodeId(), current.currentObjectHash(),
                objectHash, now)) {
            throw new FclRuntimeException("Concurrent binary output update rejected: " + path);
        }
        if (current.revisionEnabled()) {
            transaction.vfs().appendRevision(UUID.randomUUID(), current.nodeId(),
                    process.ownerId(), objectHash, process.ownerId(), now);
        }
        audit(operation + ".output", current.nodeId(), Map.of("path", path,
                "bytes", Long.toString(byteSize), "mediaType", mediaType));
        return current.nodeId().toString();
    }

    protected VfsNode createContentNode(String source, byte[] bytes, VfsNode.Type type,
                                      boolean revisions) {
        return createContentNode(source, bytes, type, revisions, TEXT);
    }

    protected VfsNode createContentNode(String source, byte[] bytes, VfsNode.Type type,
                                      boolean revisions, String mediaType) {
        return createContentNode(source, bytes, type, revisions, mediaType, process.ownerId());
    }

    protected VfsNode createContentNode(String source, byte[] bytes, VfsNode.Type type,
                                      boolean revisions, String mediaType, UUID owner) {
        return createContentNode(source, bytes, type, revisions, mediaType, owner, false);
    }

    protected VfsNode createContentNode(String source, byte[] bytes, VfsNode.Type type,
                                      boolean revisions, String mediaType, UUID owner,
                                      boolean rejectExisting) {
        VfsFileLimits.requireWithinLimit(bytes.length);
        RoutedPath routed = route(source, owner);
        source = routed.path();
        owner = routed.ownerId();
        requireFileAccess(owner, Capability.VFS_WRITE);
        ParentAndName parent = parentAndName(source, owner);
        if (existingChild(owner, parent).isPresent()) {
            throw new FclRuntimeException("Path already exists: " + normalize(source));
        }
        StoredObject object = StoredObject.create(new BinaryContent(bytes), mediaType, now);
        if (!owner.equals(process.ownerId())) {
            if (type != VfsNode.Type.FILE) throw new FclRuntimeException(
                    "Cross-user content creation supports files only");
            try {
                return transaction.vfs().createFileByAdministrator(process.ownerId(), owner,
                        UUID.randomUUID(), parent.parent().nodeId(), parent.name(), object,
                        revisions, UUID.randomUUID(), UUID.randomUUID(), now);
            } catch (RuntimeException conflict) {
                if (rejectExisting) {
                    existingChildAfterConflict(source, owner, parent, conflict);
                    throw new FclRuntimeException("Path already exists: " + normalize(source));
                }
                return existingChildAfterConflict(source, owner, parent, conflict);
            }
        }
        transaction.vfs().saveObject(object);
        VfsNode node = new VfsNode(UUID.randomUUID(), Optional.of(parent.parent().nodeId()),
                owner, parent.name(), type, Optional.of(object.objectHash()), Set.of(),
                revisions, now, now);
        boolean inserted;
        try {
            transaction.vfs().insertNode(node);
            inserted = true;
        } catch (RuntimeException conflict) {
            if (rejectExisting) {
                existingChildAfterConflict(source, owner, parent, conflict);
                throw new FclRuntimeException("Path already exists: " + normalize(source));
            }
            node = existingChildAfterConflict(source, owner, parent, conflict);
            inserted = false;
        }
        if (inserted && revisions) {
            transaction.vfs().appendRevision(UUID.randomUUID(), node.nodeId(), owner,
                    object.objectHash(), process.ownerId(), now);
        }
        if (inserted) {
            audit("vfs.file.create", node.nodeId(), Map.of("path", normalize(source)));
        }
        return node;
    }

    protected String createDirectory(String source) {
        return createDirectory(source, process.ownerId());
    }

    protected String createDirectory(String source, UUID owner) {
        RoutedPath routed = route(source, owner);
        source = routed.path();
        owner = routed.ownerId();
        requireFileAccess(owner, Capability.VFS_WRITE);
        ParentAndName parent = parentAndName(source, owner);
        if (existingChild(owner, parent).isPresent()) {
            throw new FclRuntimeException("Path already exists: " + normalize(source));
        }
        VfsNode node;
        boolean inserted;
        if (!owner.equals(process.ownerId())) {
            try {
                node = transaction.vfs().createDirectoryByAdministrator(process.ownerId(), owner,
                        UUID.randomUUID(), parent.parent().nodeId(), parent.name(),
                        UUID.randomUUID(), now);
                inserted = true;
            } catch (RuntimeException conflict) {
                node = existingChildAfterConflict(source, owner, parent, conflict);
                inserted = false;
            }
        } else {
            VfsNode candidate = new VfsNode(UUID.randomUUID(),
                    Optional.of(parent.parent().nodeId()), owner, parent.name(),
                    VfsNode.Type.DIRECTORY, Optional.empty(), Set.of(), false, now, now);
            try {
                transaction.vfs().insertNode(candidate);
                node = candidate;
                inserted = true;
            } catch (RuntimeException conflict) {
                node = existingChildAfterConflict(source, owner, parent, conflict);
                inserted = false;
            }
        }
        if (inserted) {
            audit("vfs.directory.create", node.nodeId(), Map.of("path", normalize(source)));
        }
        return node.nodeId().toString();
    }

    protected VfsNode existingChildAfterConflict(String source, UUID owner, ParentAndName parent,
                                               RuntimeException conflict) {
        if (!(conflict instanceof DurableStorageFailure failure)
                || !failure.isUniqueConflict()) {
            throw conflict;
        }
        return existingChild(owner, parent)
                .orElseThrow(() -> new FclRuntimeException(
                        "A node already exists at this path: " + normalize(source)));
    }

    protected Optional<VfsNode> existingChild(UUID owner, ParentAndName parent) {
        if (owner.equals(process.ownerId())) {
            return transaction.vfs().findChild(owner,
                    Optional.of(parent.parent().nodeId()), parent.name());
        }
        return transaction.vfs().findChildByAdministrator(process.ownerId(), owner,
                Optional.of(parent.parent().nodeId()), parent.name());
    }

    protected boolean deletePath(String source, VfsNode.Type expected) {
        return deletePath(source, expected, process.ownerId());
    }

    protected boolean deletePath(String source, VfsNode.Type expected, UUID owner) {
        RoutedPath routed = route(source, owner);
        source = routed.path();
        owner = routed.ownerId();
        requireFileAccess(owner, Capability.VFS_WRITE);
        VfsNode node = requireNode(source, owner);
        // A symbolic link is a leaf node and is removed through the file-shaped API.
        // Requiring FILE here made links permanent for ordinary users because no separate
        // removeLink function exists.
        if (expected != null && node.type() != expected
                && !(expected == VfsNode.Type.FILE && node.type() == VfsNode.Type.SYMLINK)) {
            throw new FclRuntimeException("file.remove requires "
                    + expected.name().toLowerCase(java.util.Locale.ROOT));
        }
        boolean removed = owner.equals(process.ownerId())
                ? transaction.vfs().deleteNode(node.nodeId(), owner)
                : transaction.vfs().deleteByAdministrator(process.ownerId(), owner,
                node.nodeId(), UUID.randomUUID(), now);
        if (!removed) {
            throw new FclRuntimeException(
                    "Path is non-empty, versioned, mounted, or concurrently changed: " + source);
        }
        audit("vfs.delete", node.nodeId(), Map.of("path", normalize(source)));
        return true;
    }

    protected ParentAndName parentAndName(String source) {
        return parentAndName(source, process.ownerId());
    }

    protected ParentAndName parentAndName(String source, UUID owner) {
        String normalized = normalize(source);
        if (normalized.equals("/")) throw new FclRuntimeException("Root path cannot be changed");
        int separator = normalized.lastIndexOf('/');
        String parentPath = separator <= 0 ? "/" : normalized.substring(0, separator);
        String name = normalized.substring(separator + 1);
        VfsNode parent = requireNode(parentPath, owner);
        requireType(parent, VfsNode.Type.DIRECTORY, "file parent");
        return new ParentAndName(parent, name);
    }

    protected Optional<VfsNode> resolve(String source) {
        RoutedPath routed = route(source, process.ownerId());
        return resolve(routed.path(), routed.ownerId());
    }

    protected Optional<VfsNode> resolve(String source, UUID owner) {
        String path = normalize(source);
        boolean administrative = !owner.equals(process.ownerId());
        Optional<VfsNode> current = administrative
                ? transaction.vfs().findChildByAdministrator(process.ownerId(), owner,
                Optional.empty(), "/")
                : transaction.vfs().findChild(owner, Optional.empty(), "/");
        if (path.equals("/")) return current;
        for (String part : path.substring(1).split("/")) {
            if (current.isEmpty() || current.get().type() != VfsNode.Type.DIRECTORY) {
                return Optional.empty();
            }
            current = administrative
                    ? transaction.vfs().findChildByAdministrator(process.ownerId(), owner,
                    Optional.of(current.get().nodeId()), part)
                    : transaction.vfs().findChild(owner,
                    Optional.of(current.get().nodeId()), part);
        }
        return current;
    }

    protected VfsNode requireNode(String path) {
        return requireNode(path, process.ownerId());
    }

    protected VfsNode requireNode(String path, UUID owner) {
        return resolve(path, owner).orElseThrow(() -> new FclRuntimeException(
                "Unknown VFS path: " + normalize(path)));
    }

    protected Object remove(List<Object> args, VfsNode.Type expected, String function) {
        if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                function + " expects path and optional target user");
        return deletePath(string(args.getFirst(), function + " path"), expected, owner(args, 1));
    }

    protected Object remove(List<Object> args, String function) {
        if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                function + " expects path and optional target user");
        return deletePath(string(args.getFirst(), function + " path"), null, owner(args, 1));
    }

    protected long clearDirectoryContents(String source, UUID requestedOwner, String function) {
        RoutedPath routed = route(source, requestedOwner);
        requireFileAccess(routed.ownerId(), Capability.VFS_WRITE);
        VfsNode directory = requireNode(routed.path(), routed.ownerId());
        requireType(directory, VfsNode.Type.DIRECTORY, function);
        if (directory.parentNodeId().isEmpty()) throw new FclRuntimeException(
                function + " cannot clear a root directory: " + normalize(routed.path()));
        long removed = clearChildren(directory);
        audit("vfs.clear", directory.nodeId(), Map.of(
                "path", normalize(routed.path()), "removed", Long.toString(removed)));
        return removed;
    }

    private long clearChildren(VfsNode directory) {
        long removed = 0;
        for (VfsNode child : List.copyOf(transaction.vfs()
                .findChildren(directory.ownerId(), Optional.of(directory.nodeId())))) {
            if (child.type() == VfsNode.Type.DIRECTORY) removed += clearChildren(child);
            boolean deleted = child.ownerId().equals(process.ownerId())
                    ? transaction.vfs().deleteNode(child.nodeId(), child.ownerId())
                    : transaction.vfs().deleteByAdministrator(process.ownerId(),
                            child.ownerId(), child.nodeId(), UUID.randomUUID(), now);
            if (!deleted) throw new FclRuntimeException("file.clear cannot remove "
                    + child.type().name().toLowerCase(java.util.Locale.ROOT)
                    + " " + child.name() + ": it is versioned, mounted, non-empty, "
                    + "or concurrently changed");
            removed++;
        }
        return removed;
    }

    protected UUID owner(List<Object> args, int index) {
        if (args.size() <= index) return process.ownerId();
        String identity = string(args.get(index), "target user");
        UUID requested = null;
        try {
            requested = UUID.fromString(identity);
        } catch (IllegalArgumentException ignored) { }
        if (process.ownerId().equals(requested)) return requested;
        if (requested == null) {
            Optional<UserAccount> current = transaction.auth().findUser(process.ownerId());
            if (current.isPresent() && current.orElseThrow().username()
                    .equalsIgnoreCase(identity)) return process.ownerId();
        }
        Authorization.requireAdministrator(transaction, process.ownerId());
        UUID parsed = requested;
        return transaction.auth().findUsersByAdministrator(process.ownerId()).stream()
                .filter(user -> parsed != null ? user.userId().equals(parsed)
                        : user.username().equalsIgnoreCase(identity))
                .findFirst().orElseThrow(() -> new FclRuntimeException(
                        "Unknown target user: " + identity)).userId();
    }

    protected void requireFileAccess(UUID owner, Capability capability) {
        Authorization.require(transaction, process.ownerId(), capability);
        if (!owner.equals(process.ownerId())) {
            Authorization.requireAdministrator(transaction, process.ownerId());
        }
    }

    protected void requireFileAccess(UUID owner, Capability primary, Capability alternative) {
        java.util.Set<Capability> capabilities = transaction.auth()
                .capabilities(process.ownerId());
        if (!capabilities.contains(Capability.SYSTEM_ADMIN)
                && !capabilities.contains(primary) && !capabilities.contains(alternative)) {
            throw new SecurityException("Missing CilExec capability: " + primary.name()
                    + " or " + alternative.name());
        }
        if (!owner.equals(process.ownerId())) {
            Authorization.requireAdministrator(transaction, process.ownerId());
        }
    }

    protected String normalize(String source) {
        return FclPath.resolve(continuation, source);
    }

    protected static String parentDirectory(String absolutePath) {
        int separator = absolutePath.lastIndexOf('/');
        return separator <= 0 ? "/" : absolutePath.substring(0, separator);
    }

    protected RoutedPath route(String source, UUID requestedOwner) {
        String absolute = normalize(source);
        if (!requestedOwner.equals(process.ownerId()) || !isLocalAdministrator()
                || !absolute.startsWith("/Users/")) {
            return new RoutedPath(requestedOwner, absolute);
        }
        String remainder = absolute.substring("/Users/".length());
        int slash = remainder.indexOf('/');
        String username = slash < 0 ? remainder : remainder.substring(0, slash);
        String userPath = slash < 0 ? "/" : remainder.substring(slash);
        UserAccount target = transaction.auth().findUsersByAdministrator(process.ownerId())
                .stream().filter(user -> user.username().equalsIgnoreCase(username))
                .findFirst().orElseThrow(() ->
                        new FclRuntimeException("Unknown VFS path: " + absolute));
        return new RoutedPath(target.userId(), userPath);
    }

    protected Map<String, Object> virtualUserNode(UserAccount user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", "virtual-user-root:" + user.userId());
        result.put("ownerId", user.userId().toString());
        result.put("name", user.username());
        result.put("type", VfsNode.Type.DIRECTORY.name());
        result.put("revisionEnabled", false);
        result.put("virtual", true);
        return Map.copyOf(result);
    }

    protected boolean isLocalAdministrator() {
        // User transactions intentionally cannot SELECT auth.user_account directly.
        // The security-definer capability function is the authority for administrator routing.
        return isAdministrator();
    }

    protected void requireLocalAdministrator() {
        Authorization.requireAdministrator(transaction, process.ownerId());
    }

    protected static String environmentName(Object value) {
        String name = string(value, "environment variable name").trim()
                .toUpperCase(Locale.ROOT);
        if (!name.matches("[A-Z_][A-Z0-9_]{0,127}")) throw new FclRuntimeException(
                "Environment variable name must match [A-Z_][A-Z0-9_]{0,127}");
        return name;
    }

    protected static String environmentValue(Object value) {
        String text = string(value, "environment variable value");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_ENVIRONMENT_VALUE_BYTES) {
            throw new FclRuntimeException("Environment variable value exceeds 64 KiB");
        }
        return text;
    }

    protected String runtimeEnvironment(String name) {
        return switch (name) {
            case "PWD" -> FclPath.current(continuation);
            case "USER" -> transaction.auth().findVisibleUsername(process.ownerId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Current process owner account is missing"));
            case "USER_ID" -> process.ownerId().toString();
            case "PID" -> Long.toString(process.identity().pid());
            default -> throw new IllegalArgumentException(
                    "Unknown Runtime environment variable: " + name);
        };
    }

    protected static void requireWritableEnvironmentName(String name, String operation) {
        if (RUNTIME_ENVIRONMENT_NAMES.contains(name)) {
            throw new FclRuntimeException(operation + " cannot change Java-managed Runtime "
                    + "environment variable " + name);
        }
    }

    protected Map<String, Object> nodeMap(VfsNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", node.nodeId().toString());
        result.put("ownerId", node.ownerId().toString());
        result.put("name", node.name());
        result.put("type", node.type().name());
        result.put("revisionEnabled", node.revisionEnabled());
        result.put("updatedAt", node.updatedAt().toString());
        node.currentObjectHash().ifPresent(hash -> result.put("objectHash", hash.value()));
        return Map.copyOf(result);
    }


    protected Object download(List<Object> args, FclFunctionRegistry.Invocation invocation) {
        arity(args, 2, "network.download");
        Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
        String url = string(args.get(0), "network.download url");
        String path = string(args.get(1), "network.download destination");
        FclScope scope = invocation.continuation().scope();
        String expression = "cilexec.download." + invocation.expressionId();
        String identity = downloadIdentity(url, path);
        if (scope.contains(expression + ".target")) {
            String previous = string(scope.get(expression + ".target"),
                    "network.download identity");
            if (!previous.equals(identity)) {
                clearDownloadState(scope, expression + "." + previous + ".");
            }
        }
        scope.put(expression + ".target", identity);
        String state = expression + "." + identity + ".";
        long offset = scope.contains(state + "offset")
                ? integer(scope.get(state + "offset"), "network.download offset") : 0L;
        Optional<ObjectHash> currentHash = scope.contains(state + "hash")
                ? Optional.of(new ObjectHash(string(scope.get(state + "hash"),
                "network.download object hash"))) : Optional.empty();
        String mediaType = scope.contains(state + "mediaType")
                ? string(scope.get(state + "mediaType"), "network.download media type") : null;
        if (offset < 0 || offset > MAX_FILE_BYTES) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("Download state exceeds the 1 GiB file limit");
        }

        int maximum = (int) Math.min(DOWNLOAD_CHUNK_BYTES,
                MAX_FILE_BYTES - offset + 1L);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("url", url);
        request.put("offset", offset);
        request.put("maximumBytes", (long) maximum);
        if (scope.contains(state + "validator")) {
            request.put("validator", string(scope.get(state + "validator"),
                    "network.download validator"));
        }
        Object delivered = external(invocation, "network.download", Map.copyOf(request),
                idempotentPolicy(invocation, "DOWNLOAD:" + url + ":" + offset), true);
        if (!(delivered instanceof Map<?, ?> response)) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download returned an invalid response");
        }
        long status = integer(response.get("status"), "network.download status");
        long total = response.containsKey("totalBytes")
                ? integer(response.get("totalBytes"), "network.download total bytes") : -1L;
        boolean complete = Boolean.TRUE.equals(response.get("complete"));
        if (total > MAX_FILE_BYTES) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("Downloaded file exceeds the 1 GiB limit");
        }
        if (status == 416 && complete && total == offset) {
            if (mediaType == null) mediaType = "application/octet-stream";
            clearDownloadState(scope, state);
            ObjectHash finalHash;
            if (currentHash.isPresent()) {
                finalHash = currentHash.orElseThrow();
            } else {
                // A zero-byte object: the first range probe was answered 416 bytes */0,
                // so there is nothing to download yet the file legitimately exists.
                if (offset != 0) {
                    throw new FclRuntimeException(
                            "network.download cannot resume an object with no stored hash");
                }
                StoredObject empty = StoredObject.create(
                        new BinaryContent(new byte[0]), mediaType, now);
                transaction.vfs().saveObject(empty);
                finalHash = empty.objectHash();
            }
            String nodeId = attachDownloadedObject(path, finalHash, mediaType,
                    offset, "network.download");
            return completedDownload(nodeId, path, url, 206L, offset, mediaType);
        }
        if (status < 200 || status >= 300) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download failed with HTTP status " + status);
        }
        long returnedOffset = integer(response.get("offset"), "network.download returned offset");
        if (returnedOffset != offset) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download returned the wrong byte range");
        }
        String encoded = string(response.get("bodyBase64"), "network.download body");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download returned invalid binary data");
        }
        long reportedBytes = integer(response.get("bytes"), "network.download returned bytes");
        if (reportedBytes != bytes.length || offset + bytes.length > MAX_FILE_BYTES) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("Downloaded file exceeds the 1 GiB limit");
        }
        if (bytes.length == 0 && !complete) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download returned an empty incomplete range");
        }
        if (mediaType == null) {
            mediaType = response.get("mediaType") instanceof String value && !value.isBlank()
                    ? value : "application/octet-stream";
        }

        ObjectHash nextHash;
        if (currentHash.isEmpty()) {
            StoredObject first = StoredObject.create(new BinaryContent(bytes), mediaType, now);
            transaction.vfs().saveObject(first);
            nextHash = first.objectHash();
        } else if (bytes.length == 0) {
            nextHash = currentHash.orElseThrow();
        } else {
            nextHash = transaction.vfs().appendChunkedObject(currentHash.orElseThrow(), bytes,
                    mediaType, now).objectHash();
        }
        long downloaded = offset + bytes.length;
        if (complete && total >= 0 && total != downloaded) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download completed at the wrong file size");
        }
        complete = complete || total == downloaded;
        if (complete) {
            clearDownloadState(scope, state);
            String nodeId = attachDownloadedObject(path, nextHash, mediaType, downloaded,
                    "network.download");
            return completedDownload(nodeId, path, url, status, downloaded, mediaType);
        }

        // The next chunk needs an If-Range validator; without one a changed remote file
        // would silently interleave new and old content across chunks.
        if (!scope.contains(state + "validator")
                && !(response.get("validator") instanceof String validator
                && !validator.isBlank())) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download cannot resume without a validator "
                    + "from the server (ETag or Last-Modified required for multi-chunk "
                    + "downloads)");
        }

        scope.put(state + "offset", downloaded);
        scope.put(state + "hash", nextHash.value());
        scope.put(state + "mediaType", mediaType);
        if (response.get("validator") instanceof String validator && !validator.isBlank()) {
            scope.put(state + "validator", validator);
        }
        return download(args, invocation);
    }

    protected Map<String, Object> completedDownload(String nodeId, String path, String url,
                                                   long status, long bytes, String mediaType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put("path", normalize(path));
        result.put("url", url);
        result.put("status", status);
        result.put("bytes", bytes);
        result.put("mediaType", mediaType);
        return Map.copyOf(result);
    }

    protected static void clearDownloadState(FclScope scope, String prefix) {
        for (String suffix : List.of("offset", "hash", "mediaType", "validator")) {
            String key = prefix + suffix;
            if (scope.contains(key)) scope.remove(key);
        }
    }

    protected static String downloadIdentity(String url, String destinationPath) {
        return sha256((url + "\0" + destinationPath).getBytes(StandardCharsets.UTF_8))
                .substring(0, 16);
    }

    protected EffectRequest.Policy idempotentPolicy(FclFunctionRegistry.Invocation invocation,
                                                   String operation) {
        // A terminal process is deliberately reused across commands. Expression identifiers
        // restart for every compiled submission, so epoch + expression alone aliases effects
        // from separate commands. stateVersion is stable across a transaction retry but advances
        // before the next terminal submission. Hash the material to keep attacker-controlled URLs
        // out of the unique-index key.
        String material = process.identity().processUid() + ":" + process.executionEpoch() + ":"
                + process.stateVersion() + ":" + invocation.expressionId() + ":" + operation;
        String key = sha256(material.getBytes(StandardCharsets.UTF_8));
        return new EffectRequest.Policy(true, Optional.of(key), false, true,
                EffectRequest.UnknownAction.RETRY_IDEMPOTENT);
    }
}
