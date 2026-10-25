package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclSystemHandle;
import com.follarce.fcl.FclFunctionRegistry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class FclFileRuntimeFunctions extends FclVfsRuntimeSupport {
    FclFileRuntimeFunctions(FclVfsRuntimeSupport source) { super(source); }

    protected void registerFiles() {
        registry.register("file", "read", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "file.read expects path and optional target user");
                    return readText(string(args.getFirst(), "file.read path"), owner(args, 1));
                })
                .register("file", "readChunk", args -> {
                    if (args.size() < 3 || args.size() > 4) throw new FclRuntimeException(
                            "file.readChunk expects path, offset, maximum bytes, and optional target user");
                    String path = string(args.get(0), "file.readChunk path");
                    long offset = integer(args.get(1), "file.readChunk offset");
                    long requested = integer(args.get(2), "file.readChunk maximum bytes");
                    if (offset < 0 || requested < 0 || requested > 4L * 1024 * 1024) {
                        throw new FclRuntimeException(
                                "file.readChunk requires a non-negative offset and at most 4 MiB");
                    }
                    return decodeUtf8(readRange(path, offset, (int) requested, owner(args, 3)),
                            "file.readChunk");
                })
                .register("file", "size", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "file.size expects path and optional target user");
                    String path = string(args.getFirst(), "file.size path");
                    RoutedPath routed = route(path, owner(args, 1));
                    VfsNode node = resolveFileNode(routed.path(), routed.ownerId());
                    ObjectHash hash = node.currentObjectHash().orElseThrow();
                    return routed.ownerId().equals(process.ownerId())
                            ? transaction.vfs().logicalObjectSize(hash)
                            : transaction.vfs().logicalObjectSizeByAdministrator(
                            process.ownerId(), routed.ownerId(), hash);
                })
                .register("file", "exists", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "file.exists expects path and optional target user");
                    RoutedPath routed = route(string(args.getFirst(), "file.exists path"),
                            owner(args, 1));
                    requireFileAccess(routed.ownerId(), Capability.VFS_READ,
                            Capability.VFS_WRITE);
                    return resolve(routed.path(), routed.ownerId())
                            .isPresent();
                })
                .register("file", "list", args -> {
                    if (args.size() > 2) throw new FclRuntimeException(
                            "file.list expects optional path and target user");
                    String requested = args.isEmpty() ? "/"
                            : string(args.getFirst(), "file.list path");
                    UUID owner = owner(args, 1);
                    String absolute = normalize(requested);
                    RoutedPath routed = route(requested, owner);
                    if (routed.ownerId().equals(process.ownerId())
                            && routed.path().equals("/Users") && isLocalAdministrator()) {
                        return transaction.auth().findUsersByAdministrator(process.ownerId())
                                .stream().map(user -> virtualUserNode(user)).toList();
                    }
                    requireFileAccess(routed.ownerId(), Capability.VFS_READ);
                    VfsNode directory = requireNode(routed.path(), routed.ownerId());
                    requireType(directory, VfsNode.Type.DIRECTORY, "file.list");
                    return transaction.vfs().findChildren(routed.ownerId(),
                                    Optional.of(directory.nodeId())).stream()
                            .map(this::nodeMap).toList();
                })
                .register("file", "readMetaData", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "file.readMetaData expects path and optional target user");
                    RoutedPath routed = route(string(args.getFirst(),
                            "file.readMetaData path"), owner(args, 1));
                    requireFileAccess(routed.ownerId(), Capability.VFS_READ);
                    return nodeMap(requireNode(routed.path(), routed.ownerId()));
                })
                .register("file", "write", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "file.write expects path, content, and optional target user");
                    return writeText(string(args.get(0), "file.write path"),
                            display(args.get(1)), false, owner(args, 2));
                })
                .register("file", "append", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "file.append expects path, content, and optional target user");
                    return writeText(string(args.get(0), "file.append path"),
                            display(args.get(1)), true, owner(args, 2));
                })
                .register("file", "createFile", args -> {
                    if (args.size() < 1 || args.size() > 3) throw new FclRuntimeException(
                            "file.createFile expects path, optional content and target user");
                    String content = args.size() >= 2 ? display(args.get(1)) : "";
                    return createText(string(args.getFirst(), "file.createFile path"),
                            content, owner(args, 2));
                })
                .register("file", "createDir", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "file.createDir expects path and optional target user");
                    return createDirectory(string(args.getFirst(), "file.createDir path"),
                            owner(args, 1));
                })
                .register("file", "remove", args -> remove(args, "file.remove"))
                .register("file", "clear", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "file.clear expects path and optional target user");
                    return clearDirectoryContents(string(args.getFirst(),
                            "file.clear path"), owner(args, 1), "file.clear");
                })
                .register("file", "rename", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "file.rename expects path, name, and optional target user");
                    UUID requestedOwner = owner(args, 2);
                    RoutedPath routed = route(string(args.get(0), "file.rename path"),
                            requestedOwner);
                    UUID owner = routed.ownerId();
                    requireFileAccess(owner, Capability.VFS_WRITE);
                    VfsNode source = requireNode(routed.path(), owner);
                    String replacement = string(args.get(1), "file.rename name");
                    if (replacement.isBlank() || replacement.contains("/")
                            || replacement.contains("\\") || replacement.equals(".")
                            || replacement.equals("..") || replacement.codePoints()
                            .anyMatch(Character::isISOControl)) {
                        throw new FclRuntimeException("file.rename requires a safe file name");
                    }
                    boolean renamed = owner.equals(process.ownerId())
                            ? transaction.vfs().renameNode(source.nodeId(), owner, replacement, now)
                            : transaction.vfs().renameByAdministrator(process.ownerId(), owner,
                            source.nodeId(), replacement, UUID.randomUUID(), now) != null;
                    if (!renamed) {
                        throw new FclRuntimeException("file.rename was rejected");
                    }
                    audit("vfs.rename", source.nodeId(), Map.of("name", replacement));
                    return true;
                })
                .register("file", "link", args -> {
                    arity(args, 2, "file.link");
                    String linkPath = string(args.get(0), "file.link path");
                    String target = normalize(string(args.get(1), "file.link target"));
                    byte[] content = target.getBytes(StandardCharsets.UTF_8);
                    if (content.length > MAX_LINK_TARGET_BYTES) {
                        throw new FclRuntimeException("file.link target exceeds "
                                + MAX_LINK_TARGET_BYTES + " bytes");
                    }
                    return createContentNode(linkPath, content,
                            VfsNode.Type.SYMLINK, false).nodeId().toString();
                })
                .register("file", "lock", args -> {
                    arity(args, 2, "file.lock");
                    Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
                    VfsNode node = requireNode(string(args.get(0), "file.lock path"));
                    long lease = positiveMillis(args.get(1), "file.lock lease");
                    return transaction.vfs().acquireLock(node.nodeId(), process.ownerId(),
                                    process.identity().processUid(), process.executionEpoch(),
                                    now.plusMillis(lease), now)
                            .map(FclRuntimeFunctions::fileLockMap).orElse(null);
                })
                .register("file", "renewLock", args -> {
                    arity(args, 3, "file.renewLock");
                    Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
                    VfsNode node = requireNode(string(args.get(0), "file.renewLock path"));
                    long token = integer(args.get(1), "file.renewLock token");
                    long lease = positiveMillis(args.get(2), "file.renewLock lease");
                    return transaction.vfs().renewLock(node.nodeId(), process.ownerId(),
                                    process.identity().processUid(), process.executionEpoch(), token,
                                    now.plusMillis(lease), now)
                            .map(FclRuntimeFunctions::fileLockMap).orElse(null);
                })
                .register("file", "unlock", args -> {
                    arity(args, 2, "file.unlock");
                    Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
                    VfsNode node = requireNode(string(args.get(0), "file.unlock path"));
                    return transaction.vfs().releaseLock(node.nodeId(), process.ownerId(),
                            process.identity().processUid(), process.executionEpoch(),
                            integer(args.get(1), "file.unlock token"));
                });

        registry.aliasQualified("file.read", "io", "readFile")
                .aliasQualified("file.write", "io", "writeFile");

        registerObjects();

    }

    private static final String OWNER_ATTRIBUTE = "owner";

    private void registerObjects() {
        registry.register("file", "open", args -> {
            if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                    "file.open expects path and optional target user");
            RoutedPath routed = route(string(args.getFirst(), "file.open path"), owner(args, 1));
            // Opening resolves identity only; the individual method still checks the
            // capability it needs (read, write, or delete) before doing any work, so a
            // read-only caller may open a node for read methods.
            requireFileAccess(routed.ownerId(), Capability.VFS_READ);
            VfsNode node = requireNode(routed.path(), routed.ownerId());
            return new FclSystemHandle(node.type() == VfsNode.Type.DIRECTORY
                    ? "Directory" : "File", node.nodeId().toString(), ownerAttributes(node));
        });
        registry.register("file", "path", args -> {
            arity(args, 1, "file.path");
            return new FclSystemHandle("Path", normalize(string(args.getFirst(), "file.path path")));
        });
        registry.register("file", "create", args -> {
            if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                    "file.create expects path and optional content");
            String path = string(args.getFirst(), "file.create path");
            String content = args.size() == 2 ? display(args.get(1)) : "";
            String id = createText(path, content, process.ownerId());
            return new FclSystemHandle("File", id);
        });
        registry.register("file", "mkdir", args -> {
            arity(args, 1, "file.mkdir");
            String id = createDirectory(string(args.getFirst(), "file.mkdir path"), process.ownerId());
            return new FclSystemHandle("Directory", id);
        });
        registry.registerMethod("Path", "exists", (args, invocation) -> {
            FclSystemHandle path = (FclSystemHandle) args.getFirst();
            arity(args.subList(1, args.size()), 0, "Path.exists");
            return registry.invokeInternal("file.exists", List.of(path.id()), invocation);
        });
        for (String method : List.of("read", "readChunk", "size", "metadata", "write", "append",
                "rename", "remove", "lock")) {
            registry.registerMethod("File", method, (args, invocation) ->
                    invokeNode((FclSystemHandle) args.getFirst(), method,
                            args.subList(1, args.size()), invocation));
        }
        for (String method : List.of("list", "clear", "metadata", "rename", "remove")) {
            registry.registerMethod("Directory", method, (args, invocation) ->
                    invokeNode((FclSystemHandle) args.getFirst(), method,
                            args.subList(1, args.size()), invocation));
        }
        registry.registerMethod("FileLease", "renew", (args, invocation) -> {
            FclSystemHandle lease = (FclSystemHandle) args.getFirst();
            arity(args.subList(1, args.size()), 1, "FileLease.renew");
            FileTarget target = leaseTarget(lease);
            target.requireLeasable();
            Object result = registry.invokeInternal("file.renewLock", List.of(
                    target.path(), Long.parseLong(lease.attributes().get("token")),
                    args.get(1)), invocation);
            if (result == null) throw new FclRuntimeException("ExpiredLease", "File lease expired");
            return lease;
        });
        registry.registerMethod("FileLease", "release", (args, invocation) -> {
            FclSystemHandle lease = (FclSystemHandle) args.getFirst();
            arity(args.subList(1, args.size()), 0, "FileLease.release");
            FileTarget target = leaseTarget(lease);
            return registry.invokeInternal("file.unlock", List.of(target.path(),
                    Long.parseLong(lease.attributes().get("token"))), invocation);
        });
        for (String name : List.of("read", "readChunk", "size", "exists", "list", "readMetaData",
                "write", "append", "createFile", "createDir", "remove", "clear", "rename",
                "lock", "renewLock", "unlock")) registry.internalize("file." + name);
    }

    private Object invokeNode(FclSystemHandle handle, String method, List<Object> supplied,
                              FclFunctionRegistry.Invocation invocation) {
        FileTarget target = liveTarget(handle);
        boolean crossOwner = !target.ownerId().equals(process.ownerId());
        if (crossOwner && Set.of("lock", "renewLock", "unlock").contains(method)) {
            throw new FclRuntimeException(
                    "File leases are limited to files owned by the current user");
        }
        String legacy = method.equals("metadata") ? "readMetaData" : method;
        List<Object> arguments = new ArrayList<>(supplied.size() + 2);
        arguments.add(target.path());
        arguments.addAll(supplied);
        if (crossOwner) arguments.add(target.ownerId().toString());
        Object result = registry.invokeInternal("file." + legacy, arguments, invocation);
        if (method.equals("lock")) {
            if (result == null) return null;
            Object token = ((Map<?, ?>) result).get("fencingToken");
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("token", String.valueOf(token));
            if (crossOwner) attributes.put(OWNER_ATTRIBUTE, target.ownerId().toString());
            return new FclSystemHandle("FileLease", target.node().nodeId().toString(),
                    Map.copyOf(attributes));
        }
        return result;
    }

    private Map<String, String> ownerAttributes(VfsNode node) {
        return node.ownerId().equals(process.ownerId())
                ? Map.of() : Map.of(OWNER_ATTRIBUTE, node.ownerId().toString());
    }

    private record FileTarget(UUID ownerId, VfsNode node, String path) {
        void requireLeasable() {
            if (!node.type().equals(VfsNode.Type.FILE)) {
                throw new FclRuntimeException("File lease requires a file");
            }
        }
    }

    /** Re-resolves the durable node identity on every call; a handle never grants authority. */
    private FileTarget liveTarget(FclSystemHandle handle) {
        UUID nodeId;
        try {
            nodeId = UUID.fromString(handle.id());
        } catch (IllegalArgumentException invalid) {
            throw new FclRuntimeException("StaleHandle", "Invalid VFS handle identity");
        }
        UUID ownerId = handle.attributes().containsKey(OWNER_ATTRIBUTE)
                ? UUID.fromString(handle.attributes().get(OWNER_ATTRIBUTE)) : process.ownerId();
        VfsNode node;
        if (ownerId.equals(process.ownerId())) {
            node = transaction.vfs().findNode(nodeId).orElseThrow(() ->
                    new FclRuntimeException("StaleHandle", "VFS node was removed"));
        } else {
            Authorization.requireAdministrator(transaction, process.ownerId());
            node = transaction.vfs().findAllNodesByAdministrator(process.ownerId(), ownerId,
                            UUID.randomUUID(), now).stream()
                    .filter(candidate -> candidate.nodeId().equals(nodeId))
                    .findFirst().orElseThrow(() ->
                            new FclRuntimeException("StaleHandle", "VFS node was removed"));
        }
        if (!node.ownerId().equals(ownerId)) {
            throw new FclRuntimeException("StaleHandle", "VFS node owner changed");
        }
        boolean directory = node.type() == VfsNode.Type.DIRECTORY;
        if (handle.kind().equals("Directory") != directory) {
            throw new FclRuntimeException("StaleHandle", "VFS node type changed");
        }
        return new FileTarget(ownerId, node, pathOf(node, ownerId));
    }

    private FileTarget leaseTarget(FclSystemHandle lease) {
        return liveTarget(new FclSystemHandle("File", lease.id(), lease.attributes()));
    }

    private String pathOf(VfsNode node, UUID ownerId) {
        Map<UUID, VfsNode> administratorNodes = null;
        if (!ownerId.equals(process.ownerId())) {
            Authorization.requireAdministrator(transaction, process.ownerId());
            administratorNodes = new LinkedHashMap<>();
            for (VfsNode candidate : transaction.vfs().findAllNodesByAdministrator(
                    process.ownerId(), ownerId, UUID.randomUUID(), now)) {
                administratorNodes.put(candidate.nodeId(), candidate);
            }
        }
        List<String> parts = new ArrayList<>();
        VfsNode current = node;
        for (int depth = 0; current.parentNodeId().isPresent(); depth++) {
            if (depth >= 256) throw new FclRuntimeException("InvalidPath", "VFS ancestry is too deep");
            parts.addFirst(current.name());
            UUID parentId = current.parentNodeId().orElseThrow();
            current = administratorNodes != null
                    ? Optional.ofNullable(administratorNodes.get(parentId)).orElseThrow(() ->
                    new FclRuntimeException("StaleHandle", "VFS parent was removed"))
                    : transaction.vfs().findNode(parentId).orElseThrow(() ->
                    new FclRuntimeException("StaleHandle", "VFS parent was removed"));
            if (!current.ownerId().equals(ownerId)) {
                throw new FclRuntimeException("StaleHandle", "VFS owner changed");
            }
        }
        return "/" + String.join("/", parts);
    }

}
