package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.auth.PasswordPolicy;
import com.follarce.auth.UsernamePolicy;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageInstallation;
import com.follarce.domain.packageinfo.PackageDataUsage;
import com.follarce.domain.packageinfo.PackageUninstallResult;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.ipc.IpcChannel;
import com.follarce.domain.ipc.IpcMessage;
import com.follarce.domain.ipc.IpcTopic;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.EnvironmentRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.program.Program;
import com.follarce.domain.timer.ProcessTimer;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.ipc.IpcService;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.domain.vfs.VfsFileLimits;
import com.follarce.fcl.FclBuiltins;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclInstruction;
import com.follarce.fcl.FclPath;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclScope;
import com.follarce.fcl.FclValues;
import com.follarce.fcl.TerminalModeState;
import com.follarce.fcl.FclSuspension;
import com.follarce.extension.JavaExtensionCatalog;
import com.follarce.extension.SourceExtensionIndex;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import com.follarce.package_manager.PackageBuilder;
import com.follarce.package_manager.PackageDataService;
import com.follarce.package_manager.PackageDependencyPolicy;
import com.follarce.market.client.MarketRuntimeFunctions;
import com.follarce.terminal.TerminalAccessService;
import com.follarce.terminal.TerminalDimensions;
import com.follarce.timer.TimerService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class FclFileRuntimeFunctions extends FclRuntimeFunctions {
    FclFileRuntimeFunctions(FclRuntimeFunctions source) { super(source); }

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
                    if (owner.equals(process.ownerId()) && absolute.equals("/Users")
                            && isLocalAdministrator()) {
                        return transaction.auth().findUsersByAdministrator(process.ownerId())
                                .stream().map(user -> virtualUserNode(user)).toList();
                    }
                    RoutedPath routed = route(requested, owner);
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

    }

}
