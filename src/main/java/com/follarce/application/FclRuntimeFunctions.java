package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageBinding;
import com.follarce.domain.packageinfo.PackageEnvironment;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.program.Program;
import com.follarce.domain.timer.ProcessTimer;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.VfsNode;
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
import com.follarce.fcl.FclSuspension;
import com.follarce.extension.JavaExtensionCatalog;
import com.follarce.extension.SourceExtensionIndex;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import com.follarce.package_manager.PackageCoordinateConflictException;
import com.follarce.package_manager.PackageBuilder;
import com.follarce.package_manager.PackageEnvironments;
import com.follarce.terminal.TerminalDimensions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Explicit application adapter that exposes durable CilExec services to one FCL statement. */
public final class FclRuntimeFunctions {
    private static final String TEXT = "text/plain;charset=utf-8";
    static final long MAX_FILE_BYTES = 1L * 1024 * 1024 * 1024;
    private static final int DOWNLOAD_CHUNK_BYTES = 4 * 1024 * 1024;
    private static final EffectRequest.Policy MANUAL_EFFECT = new EffectRequest.Policy(
            false, Optional.empty(), false, false, EffectRequest.UnknownAction.MANUAL);

    @FunctionalInterface
    public interface LocalPasswordVerifier {
        boolean verify(char[] password);
    }

    private static volatile LocalPasswordVerifier passwordVerifier;

    public static void setPasswordVerifier(LocalPasswordVerifier verifier) {
        passwordVerifier = java.util.Objects.requireNonNull(verifier, "passwordVerifier");
    }

    private final TransactionContext transaction;
    private final CilProcess process;
    private final Program program;
    private final FclContinuation continuation;
    private final Instant now;
    private final JavaExtensionCatalog extensions;
    private final FclContinuationCodec codec = new FclContinuationCodec();
    private final FclFunctionRegistry registry = FclBuiltins.pureRegistry();

    private FclRuntimeFunctions(TransactionContext transaction, CilProcess process, Program program,
                                FclContinuation continuation, Instant now,
                                JavaExtensionCatalog extensions) {
        this.transaction = java.util.Objects.requireNonNull(transaction, "transaction");
        this.process = java.util.Objects.requireNonNull(process, "process");
        this.program = java.util.Objects.requireNonNull(program, "program");
        this.continuation = java.util.Objects.requireNonNull(continuation, "continuation");
        this.now = java.util.Objects.requireNonNull(now, "now");
        this.extensions = java.util.Objects.requireNonNull(extensions, "extensions");
    }

    static FclFunctionRegistry create(TransactionContext transaction, CilProcess process,
                                      Program program, FclContinuation continuation, Instant now) {
        return create(transaction, process, program, continuation, now,
                SourceExtensionIndex.catalog());
    }

    static FclFunctionRegistry create(TransactionContext transaction, CilProcess process,
                                      Program program, FclContinuation continuation, Instant now,
                                      JavaExtensionCatalog extensions) {
        FclRuntimeFunctions functions = new FclRuntimeFunctions(transaction, process, program,
                continuation, now, extensions);
        functions.register();
        return functions.registry;
    }

    private void register() {
        registerPathState();
        registerUtilityAndIo();
        registerFiles();
        registerProcesses();
        registerUsers();
        registerNetworkAndSockets();
        registerPackages();
        registerSwapPool();
        registerSystem();
        extensions.installFunctions(registry, transaction, process, continuation, now);
    }

    private void registerPathState() {
        registry.register("path", "getEnvVar", args -> {
                    arity(args, 1, "path.getEnvVar");
                    String name = string(args.getFirst(), "environment variable");
                    return switch (name) {
                        case "PWD" -> FclPath.current(continuation);
                        case "USER", "USER_ID" -> process.ownerId().toString();
                        case "PID" -> Long.toString(process.identity().pid());
                        default -> null;
                    };
                })
                .registerContextual("path", "setAlias", (args, invocation) -> {
                    arity(args, 2, "path.setAlias");
                    Map<String, Object> aliases = aliases(invocation.continuation());
                    aliases.put(string(args.get(0), "alias"),
                            normalize(string(args.get(1), "alias path")));
                    invocation.continuation().scope().put("path.aliases", aliases);
                    return true;
                })
                .registerContextual("path", "removeAlias", (args, invocation) -> {
                    arity(args, 1, "path.removeAlias");
                    Map<String, Object> aliases = aliases(invocation.continuation());
                    boolean removed = aliases.remove(string(args.getFirst(), "alias")) != null;
                    invocation.continuation().scope().put("path.aliases", aliases);
                    return removed;
                })
                .registerContextual("path", "getAlias", (args, invocation) -> {
                    arity(args, 1, "path.getAlias");
                    return aliases(invocation.continuation()).get(
                            string(args.getFirst(), "alias"));
                })
                .registerContextual("path", "listAliases", (args, invocation) -> {
                    arity(args, 0, "path.listAliases");
                    return aliases(invocation.continuation());
                });
    }

    private static Map<String, Object> aliases(FclContinuation continuation) {
        if (!continuation.scope().contains("path.aliases")) return new LinkedHashMap<>();
        Object value = continuation.scope().get("path.aliases");
        if (!(value instanceof Map<?, ?> source)) throw new FclRuntimeException(
                "Persisted path alias state is invalid");
        Map<String, Object> aliases = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String name) || !(item instanceof String)) {
                throw new FclRuntimeException("Persisted path alias entry is invalid");
            }
            aliases.put(name, item);
        });
        return aliases;
    }

    private void registerUtilityAndIo() {
        registry.register("util", "getTime", args -> {
                    arity(args, 0, "util.getTime");
                    return now.toEpochMilli();
                })
                .register("math", "random", args -> {
                    if (args.isEmpty()) return Math.random();
                    arity(args, 2, "math.random");
                    long lower = integer(args.get(0), "math.random lower");
                    long upper = integer(args.get(1), "math.random upper");
                    if (upper <= lower) throw new FclRuntimeException(
                            "math.random upper bound must exceed lower bound");
                    return java.util.concurrent.ThreadLocalRandom.current().nextLong(lower, upper);
                })
                .register("term", "getSize", args -> {
                    arity(args, 0, "term.getSize");
                    TerminalDimensions.Size size = TerminalDimensions.current();
                    return Map.of("width", (long) size.width(),
                            "height", (long) size.height());
                }, "size");

        FclFunctionRegistry.ContextFunction print = (args, invocation) -> {
            arity(args, 1, "print");
            return external(invocation, "io.output",
                    Map.of("text", display(args.getFirst()), "newline", false), MANUAL_EFFECT,
                    false);
        };
        FclFunctionRegistry.ContextFunction println = (args, invocation) -> {
            arity(args, 1, "println");
            return external(invocation, "io.output",
                    Map.of("text", display(args.getFirst()), "newline", true), MANUAL_EFFECT,
                    false);
        };
        registry.registerContextual("io", "print", print)
                .registerContextual("io", "println", println)
                .aliasQualified("io.print", "util", "print")
                .aliasQualified("io.println", "util", "println")
                .registerContextual("io", "input", (args, invocation) -> {
                    if (args.size() > 1) arity(args, 1, "io.input");
                    if (!args.isEmpty()) {
                        external(invocation, "io.output",
                                Map.of("text", display(args.getFirst()), "newline", false),
                                MANUAL_EFFECT, false);
                    }
                    return terminalInput(invocation, false);
                })
                .aliasQualified("io.input", "util", "input")
                .registerContextual("io", "readChar", (args, invocation) -> {
                    arity(args, 0, "io.readChar");
                    return terminalInput(invocation, true, false);
                })
                .registerContextual("io", "readKey", (args, invocation) -> {
                    arity(args, 0, "io.readKey");
                    return terminalInput(invocation, false, true);
                })
                .registerContextual("util", "sleep", (args, invocation) -> {
                    arity(args, 1, "util.sleep");
                    long millis = integer(args.getFirst(), "util.sleep milliseconds");
                    if (millis < 0) throw new FclRuntimeException(
                            "util.sleep milliseconds cannot be negative");
                    if (invocation.continuation().scope().contains(ProcessInbox.TIMER_RESULT)) {
                        invocation.continuation().scope().remove(ProcessInbox.TIMER_RESULT);
                        return null;
                    }
                    UUID timerId = UUID.randomUUID();
                    transaction.timers().save(new ProcessTimer(timerId,
                            process.identity().processUid(), now.plus(Duration.ofMillis(millis)),
                            ProcessTimer.Status.SCHEDULED, now, Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.of(typed(null))));
                    invocation.continuation().waitFor("timer:" + timerId,
                            Map.of("milliseconds", millis));
                    throw FclSuspension.suspend();
                })
                .registerContextual("util", "exit", (args, invocation) -> {
                    if (args.size() > 1) throw new FclRuntimeException(
                            "util.exit expects zero or one argument");
                    Object result = args.isEmpty() ? null : args.getFirst();
                    invocation.continuation().exit(result);
                    return result;
                });
    }

    private void registerFiles() {
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
                    if (offset < 0 || requested < 0 || requested > 64L * 1024 * 1024) {
                        throw new FclRuntimeException(
                                "file.readChunk requires a non-negative offset and at most 64 MiB");
                    }
                    return new String(readRange(path, offset, (int) requested, owner(args, 3)),
                            StandardCharsets.UTF_8);
                })
                .register("file", "size", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "file.size expects path and optional target user");
                    UUID owner = owner(args, 1);
                    requireFileAccess(owner, Capability.VFS_READ);
                    VfsNode node = requireNode(string(args.getFirst(), "file.size path"), owner);
                    requireType(node, VfsNode.Type.FILE, "file.size");
                    return transaction.vfs().logicalObjectSize(
                            node.currentObjectHash().orElseThrow());
                })
                .register("file", "exists", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "file.exists expects path and optional target user");
                    return resolve(string(args.getFirst(), "file.exists path"), owner(args, 1))
                            .isPresent();
                })
                .register("file", "listdir", args -> {
                    if (args.size() > 2) throw new FclRuntimeException(
                            "file.listdir expects optional path and target user");
                    String requested = args.isEmpty() ? "."
                            : string(args.getFirst(), "file.listdir path");
                    UUID owner = owner(args, 1);
                    VfsNode directory = requireNode(requested, owner);
                    requireType(directory, VfsNode.Type.DIRECTORY, "file.listdir");
                    return transaction.vfs().findChildren(owner,
                                    Optional.of(directory.nodeId())).stream()
                            .map(this::nodeMap).toList();
                })
                .register("file", "readMetaData", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "file.readMetaData expects path and optional target user");
                    return nodeMap(requireNode(string(args.getFirst(), "file.readMetaData path"),
                            owner(args, 1)));
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
                    return writeText(string(args.getFirst(), "file.createFile path"),
                            content, false, owner(args, 2));
                })
                .register("file", "createDir", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "file.createDir expects path and optional target user");
                    return createDirectory(string(args.getFirst(), "file.createDir path"),
                            owner(args, 1));
                })
                .register("file", "removeFile", args -> remove(args, VfsNode.Type.FILE,
                        "file.removeFile"))
                .register("file", "removeDir", args -> remove(args, VfsNode.Type.DIRECTORY,
                        "file.removeDir"))
                .register("file", "rename", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "file.rename expects path, name, and optional target user");
                    UUID owner = owner(args, 2);
                    requireFileAccess(owner, Capability.VFS_WRITE);
                    VfsNode source = requireNode(string(args.get(0), "file.rename path"), owner);
                    String replacement = string(args.get(1), "file.rename name");
                    if (replacement.contains("/") || replacement.equals(".")
                            || replacement.equals("..")) {
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
                    return createContentNode(linkPath, target.getBytes(StandardCharsets.UTF_8),
                            VfsNode.Type.SYMLINK, false).nodeId().toString();
                })
                .register("file", "lock", args -> {
                    arity(args, 2, "file.lock");
                    VfsNode node = requireNode(string(args.get(0), "file.lock path"));
                    long lease = positiveMillis(args.get(1), "file.lock lease");
                    return transaction.vfs().acquireLock(node.nodeId(), process.ownerId(),
                                    process.identity().processUid(), process.executionEpoch(),
                                    now.plusMillis(lease), now)
                            .map(FclRuntimeFunctions::fileLockMap).orElse(null);
                })
                .register("file", "renewLock", args -> {
                    arity(args, 3, "file.renewLock");
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
                    VfsNode node = requireNode(string(args.get(0), "file.unlock path"));
                    return transaction.vfs().releaseLock(node.nodeId(), process.ownerId(),
                            process.identity().processUid(), process.executionEpoch(),
                            integer(args.get(1), "file.unlock token"));
                });

        registry.aliasQualified("file.read", "io", "readFile")
                .aliasQualified("file.write", "io", "writeFile");

    }

    private void registerProcesses() {
        registry.register("process", "getPID", args -> {
                    arity(args, 0, "process.getPID");
                    return process.identity().pid();
                })
                .register("process", "getPPID", args -> {
                    arity(args, 0, "process.getPPID");
                    return process.parentProcessUid()
                            .flatMap(transaction.processes()::findByUid)
                            .map(parent -> parent.identity().pid()).orElse(0L);
                })
                .register("process", "getListOfChildProcess", args -> {
                    arity(args, 0, "process.getListOfChildProcess");
                    return transaction.processes().findChildren(process.identity().processUid())
                            .stream().map(child -> child.identity().pid()).toList();
                })
                .register("process", "getList", args -> {
                    arity(args, 0, "process.getList");
                    return transaction.processes().findAll().stream()
                            .map(FclRuntimeFunctions::processMap).toList();
                })
                .aliasQualified("process.getList", "process", "getListOfProcess")
                .registerContextual("process", "kill", (args, invocation) -> {
                    arity(args, 1, "process.kill");
                    long pid = integer(args.getFirst(), "process.kill pid");
                    if (pid == process.identity().pid()) {
                        invocation.continuation().exit(null);
                        return true;
                    }
                    return terminate(pid);
                })
                .register("process", "pause", args -> changeProcess(
                        integerAt(args, 0, 1, "process.pause"), true))
                .register("process", "continue", args -> changeProcess(
                        integerAt(args, 0, 1, "process.continue"), false))
                .registerContextual("process", "fork", (args, invocation) -> {
                    arity(args, 0, "process.fork");
                    Authorization.require(transaction, process.ownerId(),
                            Capability.PROCESS_CREATE);
                    UUID childUid = UUID.randomUUID();
                    long childPid = transaction.processes().allocatePid();
                    FclContinuation childRuntime = invocation.continuation().snapshot();
                    childRuntime.cacheCallResult(invocation.expressionId(), 0L);
                    childRuntime.clearWait();
                    Continuation childContinuation = new FclPersistenceBridge(
                            new FclContinuationCodec()).persist(childUid, program,
                            process.continuation(), childRuntime);
                    CilProcess child = new CilProcess(new ProcessIdentity(childUid, childPid),
                            process.ownerId(), CilProcess.Status.READY, 0, 0, childContinuation,
                            Optional.of(process.identity().processUid()), now, now);
                    transaction.processes().insert(child);
                    transaction.scheduler().enqueue(
                            new com.follarce.domain.scheduler.SchedulerQueueEntry(childUid,
                                    now, now,
                                    com.follarce.domain.scheduler.SchedulerQueueEntry.Status.READY));
                    audit("process.fork", childUid, Map.of("pid", Long.toString(childPid)));
                    return childPid;
                })
                .registerContextual("process", "exec", (args, invocation) -> {
                    arity(args, 1, "process.exec");
                    Authorization.require(transaction, process.ownerId(),
                            Capability.PROCESS_CONTROL_OWN);
                    UUID programId = uuid(args.getFirst(), "process.exec program");
                    transaction.programs().findById(programId)
                            .orElseThrow(() -> new FclRuntimeException("Unknown program"));
                    invocation.continuation().waitFor("exec:" + programId, Map.of());
                    throw FclSuspension.suspend();
                })
                .registerContextual("process", "wait", (args, invocation) -> {
                    arity(args, 0, "process.wait");
                    Optional<CilProcess> active = transaction.processes()
                            .findChildren(process.identity().processUid()).stream()
                            .filter(child -> !child.isTerminal()).findFirst();
                    if (active.isEmpty()) return List.of();
                    return waitForProcess(active.orElseThrow(), invocation);
                })
                .registerContextual("process", "waitPID", (args, invocation) -> {
                    arity(args, 1, "process.waitPID");
                    CilProcess target = targetProcess(integer(args.getFirst(),
                            "process.waitPID pid"), "process.waitPID");
                    return waitForProcess(target, invocation);
                });
    }

    private void registerUsers() {
        registry.register("user", "getCurrentUser", args -> {
                    arity(args, 0, "user.getCurrentUser");
                    return process.ownerId().toString();
                })
                .register("user", "isLocal", args -> {
                    arity(args, 0, "user.isLocal");
                    return transaction.auth().capabilities(process.ownerId())
                            .contains(Capability.SYSTEM_ADMIN);
                })
                .register("user", "validateUser", args -> {
                    arity(args, 1, "user.validateUser");
                    String value = string(args.getFirst(), "user.validateUser identity");
                    try {
                        UUID identity = UUID.fromString(value);
                        if (identity.equals(process.ownerId())) return true;
                        if (!isAdministrator()) return false;
                        return transaction.auth().findUsersByAdministrator(process.ownerId())
                                .stream().anyMatch(user -> user.userId().equals(identity));
                    } catch (IllegalArgumentException ignored) {
                        if (!isAdministrator()) return false;
                        return transaction.auth().findUsersByAdministrator(process.ownerId())
                                .stream().anyMatch(user -> user.username().equalsIgnoreCase(value));
                    }
                })
                .register("user", "getListOfUsers", args -> {
                    arity(args, 0, "user.getListOfUsers");
                    return transaction.auth().findUsersByAdministrator(process.ownerId()).stream()
                            .map(FclRuntimeFunctions::userMap).toList();
                })
                .register("user", "createUser", args -> createUser(args))
                .register("user", "removeUser", args -> {
                    arity(args, 1, "user.removeUser");
                    UUID userId = uuid(args.getFirst(), "user.removeUser user");
                    return userMap(transaction.auth().disableUserByAdministrator(
                            process.ownerId(), userId, UUID.randomUUID(), now));
                })
                .register("user", "switchUser", args -> unavailable("user.switchUser",
                        "a durable process identity cannot be changed in place"));
    }

    private Object createUser(List<Object> args) {
        if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                "user.createUser expects username, password, and optional local admin password");
        String username = string(args.get(0), "user.createUser username");
        String password = string(args.get(1), "user.createUser password");
        Set<Capability> capabilities;
        if (args.size() == 3) {
            String adminPassword = string(args.get(2),
                    "user.createUser local admin password");
            if (passwordVerifier == null) throw new FclRuntimeException(
                    "user.createUser: password verifier is not available");
            char[] adminSecret = adminPassword.toCharArray();
            try {
                if (!passwordVerifier.verify(adminSecret)) {
                    throw new FclRuntimeException(
                            "Invalid local administrator password");
                }
            } finally {
                java.util.Arrays.fill(adminSecret, '\0');
            }
            capabilities = com.follarce.terminal.TerminalAccessService.ADMIN_CAPABILITIES;
        } else {
            capabilities = com.follarce.terminal.TerminalAccessService.USER_CAPABILITIES;
        }
        Optional<UserAccount> existing = transaction.auth()
                .findUsersByAdministrator(process.ownerId()).stream()
                .filter(user -> user.username().equalsIgnoreCase(username)).findFirst();
        if (existing.isPresent()) return userMap(existing.orElseThrow());
        char[] secret = password.toCharArray();
        try {
            return userMap(transaction.auth().createUserByAdministrator(process.ownerId(),
                    UUID.randomUUID(), username, secret, capabilities, UUID.randomUUID(), now));
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    private static Map<String, Object> userMap(UserAccount user) {
        return Map.of("userId", user.userId().toString(), "username", user.username(),
                "status", user.status().name(), "credentialVersion", user.credentialVersion());
    }

    private static Map<String, Object> processMap(CilProcess process) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", process.identity().pid());
        result.put("processUid", process.identity().processUid().toString());
        result.put("ownerId", process.ownerId().toString());
        result.put("status", process.status().name());
        result.put("stateVersion", process.stateVersion());
        result.put("executionEpoch", process.executionEpoch());
        result.put("programId", process.continuation().programId().toString());
        result.put("programCounter", process.continuation().programCounter());
        result.put("updatedAt", process.updatedAt().toString());
        process.parentProcessUid().ifPresent(parent -> result.put("parentProcessUid",
                parent.toString()));
        return Map.copyOf(result);
    }

    private void registerNetworkAndSockets() {
        registry.registerContextual("network", "httpGet", (args, invocation) -> {
                    arity(args, 1, "network.httpGet");
                    String url = string(args.getFirst(), "network.httpGet url");
                    return external(invocation, "network.http-get", Map.of("url", url),
                            idempotentPolicy(invocation, "GET:" + url), true);
                }, "webget")
                .registerContextual("network", "httpPost", (args, invocation) -> {
                    arity(args, 2, "network.httpPost");
                    return external(invocation, "network.http-post", Map.of(
                            "url", string(args.get(0), "network.httpPost url"),
                            "body", display(args.get(1))), MANUAL_EFFECT, true);
                }, "webpost")
                .registerContextual("network", "download", this::download);
        for (String name : List.of("connect", "send", "receive", "close", "bind", "accept")) {
            registry.registerContextual("socket", name, (args, invocation) ->
                    external(invocation, "socket." + name,
                            Map.of("arguments", List.copyOf(args)), MANUAL_EFFECT, true));
        }
    }

    private void registerPackages() {
        registry.register("package", "info", args -> {
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
                    return packageMap(requirePackage(args, "package.info"));
                })
                .register("package", "list", args -> {
                    arity(args, 0, "package.list");
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
                    return transaction.packages().findReleases().stream()
                            .map(FclRuntimeFunctions::packageMap).toList();
                })
                .register("package", "install", args -> installPackage(args))
                .register("package", "build", args -> buildPackage(args))
                .register("package", "run", args -> runPackage(args))
                .register("package", "createEnvironment", args -> {
                    arity(args, 1, "package.createEnvironment");
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
                    String name = string(args.getFirst(), "package environment name");
                    PackageEnvironment existing = transaction.packages()
                            .findEnvironmentByName(name).orElse(null);
                    if (existing != null) return environmentMap(existing);
                    PackageEnvironment environment = new PackageEnvironment(UUID.randomUUID(),
                            process.ownerId(), name, Optional.empty(),
                            PackageEnvironment.Status.ACTIVE, now);
                    transaction.packages().saveEnvironment(environment);
                    return environmentMap(environment);
                })
                .register("package", "environments", args -> {
                    arity(args, 0, "package.environments");
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
                    return transaction.packages().findEnvironments().stream()
                            .map(FclRuntimeFunctions::environmentMap).toList();
                })
                .register("package", "verify", args -> {
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
                    PackageRelease release = requirePackage(args, "package.verify");
                    StoredObject object = transaction.vfs().findObject(release.databaseObjectHash())
                            .orElseThrow(() -> new FclRuntimeException(
                                    "Package database object is missing"));
                    boolean hashMatches = ObjectHash.sha256(object.content())
                            .equals(release.databaseFileHash());
                    return Map.of("valid", hashMatches
                                    && release.signatureStatus() != PackageRelease.SignatureStatus.INVALID
                                    && release.signatureStatus() != PackageRelease.SignatureStatus.REVOKED,
                            "hashMatches", hashMatches,
                            "signatureStatus", release.signatureStatus().name());
                })
                .register("package", "resource", args -> {
                    arity(args, 2, "package.resource");
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
                    PackageRelease release = requirePackage(List.of(args.getFirst()),
                            "package.resource");
                    StoredObject object = transaction.vfs().findObject(release.databaseObjectHash())
                            .orElseThrow(() -> new FclRuntimeException(
                                    "Package database object is missing"));
                    byte[] content = new SqlitePackageReader().readResource(object.content().bytes(),
                            string(args.get(1), "package.resource path"));
                    return new String(content, StandardCharsets.UTF_8);
                })
                .register("package", "pin", args -> pinPackage(args))
                .register("package", "unpin", args -> {
                    arity(args, 2, "package.unpin");
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
                    return transaction.packages().deleteBinding(
                            uuid(args.get(0), "package environment"),
                            string(args.get(1), "package binding"));
                })
                .register("package", "remove", args -> {
                    arity(args, 2, "package.remove");
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
                    return transaction.packages().deleteBinding(
                            uuid(args.get(0), "package environment"),
                            string(args.get(1), "package binding"));
                })
                .register("package", "gc", args -> {
                    arity(args, 0, "package.gc");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    // Releases and content are immutable authorities; no reachable data is deleted.
                    return 0L;
                })
                .register("package", "recover", args -> {
                    arity(args, 0, "package.recover");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    return true;
                });
    }

    private Object installPackage(List<Object> args) {
        if (args.isEmpty() || args.size() > 3) throw new FclRuntimeException(
                "package.install expects a VFS path and optional binding arguments");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
        VfsNode node = requireNode(string(args.getFirst(), "package.install path"));
        requireType(node, VfsNode.Type.FILE, "package.install");
        StoredObject database = transaction.vfs().findObject(node.currentObjectHash().orElseThrow())
                .orElseThrow(() -> new FclRuntimeException("Package source bytes are missing"));
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(database.content().bytes());
        ObjectHash fileHash = new ObjectHash(descriptor.databaseFileHash());
        if (!fileHash.equals(database.objectHash())) {
            throw new FclRuntimeException("Package database hash changed during inspection");
        }
        PackageRelease release = new PackageRelease(new PackageRelease.Coordinate(
                descriptor.namespace(), descriptor.name(), descriptor.version()),
                new PackageRelease.Hash(new ObjectHash(descriptor.packageHash())), fileHash,
                fileHash, PackageRelease.SignatureStatus.UNSIGNED, now);
        PackageIndex index = new PackageIndex(release, descriptor.moduleIndex(),
                descriptor.dependencyIndex(), descriptor.entrypoints(), descriptor.exports(),
                descriptor.capabilityIndex());
        var result = transaction.packages().registerRelease(index);
        if (result == com.follarce.domain.port.PackageRepository.ReleaseWriteResult.COORDINATE_CONFLICT) {
            throw new PackageCoordinateConflictException(release.coordinate());
        }
        UUID environmentId;
        String binding;
        if (args.size() == 3) {
            Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
            environmentId = uuid(args.get(1), "package environment");
            binding = string(args.get(2), "package binding");
            transaction.packages().findEnvironment(environmentId)
                    .orElseThrow(() -> new FclRuntimeException("Unknown package environment"));
        } else {
            Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
            PackageEnvironment environment = PackageEnvironments.ensureDefault(
                    transaction.packages(), process.ownerId(), now);
            environmentId = environment.environmentId();
            binding = args.size() == 2
                    ? string(args.get(1), "package binding") : descriptor.name();
        }
        transaction.packages().saveBinding(new PackageBinding(environmentId, binding,
                release.packageHash(), now));
        audit("package.install", node.nodeId(), Map.of(
                "coordinate", release.coordinate().key(), "writeResult", result.name(),
                "environmentId", environmentId.toString(), "binding", binding));
        Map<String, Object> installed = new LinkedHashMap<>(packageMap(release));
        installed.put("environmentId", environmentId.toString());
        installed.put("binding", binding);
        return Map.copyOf(installed);
    }

    private Object buildPackage(List<Object> args) {
        arity(args, 2, "package.build");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
        Authorization.require(transaction, process.ownerId(), Capability.VFS_READ);
        Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
        String manifestPath = normalize(string(args.get(0), "package manifest path"));
        String outputPath = normalize(string(args.get(1), "package output path"));
        byte[] manifest = readBytes(manifestPath);
        PackageBuilder builder = new PackageBuilder();
        var parsed = builder.parseManifest(manifest);
        int separator = manifestPath.lastIndexOf('/');
        String directory = separator <= 0 ? "/" : manifestPath.substring(0, separator);
        byte[] database = builder.build(parsed,
                path -> readBytes(normalize(directory + "/" + path)));
        String nodeId = writeBinary(outputPath, database, "application/vnd.sqlite3");
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(database);
        return Map.of("nodeId", nodeId, "path", outputPath,
                "coordinate", descriptor.coordinate(), "packageHash", descriptor.packageHash(),
                "databaseFileHash", descriptor.databaseFileHash());
    }

    private Object runPackage(List<Object> args) {
        if (args.isEmpty() || args.size() > 2) {
            throw new FclRuntimeException("package.run expects binding and optional entrypoint");
        }
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        Authorization.require(transaction, process.ownerId(), Capability.PROCESS_CREATE);
        String bindingName = string(args.getFirst(), "package binding");
        String entrypointName = args.size() == 2
                ? string(args.get(1), "package entrypoint") : "run";
        PackageEnvironment environment = PackageEnvironments.ensureDefault(
                transaction.packages(), process.ownerId(), now);
        PackageBinding binding = transaction.packages().findBinding(environment.environmentId(),
                        bindingName)
                .orElseThrow(() -> new FclRuntimeException("Package is not installed: "
                        + bindingName));
        PackageRelease release = transaction.packages().findRelease(binding.packageHash())
                .orElseThrow(() -> new FclRuntimeException("Installed package release is missing"));
        StoredObject database = transaction.vfs().findObject(release.databaseObjectHash())
                .orElseThrow(() -> new FclRuntimeException("Installed package database is missing"));
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(database.content().bytes());
        PackageIndex.Entrypoint entrypoint = descriptor.entrypoints().stream()
                .filter(value -> value.name().equals(entrypointName)).findFirst()
                .orElseThrow(() -> new FclRuntimeException("Unknown package entrypoint: "
                        + entrypointName));
        String source = "import \"" + escapeFcl(bindingName) + "\" as __package_entry\n"
                + "return __package_entry." + entrypoint.name() + "()\n";
        Program entryProgram = createProgram(source);
        long pid = transaction.processes().allocatePid();
        UUID processUid = UUID.randomUUID();
        Map<String, ObjectHash> packageBindings = Map.of(bindingName,
                binding.packageHash().value());
        Continuation continuation = new Continuation(entryProgram.programId(),
                entryProgram.programHash(), 0, List.of(), List.of(), List.of(), List.of(),
                Optional.empty(), Map.of(), packageBindings, entryProgram.languageVersion(),
                Integer.toString(entryProgram.runtimeFormatVersion()));
        CilProcess child = new CilProcess(new ProcessIdentity(processUid, pid), process.ownerId(),
                CilProcess.Status.READY, 0, 0, continuation,
                Optional.of(process.identity().processUid()), now, now);
        transaction.processes().insert(child);
        transaction.packages().saveProcessBinding(new ProcessPackageBinding(processUid,
                bindingName, environment.environmentId(), binding.packageHash(), now));
        transaction.scheduler().enqueue(new com.follarce.domain.scheduler.SchedulerQueueEntry(
                processUid, now, now,
                com.follarce.domain.scheduler.SchedulerQueueEntry.Status.READY));
        audit("package.run", processUid, Map.of("pid", Long.toString(pid),
                "binding", bindingName, "entrypoint", entrypointName,
                "coordinate", release.coordinate().key()));
        return Map.of("pid", pid, "processUid", processUid.toString(),
                "programId", entryProgram.programId().toString(),
                "coordinate", release.coordinate().key(), "entrypoint", entrypointName);
    }

    private Program createProgram(String source) {
        FclProgram compiled = new FclCompiler().compile(source);
        StoredObject sourceObject = StoredObject.create(new BinaryContent(
                source.getBytes(StandardCharsets.UTF_8)), ProgramService.SOURCE_MEDIA_TYPE, now);
        StoredObject compiledObject = StoredObject.create(new BinaryContent(
                new FclProgramCodec().toJson(compiled).getBytes(StandardCharsets.UTF_8)),
                ProgramService.COMPILED_MEDIA_TYPE, now);
        transaction.vfs().saveObject(sourceObject);
        transaction.vfs().saveObject(compiledObject);
        int statements = Math.toIntExact(compiled.instructions().stream()
                .filter(instruction -> !(instruction instanceof FclInstruction.Jump)).count());
        return transaction.programs().saveIfAbsent(new Program(UUID.randomUUID(),
                sourceObject.objectHash(), ProgramService.LANGUAGE_VERSION,
                FclProgramCodec.FORMAT_VERSION, sourceObject.objectHash(),
                Optional.of(compiledObject.objectHash()), statements, now));
    }

    private Object pinPackage(List<Object> args) {
        if (args.size() != 3 && args.size() != 5) throw new FclRuntimeException(
                "package.pin expects environment, binding, coordinate or hash");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        UUID environment = uuid(args.get(0), "package environment");
        String binding = string(args.get(1), "package binding");
        PackageRelease release = requirePackage(args.subList(2, args.size()), "package.pin");
        transaction.packages().saveBinding(new PackageBinding(environment, binding,
                release.packageHash(), now));
        return packageMap(release);
    }

    private PackageRelease requirePackage(List<Object> args, String function) {
        Optional<PackageRelease> release;
        if (args.size() == 1) {
            String identity = string(args.getFirst(), function + " package");
            if (identity.matches("[0-9a-fA-F]{64}")) {
                release = transaction.packages().findRelease(
                        new PackageRelease.Hash(new ObjectHash(identity.toLowerCase(
                                java.util.Locale.ROOT))));
            } else {
                String[] coordinate = identity.split("/", 3);
                if (coordinate.length != 3) throw new FclRuntimeException(
                        function + " requires namespace/name/version or a package hash");
                release = transaction.packages().findRelease(new PackageRelease.Coordinate(
                        coordinate[0], coordinate[1], coordinate[2]));
            }
        } else if (args.size() == 3) {
            release = transaction.packages().findRelease(new PackageRelease.Coordinate(
                    string(args.get(0), "package namespace"),
                    string(args.get(1), "package name"),
                    string(args.get(2), "package version")));
        } else {
            throw new FclRuntimeException(function + " expects one or three package arguments");
        }
        return release.orElseThrow(() -> new FclRuntimeException("Unknown package release"));
    }

    private static Map<String, Object> packageMap(PackageRelease release) {
        return Map.of("coordinate", release.coordinate().key(),
                "hash", release.packageHash().value().value(),
                "signatureStatus", release.signatureStatus().name(),
                "importedAt", release.importedAt().toString());
    }

    private static Map<String, Object> environmentMap(PackageEnvironment environment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("environmentId", environment.environmentId().toString());
        result.put("name", environment.name());
        result.put("status", environment.status().name());
        result.put("createdAt", environment.createdAt().toString());
        environment.parentEnvironmentId().ifPresent(parent -> result.put(
                "parentEnvironmentId", parent.toString()));
        return Map.copyOf(result);
    }

    private static String escapeFcl(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private void registerSwapPool() {
        registry.register("swapPool", "create", args -> {
                    String pool = path(args, 0, 1, "swapPool.create");
                    return transaction.ipc().createSwapPool(process.ownerId(),
                            process.identity().processUid(), pool, now);
                })
                .register("swapPool", "remove", args -> {
                    String pool = path(args, 0, 1, "swapPool.remove");
                    return transaction.ipc().removeSwapPool(process.ownerId(),
                            process.identity().processUid(), pool);
                })
                .register("swapPool", "exists", args -> transaction.ipc().swapPoolExists(
                        process.ownerId(), path(args, 0, 1, "swapPool.exists")))
                .register("swapPool", "list", args -> {
                    arity(args, 0, "swapPool.list");
                    return transaction.ipc().findSwapPools(process.ownerId());
                })
                .register("swapPool", "ls", args -> transaction.ipc().findSwapVariables(
                        process.ownerId(), path(args, 0, 1, "swapPool.ls")))
                .register("swapPool", "add", args -> addSwapValue(args))
                .register("swapPool", "get", args -> {
                    arity(args, 2, "swapPool.get");
                    return transaction.ipc().consumeSwapValue(process.ownerId(),
                                    string(args.get(0), "swapPool.get pool"),
                                    string(args.get(1), "swapPool.get variable"), now)
                            .map(value -> codec.valueFromJson(value.canonicalPayload()))
                            .orElse(null);
                })
                .register("swapPool", "update", args -> {
                    if (args.size() < 3 || args.size() > 4) throw new FclRuntimeException(
                            "swapPool.update expects three or four arguments");
                    return transaction.ipc().updateSwapValue(process.ownerId(),
                            string(args.get(0), "swapPool.update pool"),
                            string(args.get(1), "swapPool.update variable"), typed(args.get(2)),
                            process.identity().processUid(), process.executionEpoch(),
                            args.size() == 4 ? Optional.of(integer(args.get(3),
                                    "swapPool.update fencing token")) : Optional.empty(), now);
                })
                .register("swapPool", "removeVar", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "swapPool.removeVar expects two or three arguments");
                    return transaction.ipc().removeSwapValue(process.ownerId(),
                            string(args.get(0), "swapPool.removeVar pool"),
                            string(args.get(1), "swapPool.removeVar variable"),
                            process.identity().processUid(), process.executionEpoch(),
                            args.size() == 3 ? Optional.of(integer(args.get(2),
                                    "swapPool.removeVar fencing token")) : Optional.empty());
                })
                .register("swapPool", "clear", args -> transaction.ipc().clearSwapPool(
                        process.ownerId(), path(args, 0, 1, "swapPool.clear"),
                        process.identity().processUid()))
                .register("swapPool", "lock", args -> {
                    arity(args, 3, "swapPool.lock");
                    long leaseMillis = positiveMillis(args.get(2), "swapPool.lock lease");
                    return transaction.ipc().acquireSwapLock(process.ownerId(),
                                    string(args.get(0), "swapPool.lock pool"),
                                    string(args.get(1), "swapPool.lock variable"),
                                    process.identity().processUid(), process.executionEpoch(),
                                    now.plusMillis(leaseMillis), now)
                            .map(FclRuntimeFunctions::lockMap).orElse(null);
                })
                .register("swapPool", "renewLock", args -> {
                    arity(args, 4, "swapPool.renewLock");
                    long token = integer(args.get(2), "swapPool.renewLock token");
                    long leaseMillis = positiveMillis(args.get(3), "swapPool.renewLock lease");
                    return transaction.ipc().renewSwapLock(process.ownerId(),
                                    string(args.get(0), "swapPool.renewLock pool"),
                                    string(args.get(1), "swapPool.renewLock variable"),
                                    process.identity().processUid(), process.executionEpoch(), token,
                                    now.plusMillis(leaseMillis), now)
                            .map(FclRuntimeFunctions::lockMap).orElse(null);
                })
                .register("swapPool", "unlock", args -> {
                    arity(args, 3, "swapPool.unlock");
                    return transaction.ipc().releaseSwapLock(process.ownerId(),
                            string(args.get(0), "swapPool.unlock pool"),
                            string(args.get(1), "swapPool.unlock variable"),
                            process.identity().processUid(), process.executionEpoch(),
                            integer(args.get(2), "swapPool.unlock token"));
                })
                .register("swapPool", "signal", args -> {
                    arity(args, 2, "swapPool.signal");
                    return transaction.ipc().signalSwapValue(process.ownerId(),
                            string(args.get(0), "swapPool.signal pool"),
                            string(args.get(1), "swapPool.signal variable"), now);
                })
                .registerContextual("swapPool", "waitFor", (args, invocation) ->
                        waitForSwap(args, invocation));
    }

    private Object addSwapValue(List<Object> args) {
        if (args.size() < 2) throw new FclRuntimeException(
                "swapPool.add expects at least data and pool arguments");
        String data = string(args.get(0), "swapPool.add data");
        int separator = data.indexOf(':');
        if (separator < 1) throw new FclRuntimeException(
                "swapPool.add data must use variable:value format");
        String variable = data.substring(0, separator);
        Object value = data.substring(separator + 1);
        String pool = string(args.get(1), "swapPool.add pool");
        String mode = "ALWAYS";
        Optional<Integer> remaining = Optional.empty();
        for (int index = 2; index < args.size(); index++) {
            String parameter = string(args.get(index), "swapPool.add option");
            if (parameter.equalsIgnoreCase("type:sync")) mode = "SYNC";
            if (parameter.toLowerCase(java.util.Locale.ROOT).startsWith("type:times(")) {
                int close = parameter.lastIndexOf(')');
                if (close < 12) throw new FclRuntimeException("Invalid times retention option");
                int count;
                try {
                    count = Integer.parseInt(parameter.substring(11, close));
                } catch (NumberFormatException failure) {
                    throw new FclRuntimeException("Invalid times retention count", failure);
                }
                if (count < 1) throw new FclRuntimeException(
                        "times retention count must be positive");
                mode = "TIMES";
                remaining = Optional.of(count);
            }
        }
        return transaction.ipc().addSwapValue(process.ownerId(), pool, variable, typed(value),
                mode, remaining, now);
    }

    private Object waitForSwap(List<Object> args, FclFunctionRegistry.Invocation invocation) {
        arity(args, 2, "swapPool.waitFor");
        String pool = string(args.get(0), "swapPool.waitFor pool");
        String variable = string(args.get(1), "swapPool.waitFor variable");
        if (invocation.continuation().scope().contains(ProcessInbox.TIMER_RESULT)) {
            invocation.continuation().scope().remove(ProcessInbox.TIMER_RESULT);
        }
        if (transaction.ipc().consumeSwapSignal(process.ownerId(), pool, variable)) return true;
        UUID timerId = UUID.randomUUID();
        transaction.timers().save(new ProcessTimer(timerId, process.identity().processUid(),
                now.plusMillis(50), ProcessTimer.Status.SCHEDULED, now, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(typed(null))));
        invocation.continuation().waitFor("timer:" + timerId,
                Map.of("swapPool", pool, "variable", variable));
        throw FclSuspension.suspend();
    }

    private static long positiveMillis(Object value, String field) {
        long millis = integer(value, field);
        if (millis < 1) throw new FclRuntimeException(field + " must be positive");
        return millis;
    }

    private static Map<String, Object> lockMap(
            com.follarce.domain.port.IpcRepository.SwapLock lock) {
        return Map.of("fencingToken", lock.fencingToken(),
                "leaseUntil", lock.leaseUntil().toString());
    }

    private static Map<String, Object> fileLockMap(
            com.follarce.domain.port.VfsRepository.FileLock lock) {
        return Map.of("fencingToken", lock.fencingToken(),
                "leaseUntil", lock.leaseUntil().toString());
    }

    private void registerSystem() {
        registry.register("system", "ls", args -> {
                    if (args.isEmpty()) return new ArrayList<>(registry.qualifiedNames());
                    arity(args, 1, "system.ls");
                    VfsNode directory = requireNode(string(args.getFirst(), "system.ls path"));
                    requireType(directory, VfsNode.Type.DIRECTORY, "system.ls");
                    return transaction.vfs().findChildren(process.ownerId(),
                                    Optional.of(directory.nodeId())).stream()
                            .map(this::nodeMap).toList();
                })
                .aliasQualified("process.kill", "system", "kill")
                .register("system", "resolveEffect", args -> unavailable("system.resolveEffect",
                        "manual effect resolution belongs to the administrator control plane"))
                .registerContextual("system", "exec", (args, invocation) -> {
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    if (args.size() != 1) throw new FclRuntimeException(
                            "system.exec expects a command string or argument array");
                    Object command = args.getFirst();
                    if (!(command instanceof String) && !(command instanceof List<?>)) {
                        throw new FclRuntimeException(
                                "system.exec command must be a string or argument array");
                    }
                    return external(invocation, "system.exec", Map.of("command", command),
                            MANUAL_EFFECT, true);
                })
                .registerContextual("system", "invoke", (args, invocation) -> {
                    if (args.isEmpty() || args.size() > 2) throw new FclRuntimeException(
                            "system.invoke expects a qualified FCL function and optional array");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    String function = string(args.getFirst(), "system.invoke function");
                    if (function.equals("system.invoke")) {
                        throw new FclRuntimeException("system.invoke cannot invoke itself");
                    }
                    List<Object> arguments;
                    if (args.size() == 1) arguments = List.of();
                    else if (args.get(1) instanceof List<?> supplied) {
                        arguments = new ArrayList<>(supplied);
                    } else {
                        throw new FclRuntimeException("system.invoke arguments must be an array");
                    }
                    return registry.invoke(function, arguments, invocation);
                })
                .register("system", "forceRemove", args -> {
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    if (args.size() == 1) {
                        VfsNode node = requireNode(string(args.getFirst(),
                                "system.forceRemove path"));
                        return deletePath(string(args.getFirst(), "system.forceRemove path"),
                                node.type());
                    }
                    if (args.size() == 2) {
                        return transaction.vfs().deleteByAdministrator(process.ownerId(),
                                uuid(args.get(0), "target user"), uuid(args.get(1), "node"),
                                UUID.randomUUID(), now);
                    }
                    throw new FclRuntimeException(
                            "system.forceRemove expects path or target-user/node IDs");
                })
                .register("system", "extensions", args -> {
                    arity(args, 0, "system.extensions");
                    return extensions.descriptors().stream().map(descriptor -> Map.of(
                            "id", descriptor.id(),
                            "version", descriptor.version(),
                            "description", descriptor.description())).toList();
                })
                .register("system", "reset", args -> unavailable("system.reset",
                        "runtime reset requires the administrator control plane"));
    }

    private Object terminalInput(FclFunctionRegistry.Invocation invocation, boolean oneCharacter) {
        return terminalInput(invocation, oneCharacter, false);
    }

    private Object terminalInput(FclFunctionRegistry.Invocation invocation, boolean oneCharacter,
                                 boolean rawKey) {
        FclContinuation continuation = invocation.continuation();
        if (continuation.scope().contains(ProcessInbox.TERMINAL_INPUT)) {
            String input = display(continuation.scope().remove(ProcessInbox.TERMINAL_INPUT));
            return oneCharacter ? (input.isEmpty() ? "" : input.substring(0, 1)) : input;
        }
        continuation.waitFor(rawKey ? "input:key" : "input",
                Map.of("readChar", oneCharacter, "rawKey", rawKey));
        throw FclSuspension.suspend();
    }

    private Object external(FclFunctionRegistry.Invocation invocation, String effectType,
                            Map<String, Object> payload, EffectRequest.Policy policy,
                            boolean returnValue) {
        FclContinuation continuation = invocation.continuation();
        if (continuation.scope().contains(ProcessInbox.EFFECT_RESULT)) {
            Object delivered = continuation.scope().remove(ProcessInbox.EFFECT_RESULT);
            if (!(delivered instanceof Map<?, ?> result)
                || !Boolean.TRUE.equals(result.get("ok"))) {
                throw new FclRuntimeException("External effect failed: " + display(delivered));
            }
            return returnValue ? result.get("value") : null;
        }
        Authorization.require(transaction, process.ownerId(), Capability.EFFECT_REQUEST);
        UUID effectId = UUID.randomUUID();
        transaction.effects().save(EffectRequest.prepare(effectId,
                process.identity().processUid(), effectType, typed(payload), policy, now));
        continuation.waitFor("effect:" + effectId, Map.of("effectType", effectType));
        audit("effect.request", effectId, Map.of("effectType", effectType));
        throw FclSuspension.suspend();
    }

    private Object download(List<Object> args, FclFunctionRegistry.Invocation invocation) {
        arity(args, 2, "network.download");
        Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
        String url = string(args.get(0), "network.download url");
        String path = string(args.get(1), "network.download destination");
        FclScope scope = invocation.continuation().scope();
        String state = "cilexec.download." + invocation.expressionId() + ".";
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
        if (status == 416 && complete && total == offset && currentHash.isPresent()) {
            if (mediaType == null) mediaType = "application/octet-stream";
            clearDownloadState(scope, state);
            String nodeId = attachDownloadedObject(path, currentHash.orElseThrow(), mediaType,
                    offset, "network.download");
            return Map.of("nodeId", nodeId, "path", normalize(path), "url", url,
                    "status", 206L, "bytes", offset, "mediaType", mediaType);
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
            return Map.of("nodeId", nodeId, "path", normalize(path), "url", url,
                    "status", status, "bytes", downloaded, "mediaType", mediaType);
        }

        scope.put(state + "offset", downloaded);
        scope.put(state + "hash", nextHash.value());
        scope.put(state + "mediaType", mediaType);
        if (response.get("validator") instanceof String validator && !validator.isBlank()) {
            scope.put(state + "validator", validator);
        }
        return download(args, invocation);
    }

    private static void clearDownloadState(FclScope scope, String prefix) {
        for (String suffix : List.of("offset", "hash", "mediaType", "validator")) {
            String key = prefix + suffix;
            if (scope.contains(key)) scope.remove(key);
        }
    }

    private EffectRequest.Policy idempotentPolicy(FclFunctionRegistry.Invocation invocation,
                                                   String operation) {
        String key = process.identity().processUid() + ":" + process.executionEpoch() + ":"
                + invocation.expressionId() + ":" + operation;
        return new EffectRequest.Policy(true, Optional.of(key), false, true,
                EffectRequest.UnknownAction.RETRY_IDEMPOTENT);
    }

    private Continuation.PersistedValue typed(Object value) {
        return new Continuation.PersistedValue(codec.valueType(value), codec.valueToJson(value));
    }

    private String readText(String path) {
        return readText(path, process.ownerId());
    }

    private String readText(String path, UUID owner) {
        return new String(readBytes(path, owner), StandardCharsets.UTF_8);
    }

    private byte[] readBytes(String path) {
        return readBytes(path, process.ownerId());
    }

    private byte[] readBytes(String path, UUID owner) {
        requireFileAccess(owner, Capability.VFS_READ);
        VfsNode node = requireNode(path, owner);
        if (node.type() != VfsNode.Type.FILE && node.type() != VfsNode.Type.SYMLINK) {
            throw new FclRuntimeException("Path is not a file: " + path);
        }
        ObjectHash hash = node.currentObjectHash().orElseThrow();
        if (!owner.equals(process.ownerId())) {
            transaction.vfs().readFileByAdministrator(process.ownerId(), owner,
                    node.nodeId(), UUID.randomUUID(), now);
        }
        long size = transaction.vfs().logicalObjectSize(hash);
        if (size > Integer.MAX_VALUE) throw new FclRuntimeException(
                "File is too large for one FCL string; use file.readChunk");
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream((int) size);
        long offset = 0;
        while (offset < size) {
            int request = (int) Math.min(64L * 1024 * 1024, size - offset);
            byte[] chunk = transaction.vfs().readObjectRange(hash, offset, request);
            if (chunk.length == 0) throw new FclRuntimeException(
                    "File content ended before its declared size: " + path);
            result.writeBytes(chunk);
            offset += chunk.length;
        }
        return result.toByteArray();
    }

    private byte[] readRange(String path, long offset, int maximum, UUID owner) {
        requireFileAccess(owner, Capability.VFS_READ);
        VfsNode node = requireNode(path, owner);
        requireType(node, VfsNode.Type.FILE, "file.readChunk");
        if (!owner.equals(process.ownerId())) {
            transaction.vfs().readFileByAdministrator(process.ownerId(), owner,
                    node.nodeId(), UUID.randomUUID(), now);
        }
        return transaction.vfs().readObjectRange(node.currentObjectHash().orElseThrow(),
                offset, maximum);
    }

    private String writeText(String source, String content, boolean append) {
        return writeText(source, content, append, process.ownerId());
    }

    private String writeText(String source, String content, boolean append, UUID owner) {
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
        if (append) {
            if (!owner.equals(process.ownerId())) {
                transaction.vfs().readFileByAdministrator(process.ownerId(), owner,
                        current.nodeId(), UUID.randomUUID(), now);
            }
        }
        StoredObject object = append
                ? transaction.vfs().appendChunkedObject(
                current.currentObjectHash().orElseThrow(), bytes, TEXT, now)
                : StoredObject.create(new BinaryContent(bytes), TEXT, now);
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

    private String writeBinary(String source, byte[] bytes, String mediaType) {
        return writeBinary(source, bytes, mediaType, "package.build");
    }

    private String writeBinary(String source, byte[] bytes, String mediaType, String operation) {
        Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
        String path = normalize(source);
        Optional<VfsNode> existing = resolve(path);
        if (existing.isEmpty()) {
            return createContentNode(path, bytes, VfsNode.Type.FILE, false, mediaType)
                    .nodeId().toString();
        }
        VfsNode current = existing.orElseThrow();
        requireType(current, VfsNode.Type.FILE, operation);
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

    /** Publishes an already persisted logical object only after every download chunk arrived. */
    private String attachDownloadedObject(String source, ObjectHash objectHash, String mediaType,
                                          long byteSize, String operation) {
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

    private VfsNode createContentNode(String source, byte[] bytes, VfsNode.Type type,
                                      boolean revisions) {
        return createContentNode(source, bytes, type, revisions, TEXT);
    }

    private VfsNode createContentNode(String source, byte[] bytes, VfsNode.Type type,
                                      boolean revisions, String mediaType) {
        return createContentNode(source, bytes, type, revisions, mediaType, process.ownerId());
    }

    private VfsNode createContentNode(String source, byte[] bytes, VfsNode.Type type,
                                      boolean revisions, String mediaType, UUID owner) {
        requireFileAccess(owner, Capability.VFS_WRITE);
        ParentAndName parent = parentAndName(source, owner);
        if (transaction.vfs().findChild(owner, Optional.of(parent.parent().nodeId()),
                parent.name()).isPresent()) {
            throw new FclRuntimeException("Path already exists: " + normalize(source));
        }
        StoredObject object = StoredObject.create(new BinaryContent(bytes), mediaType, now);
        if (!owner.equals(process.ownerId())) {
            if (type != VfsNode.Type.FILE) throw new FclRuntimeException(
                    "Cross-user content creation supports files only");
            return transaction.vfs().createFileByAdministrator(process.ownerId(), owner,
                    UUID.randomUUID(), parent.parent().nodeId(), parent.name(), object, revisions,
                    UUID.randomUUID(), UUID.randomUUID(), now);
        }
        transaction.vfs().saveObject(object);
        VfsNode node = new VfsNode(UUID.randomUUID(), Optional.of(parent.parent().nodeId()),
                owner, parent.name(), type, Optional.of(object.objectHash()), Set.of(),
                revisions, now, now);
        transaction.vfs().insertNode(node);
        if (revisions) {
            transaction.vfs().appendRevision(UUID.randomUUID(), node.nodeId(), owner,
                    object.objectHash(), process.ownerId(), now);
        }
        audit("vfs.file.create", node.nodeId(), Map.of("path", normalize(source)));
        return node;
    }

    private String createDirectory(String source) {
        return createDirectory(source, process.ownerId());
    }

    private String createDirectory(String source, UUID owner) {
        requireFileAccess(owner, Capability.VFS_WRITE);
        ParentAndName parent = parentAndName(source, owner);
        if (transaction.vfs().findChild(owner, Optional.of(parent.parent().nodeId()),
                parent.name()).isPresent()) {
            throw new FclRuntimeException("Path already exists: " + normalize(source));
        }
        if (!owner.equals(process.ownerId())) {
            return transaction.vfs().createDirectoryByAdministrator(process.ownerId(), owner,
                    UUID.randomUUID(), parent.parent().nodeId(), parent.name(), UUID.randomUUID(), now)
                    .nodeId().toString();
        }
        VfsNode node = new VfsNode(UUID.randomUUID(), Optional.of(parent.parent().nodeId()),
                owner, parent.name(), VfsNode.Type.DIRECTORY, Optional.empty(),
                Set.of(), false, now, now);
        transaction.vfs().insertNode(node);
        audit("vfs.directory.create", node.nodeId(), Map.of("path", normalize(source)));
        return node.nodeId().toString();
    }

    private boolean deletePath(String source, VfsNode.Type expected) {
        return deletePath(source, expected, process.ownerId());
    }

    private boolean deletePath(String source, VfsNode.Type expected, UUID owner) {
        requireFileAccess(owner, Capability.VFS_WRITE);
        VfsNode node = requireNode(source, owner);
        requireType(node, expected, "file.remove");
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

    private ParentAndName parentAndName(String source) {
        return parentAndName(source, process.ownerId());
    }

    private ParentAndName parentAndName(String source, UUID owner) {
        String normalized = normalize(source);
        if (normalized.equals("/")) throw new FclRuntimeException("Root path cannot be changed");
        int separator = normalized.lastIndexOf('/');
        String parentPath = separator <= 0 ? "/" : normalized.substring(0, separator);
        String name = normalized.substring(separator + 1);
        VfsNode parent = requireNode(parentPath, owner);
        requireType(parent, VfsNode.Type.DIRECTORY, "file parent");
        return new ParentAndName(parent, name);
    }

    private Optional<VfsNode> resolve(String source) {
        return resolve(source, process.ownerId());
    }

    private Optional<VfsNode> resolve(String source, UUID owner) {
        String path = normalize(source);
        Optional<VfsNode> current = transaction.vfs().findChild(owner,
                Optional.empty(), "/");
        if (path.equals("/")) return current;
        for (String part : path.substring(1).split("/")) {
            if (current.isEmpty() || current.get().type() != VfsNode.Type.DIRECTORY) {
                return Optional.empty();
            }
            current = transaction.vfs().findChild(owner,
                    Optional.of(current.get().nodeId()), part);
        }
        return current;
    }

    private VfsNode requireNode(String path) {
        return requireNode(path, process.ownerId());
    }

    private VfsNode requireNode(String path, UUID owner) {
        return resolve(path, owner).orElseThrow(() -> new FclRuntimeException(
                "Unknown VFS path: " + normalize(path)));
    }

    private Object remove(List<Object> args, VfsNode.Type expected, String function) {
        if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                function + " expects path and optional target user");
        return deletePath(string(args.getFirst(), function + " path"), expected, owner(args, 1));
    }

    private UUID owner(List<Object> args, int index) {
        if (args.size() <= index) return process.ownerId();
        String identity = string(args.get(index), "target user");
        UUID requested = null;
        try {
            requested = UUID.fromString(identity);
        } catch (IllegalArgumentException ignored) {
            Optional<UserAccount> own = transaction.auth().findUser(identity);
            if (own.isPresent() && own.orElseThrow().userId().equals(process.ownerId())) {
                return process.ownerId();
            }
        }
        if (process.ownerId().equals(requested)) return requested;
        Authorization.requireAdministrator(transaction, process.ownerId());
        UUID parsed = requested;
        return transaction.auth().findUsersByAdministrator(process.ownerId()).stream()
                .filter(user -> parsed != null ? user.userId().equals(parsed)
                        : user.username().equalsIgnoreCase(identity))
                .findFirst().orElseThrow(() -> new FclRuntimeException(
                        "Unknown target user: " + identity)).userId();
    }

    private void requireFileAccess(UUID owner, Capability capability) {
        Authorization.require(transaction, process.ownerId(), capability);
        if (!owner.equals(process.ownerId())) {
            Authorization.requireAdministrator(transaction, process.ownerId());
        }
    }

    private String normalize(String source) {
        return FclPath.resolve(continuation, source);
    }

    private Map<String, Object> nodeMap(VfsNode node) {
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

    private boolean terminate(long pid) {
        CilProcess target = targetProcess(pid, "process.kill");
        if (target.isTerminal()) return false;
        CilProcess changed = target;
        if (changed.status() != CilProcess.Status.TERMINATING) {
            changed = changed.transitionTo(CilProcess.Status.TERMINATING, now);
            requireUpdated(transaction.processes().update(changed, target.stateVersion(),
                    target.executionEpoch()), "process.kill");
        }
        CilProcess terminated = changed.transitionTo(CilProcess.Status.TERMINATED, now);
        requireUpdated(transaction.processes().update(terminated, changed.stateVersion(),
                changed.executionEpoch()), "process.kill");
        transaction.scheduler().release(target.identity().processUid(), target.executionEpoch());
        audit("process.kill", target.identity().processUid(), Map.of("pid", Long.toString(pid)));
        return true;
    }

    private boolean changeProcess(long pid, boolean pause) {
        if (pid == process.identity().pid()) {
            throw new FclRuntimeException("A RUNNING process cannot change its own scheduler state");
        }
        CilProcess target = targetProcess(pid, pause ? "process.pause" : "process.continue");
        CilProcess changed;
        if (pause) {
            if (target.status() == CilProcess.Status.PAUSED || target.isTerminal()) return false;
            changed = target.transitionTo(CilProcess.Status.PAUSED, now);
            transaction.scheduler().release(target.identity().processUid(), target.executionEpoch());
        } else {
            if (target.status() != CilProcess.Status.PAUSED) return false;
            CilProcess.Status resumed = CilProcess.statusFor(target.continuation().waitState());
            changed = target.transitionTo(resumed, now);
            if (resumed == CilProcess.Status.READY) {
                transaction.scheduler().enqueue(new com.follarce.domain.scheduler.SchedulerQueueEntry(
                        target.identity().processUid(), now, now,
                        com.follarce.domain.scheduler.SchedulerQueueEntry.Status.READY));
            }
        }
        requireUpdated(transaction.processes().update(changed, target.stateVersion(),
                target.executionEpoch()), pause ? "process.pause" : "process.continue");
        return true;
    }

    private Object waitForProcess(CilProcess target,
                                  FclFunctionRegistry.Invocation invocation) {
        if (target.isTerminal()) {
            return Map.of("pid", target.identity().pid(), "status", target.status().name());
        }
        if (invocation.continuation().scope().contains(ProcessInbox.TIMER_RESULT)) {
            invocation.continuation().scope().remove(ProcessInbox.TIMER_RESULT);
        }
        UUID timerId = UUID.randomUUID();
        transaction.timers().save(new ProcessTimer(timerId, process.identity().processUid(),
                now.plusMillis(50), ProcessTimer.Status.SCHEDULED, now, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(typed(null))));
        invocation.continuation().waitFor("timer:" + timerId,
                Map.of("pid", target.identity().pid()));
        throw FclSuspension.suspend();
    }

    private CilProcess targetProcess(long pid, String operation) {
        CilProcess target = transaction.processes().findByPid(pid)
                .orElseThrow(() -> new FclRuntimeException("Unknown PID: " + pid));
        Set<Capability> capabilities = transaction.auth().capabilities(process.ownerId());
        boolean allowed = target.ownerId().equals(process.ownerId())
                ? capabilities.contains(Capability.PROCESS_CONTROL_OWN)
                : capabilities.contains(Capability.PROCESS_CONTROL_ANY)
                || capabilities.contains(Capability.SYSTEM_ADMIN);
        if (!allowed) throw new SecurityException("Missing process control capability for "
                + operation);
        return target;
    }

    private boolean isAdministrator() {
        return transaction.auth().capabilities(process.ownerId())
                .contains(Capability.SYSTEM_ADMIN);
    }

    private void audit(String action, UUID resourceId, Map<String, String> details) {
        transaction.audit().append(new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                process.ownerId().toString(), action,
                action.startsWith("effect") ? "effect.effect"
                        : action.startsWith("process") ? "process.process" : "vfs.node",
                resourceId.toString(), AuditEvent.Result.SUCCEEDED, details, now));
    }

    private static void requireUpdated(ProcessRepository.UpdateResult result, String operation) {
        if (result != ProcessRepository.UpdateResult.UPDATED) {
            throw new FclRuntimeException(operation + " was rejected: " + result);
        }
    }

    private static void requireType(VfsNode node, VfsNode.Type type, String operation) {
        if (node.type() != type) throw new FclRuntimeException(operation
                + " requires " + type.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String path(List<Object> args, int index, int count, String function) {
        arity(args, count, function);
        return string(args.get(index), function + " path");
    }

    private static long integerAt(List<Object> args, int index, int count, String function) {
        arity(args, count, function);
        return integer(args.get(index), function);
    }

    private static long integer(Object value, String field) {
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) {
            throw new FclRuntimeException(field + " must be an integer");
        }
        return number.longValue();
    }

    private static UUID uuid(Object value, String field) {
        try {
            return UUID.fromString(string(value, field));
        } catch (IllegalArgumentException failure) {
            throw new FclRuntimeException(field + " must be a UUID", failure);
        }
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String text)) throw new FclRuntimeException(field
                + " must be a string");
        return text;
    }

    private static String display(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return text;
        return new com.google.gson.Gson().toJson(value);
    }

    private static Object unavailable(String function, String reason) {
        throw new FclRuntimeException(function + " is unavailable: " + reason);
    }

    private static void arity(List<Object> args, int expected, String function) {
        if (args.size() != expected) throw new FclRuntimeException(function + " expects "
                + expected + " arguments, got " + args.size());
    }

    private record ParentAndName(VfsNode parent, String name) {}
}
