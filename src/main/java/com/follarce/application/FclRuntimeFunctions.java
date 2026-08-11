package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.PackageIndex;
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
import com.follarce.fcl.FclSuspension;
import com.follarce.extension.JavaExtensionCatalog;
import com.follarce.extension.SourceExtensionIndex;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import com.follarce.package_manager.PackageCoordinateConflictException;
import com.follarce.package_manager.PackageBuilder;
import com.follarce.package_manager.PackageDependencyPolicy;
import com.follarce.market.client.MarketRuntimeFunctions;
import com.follarce.terminal.TerminalDimensions;
import com.follarce.timer.TimerService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Explicit application adapter that exposes durable CilExec services to one FCL statement. */
public final class FclRuntimeFunctions {
    private static final String TEXT = "text/plain;charset=utf-8";
    static final long MAX_FILE_BYTES = VfsFileLimits.MAX_FILE_BYTES;
    static final long MAX_IN_MEMORY_READ_BYTES = 16L * 1024 * 1024;
    private static final long MAX_PACKAGE_DATABASE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ENVIRONMENT_VALUE_BYTES = 64 * 1024;
    private static final int DOWNLOAD_CHUNK_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SYMLINK_DEPTH = 16;
    private static final int MAX_LINK_TARGET_BYTES = 4 * 1024;
    private static final Set<String> RUNTIME_ENVIRONMENT_NAMES = Set.of(
            "PWD", "USER", "USER_ID", "PID");
    private static final com.google.gson.Gson JSON = new com.google.gson.Gson();
    private static final EffectRequest.Policy MANUAL_EFFECT = new EffectRequest.Policy(
            false, Optional.empty(), false, false, EffectRequest.UnknownAction.MANUAL);

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
        registerEnvironment();
        registerUtilityAndIo();
        registerMemory();
        registerFiles();
        registerProcesses();
        registerUsers();
        registerNetworkAndSockets();
        registerPackages();
        registerMarket();
        registerSwapPool();
        registerIpc();
        registerSystem();
        extensions.installFunctions(registry, transaction, process, continuation, now);
    }

    private void registerPathState() {
        registry.registerContextual("path", "setAlias", (args, invocation) -> {
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

    private void registerEnvironment() {
        registry.register("env", "get", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "env.get expects name and optional target user");
                    String name = environmentName(args.getFirst());
                    if (RUNTIME_ENVIRONMENT_NAMES.contains(name)) {
                        if (args.size() != 1) throw new FclRuntimeException(
                                "Runtime environment variable " + name
                                        + " does not accept a target user");
                        return runtimeEnvironment(name);
                    }
                    UUID ownerId = owner(args, 1);
                    return transaction.environment().findUser(ownerId, name)
                            .or(() -> transaction.environment().findShared(name)).orElse(null);
                })
                .register("env", "set", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "env.set expects name, value, and optional target user");
                    String name = environmentName(args.getFirst());
                    requireWritableEnvironmentName(name, "env.set");
                    String value = environmentValue(args.get(1));
                    UUID ownerId = owner(args, 2);
                    transaction.environment().saveUser(ownerId, name, value, now);
                    return value;
                })
                .register("env", "remove", args -> {
                    if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                            "env.remove expects name and optional target user");
                    String name = environmentName(args.getFirst());
                    requireWritableEnvironmentName(name, "env.remove");
                    return transaction.environment().deleteUser(owner(args, 1), name);
                })
                .register("env", "list", args -> {
                    if (args.size() > 1) throw new FclRuntimeException(
                            "env.list expects an optional target user");
                    Map<String, String> resolved = new LinkedHashMap<>(
                            transaction.environment().findShared());
                    resolved.putAll(transaction.environment().findUsers(owner(args, 0)));
                    RUNTIME_ENVIRONMENT_NAMES.forEach(resolved::remove);
                    if (args.isEmpty()) {
                        RUNTIME_ENVIRONMENT_NAMES.forEach(name ->
                                resolved.put(name, runtimeEnvironment(name)));
                    }
                    return Map.copyOf(resolved);
                })
                .register("env", "getShared", args -> {
                    arity(args, 1, "env.getShared");
                    String name = environmentName(args.getFirst());
                    if (RUNTIME_ENVIRONMENT_NAMES.contains(name)) return null;
                    return transaction.environment().findShared(name)
                            .orElse(null);
                })
                .register("env", "listShared", args -> {
                    arity(args, 0, "env.listShared");
                    Map<String, String> shared = new LinkedHashMap<>(
                            transaction.environment().findShared());
                    RUNTIME_ENVIRONMENT_NAMES.forEach(shared::remove);
                    return Map.copyOf(shared);
                })
                .register("env", "setShared", args -> {
                    arity(args, 2, "env.setShared");
                    requireLocalAdministrator();
                    String name = environmentName(args.getFirst());
                    requireWritableEnvironmentName(name, "env.setShared");
                    EnvironmentRepository.SharedPolicy policy =
                            transaction.environment().sharedPolicy();
                    if (!policy.allows(name)) throw new FclRuntimeException(
                            "Shared environment policy rejects " + name);
                    String value = environmentValue(args.get(1));
                    transaction.environment().saveShared(name, value, process.ownerId(), now);
                    return value;
                })
                .register("env", "removeShared", args -> {
                    arity(args, 1, "env.removeShared");
                    requireLocalAdministrator();
                    String name = environmentName(args.getFirst());
                    requireWritableEnvironmentName(name, "env.removeShared");
                    return transaction.environment().deleteShared(name);
                })
                .register("env", "getSharedPolicy", args -> {
                    arity(args, 0, "env.getSharedPolicy");
                    EnvironmentRepository.SharedPolicy policy =
                            transaction.environment().sharedPolicy();
                    return Map.of("mode", policy.mode().name(), "names",
                            policy.names().stream().sorted().toList());
                })
                .register("env", "setSharedPolicy", args -> {
                    arity(args, 2, "env.setSharedPolicy");
                    requireLocalAdministrator();
                    String mode = string(args.getFirst(), "env.setSharedPolicy mode")
                            .toUpperCase(Locale.ROOT);
                    EnvironmentRepository.SharedPolicy.Mode parsed;
                    try {
                        parsed = EnvironmentRepository.SharedPolicy.Mode.valueOf(mode);
                    } catch (IllegalArgumentException invalid) {
                        throw new FclRuntimeException(
                                "env.setSharedPolicy mode must be ALLOWLIST or DENYLIST");
                    }
                    if (!(args.get(1) instanceof List<?> values)) throw new FclRuntimeException(
                            "env.setSharedPolicy names must be an array");
                    Set<String> names = values.stream().map(FclRuntimeFunctions::environmentName)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    EnvironmentRepository.SharedPolicy policy =
                            new EnvironmentRepository.SharedPolicy(parsed, names);
                    transaction.environment().findShared().keySet().forEach(name -> {
                        if (!policy.allows(name)) throw new FclRuntimeException(
                                "New policy rejects existing shared variable " + name);
                    });
                    transaction.environment().saveSharedPolicy(policy, process.ownerId(), now);
                    return Map.of("mode", policy.mode().name(), "names",
                            policy.names().stream().sorted().toList());
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
                .register("util", "which", args -> {
                    arity(args, 1, "util.which");
                    return functionOrigin(string(args.getFirst(), "util.which function"));
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
                    TerminalDimensions.Size size = TerminalDimensions.current(process.ownerId());
                    return Map.of("width", (long) size.width(),
                            "height", (long) size.height());
                }, "size")
                .register("term", "sanitize", args -> {
                    arity(args, 1, "term.sanitize");
                    return com.follarce.terminal.TerminalSanitizer.sanitize(
                            display(args.getFirst()));
                });

        FclFunctionRegistry.ContextFunction print = (args, invocation) -> {
            arity(args, 1, "print");
            return external(invocation, "io.output",
                    outputPayload(display(args.getFirst()), false), MANUAL_EFFECT, false);
        };
        FclFunctionRegistry.ContextFunction println = (args, invocation) -> {
            arity(args, 1, "println");
            return external(invocation, "io.output",
                    outputPayload(display(args.getFirst()), true), MANUAL_EFFECT, false);
        };
        registry.registerContextual("io", "print", print)
                .registerContextual("io", "println", println)
                .aliasQualified("io.print", "util", "print")
                .aliasQualified("io.println", "util", "println")
                .registerContextual("io", "input", (args, invocation) -> {
                    if (args.size() > 1) arity(args, 1, "io.input");
                    if (!args.isEmpty()) {
                        external(invocation, "io.output",
                                outputPayload(display(args.getFirst()), false), MANUAL_EFFECT,
                                false);
                    }
                    return terminalInput(invocation, false);
                })
                .aliasQualified("io.input", "util", "input")
                .registerContextual("io", "readChar", (args, invocation) -> {
                    arity(args, 0, "io.readChar");
                    return terminalInput(invocation, true, false);
                })
                .registerContextual("io", "readKey", (args, invocation) -> {
                    if (args.size() > 1) arity(args, 1, "io.readKey");
                    long timeout = -1;
                    if (!args.isEmpty()) {
                        timeout = integer(args.getFirst(), "io.readKey timeout milliseconds");
                        if (timeout < 0 || timeout > 86_400_000L) {
                            throw new FclRuntimeException(
                                    "io.readKey timeout must be between 0 and 86400000 milliseconds");
                        }
                    }
                    return readKey(invocation, timeout);
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

    /**
     * Returns 0 for a Runtime/extension function and the installed package database SHA-256
     * for an imported FCL function. A null result means that the name is not callable in the
     * current program, or that it is a function defined by the user's own source.
     */
    private Object functionOrigin(String identifier) {
        if (identifier.isBlank()) throw new FclRuntimeException(
                "util.which requires a non-blank function name");
        try {
            registry.resolve(identifier);
            return 0L;
        } catch (FclRuntimeException notRuntimeFunction) {
            // Imported and user-defined functions live in the compiled program, not here.
        }
        List<ProcessPackageBinding> bindings = transaction.packages()
                .findProcessBindings(process.identity().processUid());
        int separator = identifier.indexOf('.');
        if (separator > 0) {
            String importName = identifier.substring(0, separator);
            String publishedName = identifier.substring(separator + 1);
            return bindings.stream()
                    .filter(binding -> binding.importName().equals(importName))
                    .filter(binding -> packagePublishes(binding, publishedName))
                    .findFirst().map(this::marketPackageId).orElse(null);
        }

        String found = null;
        for (ProcessPackageBinding binding : bindings) {
            if (!packagePublishes(binding, identifier)) continue;
            String candidate = marketPackageId(binding);
            if (found != null && !found.equals(candidate)) return null;
            found = candidate;
        }
        return found;
    }

    private boolean packagePublishes(ProcessPackageBinding binding, String name) {
        PackageRelease release = transaction.packages().findRelease(binding.packageHash())
                .orElseThrow(() -> new IllegalStateException(
                        "Pinned package release is missing"));
        StoredObject database = transaction.vfs().findObject(release.databaseObjectHash())
                .orElseThrow(() -> new IllegalStateException(
                        "Pinned package database is missing"));
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(database.content().bytes());
        return descriptor.exports().stream().anyMatch(export -> export.name().equals(name))
                || descriptor.entrypoints().stream()
                .anyMatch(entrypoint -> entrypoint.name().equals(name));
    }

    private String marketPackageId(ProcessPackageBinding binding) {
        return transaction.packages().findRelease(binding.packageHash())
                .orElseThrow(() -> new IllegalStateException(
                        "Pinned package release is missing"))
                .databaseFileHash().value();
    }

    private Map<String, Object> outputPayload(String text, boolean newline) {
        FclScope global = continuation.globalScope();
        Object route = global.contains(
                TerminalReplService.TERMINAL_SESSION_SCOPE_KEY)
                ? global.get(TerminalReplService.TERMINAL_SESSION_SCOPE_KEY)
                : process.identity().processUid().toString();
        return Map.of("text", text, "newline", newline, "routeId", display(route));
    }

    /** Removes one current-scope user binding so its persisted value can be reclaimed. */
    private void registerMemory() {
        registry.registerContextual("memory", "destroy", (args, invocation) -> {
            arity(args, 1, "memory.destroy");
            String name = string(args.getFirst(), "variable name");
            if (reservedScopeKey(name)) {
                throw new FclRuntimeException("memory.destroy cannot remove runtime state: " + name);
            }
            if (!invocation.continuation().scope().contains(name)) return false;
            releaseValue(invocation.continuation().scope().remove(name), new IdentityHashMap<>());
            return true;
        }, "unset", "release");
    }

    private static boolean reservedScopeKey(String name) {
        return name.startsWith("cilexec.") || ProcessInbox.keys().contains(name);
    }

    /** Breaks every mutable container edge before the enclosing continuation is persisted. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void releaseValue(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || seen.put(value, Boolean.TRUE) != null) return;
        if (value instanceof Map map) {
            for (Object entry : new ArrayList<>(map.entrySet())) {
                Map.Entry item = (Map.Entry) entry;
                releaseValue(item.getKey(), seen);
                releaseValue(item.getValue(), seen);
            }
            map.clear();
            return;
        }
        if (value instanceof List list) {
            for (Object item : new ArrayList<>(list)) releaseValue(item, seen);
            list.clear();
            return;
        }
        if (value instanceof byte[] bytes) java.util.Arrays.fill(bytes, (byte) 0);
        if (value instanceof char[] chars) java.util.Arrays.fill(chars, '\0');
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
                .register("file", "listdir", args -> {
                    if (args.size() > 2) throw new FclRuntimeException(
                            "file.listdir expects optional path and target user");
                    String requested = args.isEmpty() ? "/"
                            : string(args.getFirst(), "file.listdir path");
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
                    requireType(directory, VfsNode.Type.DIRECTORY, "file.listdir");
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
                    String requestedPath = string(args.getFirst(), "process.exec path");
                    String absolutePath = normalize(requestedPath);
                    RoutedPath routed = route(requestedPath, process.ownerId());
                    if (!routed.ownerId().equals(process.ownerId())) {
                        throw new FclRuntimeException(
                                "process.exec accepts a file in the current user's VFS");
                    }
                    VfsNode sourceNode = requireNode(routed.path(), routed.ownerId());
                    requireType(sourceNode, VfsNode.Type.FILE, "process.exec");
                    if (absolutePath.toLowerCase(Locale.ROOT).endsWith(".db")) {
                        throw new FclRuntimeException(
                                "process.exec accepts an FCL source file, not a package database");
                    }
                    String source = readText(routed.path(), routed.ownerId());
                    String expanded = new FclSourceIncludes().expand(transaction,
                            process.ownerId(), source, parentDirectory(routed.path()));
                    String terminalLibrary = TerminalReplService.isTerminalProcess(
                            invocation.continuation())
                            ? TerminalReplService.librarySource(invocation.continuation()) : "";
                    Program target = createProgram(terminalLibrary + expanded);
                    invocation.continuation().waitFor("exec:" + target.programId(),
                            Map.of("path", absolutePath));
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
                .register("user", "removeUser", args -> {
                    arity(args, 1, "user.removeUser");
                    UUID userId = uuid(args.getFirst(), "user.removeUser user");
                    return userMap(transaction.auth().disableUserByAdministrator(
                            process.ownerId(), userId, UUID.randomUUID(), now));
                })
                .register("user", "switchUser", args -> unavailable("user.switchUser",
                        "a durable process identity cannot be changed in place"));
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
                    return packageDetails(requirePackage(args, "package.info"));
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
                .register("package", "verify", args -> {
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
                    PackageRelease release = requirePackage(args, "package.verify");
                    StoredObject object = transaction.vfs().findObject(release.databaseObjectHash())
                            .orElseThrow(() -> new FclRuntimeException(
                                    "Package database object is missing"));
                    boolean hashMatches = ObjectHash.sha256(object.content())
                            .equals(release.databaseFileHash());
                    Map<String, Object> verification = new LinkedHashMap<>(
                            packageDetails(release));
                    verification.put("valid", hashMatches);
                    verification.put("hashMatches", hashMatches);
                    return Map.copyOf(verification);
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
                    return decodeUtf8(content, "package.resource");
                })
                .register("package", "pin", args -> pinPackage(args))
                .register("package", "gc", args -> {
                    arity(args, 0, "package.gc");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    long deleted = transaction.vfs().garbageCollectObjects(
                            process.ownerId(), 1000);
                    transaction.audit().append(new AuditEvent(UUID.randomUUID(),
                            AuditEvent.ActorType.USER, process.ownerId().toString(),
                            "package.gc", "object_store", process.ownerId().toString(),
                            AuditEvent.Result.SUCCEEDED,
                            Map.of("limit", "1000", "deleted", Long.toString(deleted)), now));
                    return deleted;
                })
                .register("package", "recover", args -> {
                    arity(args, 0, "package.recover");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    return true;
                });
    }

    private void registerMarket() {
        new MarketRuntimeFunctions(new MarketRuntimeFunctions.Host() {
            @Override public String environment(String name) {
                return transaction.environment().findUser(process.ownerId(), name)
                        .or(() -> transaction.environment().findShared(name)).orElse(null);
            }

            @Override public void setEnvironment(String name, String value) {
                transaction.environment().saveUser(process.ownerId(), name, value, now);
            }

            @Override public boolean exists(String path) {
                return resolve(normalize(path)).isPresent();
            }

            @Override public void ensureDirectory(String path) {
                Optional<VfsNode> existing = resolve(normalize(path));
                if (existing.isEmpty()) {
                    createDirectory(path, process.ownerId());
                } else {
                    requireType(existing.orElseThrow(), VfsNode.Type.DIRECTORY,
                            "market storage");
                }
            }

            @Override public String readText(String path) {
                return FclRuntimeFunctions.this.readText(path);
            }

            @Override public void writeText(String path, String content) {
                FclRuntimeFunctions.this.writeText(path, content, false);
            }

            @Override public boolean removeFile(String path) {
                if (resolve(normalize(path)).isEmpty()) return false;
                return Boolean.TRUE.equals(remove(List.of(path), VfsNode.Type.FILE,
                        "market.removeFile"));
            }

            @Override public boolean fileMatches(String path, String sha256, long bytes) {
                return downloadedFileMatches(path, sha256, bytes);
            }

            @Override public Object httpGet(String url,
                                            FclFunctionRegistry.Invocation invocation) {
                return external(invocation, "network.http-get", Map.of("url", url),
                        idempotentPolicy(invocation, "MARKET-INDEX:" + url), true);
            }

            @Override public Object download(String url, String path,
                                             FclFunctionRegistry.Invocation invocation) {
                return FclRuntimeFunctions.this.download(List.of(url, path), invocation);
            }

            @Override @SuppressWarnings("unchecked")
            public Map<String, Object> install(String path) {
                Object installed = installPackage(List.of(path));
                if (!(installed instanceof Map<?, ?> map)) {
                    throw new FclRuntimeException("Package installer returned an invalid result");
                }
                return (Map<String, Object>) map;
            }
        }).register(registry);
    }

    private Object installPackage(List<Object> args) {
        if (args.size() != 1) throw new FclRuntimeException(
                "package.install expects a VFS path to an immutable package database");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
        VfsNode node = requireNode(string(args.getFirst(), "package.install path"));
        requireType(node, VfsNode.Type.FILE, "package.install");
        ObjectHash sourceHash = node.currentObjectHash().orElseThrow();
        byte[] databaseBytes = readLogicalObject(sourceHash, MAX_PACKAGE_DATABASE_BYTES,
                "Package database");
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(databaseBytes);
        com.follarce.package_manager.PackageCapabilityPolicy.inspect(
                databaseBytes, descriptor).requireUserCapabilities(
                transaction.auth().capabilities(process.ownerId()));
        ObjectHash fileHash = new ObjectHash(descriptor.databaseFileHash());
        StoredObject database = StoredObject.create(new BinaryContent(databaseBytes),
                "application/vnd.sqlite3", now);
        if (!fileHash.equals(database.objectHash())) {
            throw new FclRuntimeException("Package database hash changed during inspection");
        }
        transaction.vfs().saveObject(database);
        PackageRelease release = new PackageRelease(new PackageRelease.Coordinate(
                descriptor.namespace(), descriptor.name(), descriptor.version()),
                new PackageRelease.Hash(new ObjectHash(descriptor.packageHash())), fileHash,
                fileHash, now);
        PackageIndex index = new PackageIndex(release, descriptor.moduleIndex(),
                descriptor.dependencyIndex(), descriptor.entrypoints(), descriptor.exports(),
                descriptor.capabilityIndex());
        PackageDependencyPolicy.requireInstalled(transaction.packages(), index.dependencies());
        var result = transaction.packages().registerRelease(index);
        if (result == com.follarce.domain.port.PackageRepository.ReleaseWriteResult.COORDINATE_CONFLICT) {
            throw new PackageCoordinateConflictException(release.coordinate());
        }
        release = transaction.packages().findRelease(release.coordinate()).orElseThrow(
                () -> new IllegalStateException("Installed package release is missing"));
        audit("package.install", node.nodeId(), Map.of(
                "coordinate", release.coordinate().key(), "writeResult", result.name()));
        Map<String, Object> installed = new LinkedHashMap<>(packageMap(release));
        installed.putAll(descriptorMap(descriptor));
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
        Map<String, Object> built = new LinkedHashMap<>(descriptorMap(descriptor));
        built.put("nodeId", nodeId);
        built.put("path", outputPath);
        built.put("coordinate", descriptor.coordinate());
        built.put("packageHash", descriptor.packageHash());
        built.put("databaseFileHash", descriptor.databaseFileHash());
        return Map.copyOf(built);
    }

    private Object runPackage(List<Object> args) {
        if (args.isEmpty() || args.size() > 2) {
            throw new FclRuntimeException(
                    "package.run expects a package SHA-256 and optional entrypoint");
        }
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        Authorization.require(transaction, process.ownerId(), Capability.PROCESS_CREATE);
        String packageId = string(args.getFirst(), "package hash");
        if (!packageId.matches("(?i)[0-9a-f]{64}")) {
            throw new FclRuntimeException(
                    "package.run requires a 64-character package SHA-256");
        }
        String entrypointName = args.size() == 2
                ? string(args.get(1), "package entrypoint") : "run";
        PackageRelease release = transaction.packages().findReleaseByDatabaseFileHash(
                        new ObjectHash(packageId.toLowerCase(java.util.Locale.ROOT)))
                .orElseThrow(() -> new FclRuntimeException("Installed package release is missing"));
        StoredObject database = transaction.vfs().findObject(release.databaseObjectHash())
                .orElseThrow(() -> new FclRuntimeException("Installed package database is missing"));
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(database.content().bytes());
        PackageIndex.Entrypoint entrypoint = descriptor.entrypoints().stream()
                .filter(value -> value.name().equals(entrypointName)).findFirst()
                .orElseThrow(() -> new FclRuntimeException("Unknown package entrypoint: "
                        + entrypointName));
        if (!isSafeFclIdentifier(entrypoint.name())) {
            throw new FclRuntimeException("Invalid entrypoint name");
        }
        String importHash = release.databaseFileHash().value();
        String source = "import \"" + escapeFcl(importHash)
                + "\" as \"__package_entry\"\n"
                + "return __package_entry." + entrypoint.name() + "()\n";
        Program entryProgram = createProgram(source);
        long pid = transaction.processes().allocatePid();
        UUID processUid = UUID.randomUUID();
        Map<String, ObjectHash> packageBindings = Map.of(importHash,
                release.packageHash().value());
        Continuation continuation = new Continuation(entryProgram.programId(),
                entryProgram.programHash(), 0, List.of(), List.of(), List.of(), List.of(),
                Optional.empty(), Map.of(), packageBindings, entryProgram.languageVersion(),
                Integer.toString(entryProgram.runtimeFormatVersion()));
        CilProcess child = new CilProcess(new ProcessIdentity(processUid, pid), process.ownerId(),
                CilProcess.Status.READY, 0, 0, continuation,
                Optional.of(process.identity().processUid()), now, now);
        transaction.processes().insert(child);
        transaction.packages().saveProcessBinding(new ProcessPackageBinding(processUid,
                importHash, release.packageHash(), now));
        transaction.scheduler().enqueue(new com.follarce.domain.scheduler.SchedulerQueueEntry(
                processUid, now, now,
                com.follarce.domain.scheduler.SchedulerQueueEntry.Status.READY));
        audit("package.run", processUid, Map.of("pid", Long.toString(pid),
                "entrypoint", entrypointName,
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
        if (args.size() != 1) throw new FclRuntimeException(
                "package.pin expects a package SHA-256");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        PackageRelease release = requirePackage(args, "package.pin");
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
                "name", release.coordinate().name(),
                "hash", release.packageHash().value().value(),
                "sha256", release.databaseFileHash().value(),
                "importedAt", release.importedAt().toString());
    }

    private Map<String, Object> packageDetails(PackageRelease release) {
        StoredObject object = transaction.vfs().findObject(release.databaseObjectHash())
                .orElseThrow(() -> new FclRuntimeException("Package database object is missing"));
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(object.content().bytes());
        Map<String, Object> result = new LinkedHashMap<>(packageMap(release));
        result.putAll(descriptorMap(descriptor));
        return Map.copyOf(result);
    }

    private static Map<String, Object> descriptorMap(PackageDescriptor descriptor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", descriptor.kind().wireName());
        result.put("languageVersion", descriptor.languageVersion());
        result.put("dependencies", descriptor.dependencyIndex().stream().map(dependency -> Map.of(
                "sha256", dependency.databaseFileHash().value(),
                "optional", dependency.optional())).toList());
        result.put("entrypoints", descriptor.entrypoints().stream().map(entrypoint -> Map.of(
                "name", entrypoint.name(),
                "module", entrypoint.moduleName(),
                "function", entrypoint.functionName())).toList());
        result.put("exports", descriptor.exports().stream().map(export -> Map.of(
                "name", export.name(),
                "module", export.moduleName(),
                "symbol", export.symbolName())).toList());
        result.put("capabilities", descriptor.capabilityIndex().stream().map(capability -> Map.of(
                "key", capability.key(),
                "required", capability.required(),
                "rationale", capability.rationale())).toList());
        return Map.copyOf(result);
    }

    private static String escapeFcl(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static boolean isSafeFclIdentifier(String value) {
        return value != null
                && value.matches("[A-Za-z_][A-Za-z0-9_]*")
                && !value.equals("func") && !value.equals("if") && !value.equals("else")
                && !value.equals("while") && !value.equals("break")
                && !value.equals("continue") && !value.equals("return")
                && !value.equals("import") && !value.equals("include")
                && !value.equals("as") && !value.equals("and") && !value.equals("or")
                && !value.equals("true") && !value.equals("false") && !value.equals("null");
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

    private void registerIpc() {
        registry.register("ipc", "createChannel", args -> {
                    arity(args, 1, "ipc.createChannel");
                    IpcChannel channel = IpcService.createChannelIn(transaction,
                            process.ownerId(), string(args.get(0), "ipc.createChannel name"), now);
                    return Map.of("channelId", channel.channelId().toString(),
                            "name", channel.name());
                })
                .register("ipc", "createTopic", args -> {
                    arity(args, 1, "ipc.createTopic");
                    IpcTopic topic = IpcService.createTopicIn(transaction, process.ownerId(),
                            string(args.get(0), "ipc.createTopic name"), now);
                    return Map.of("topicId", topic.topicId().toString(),
                            "name", topic.name());
                })
                .register("ipc", "removeChannel", args -> {
                    arity(args, 1, "ipc.removeChannel");
                    return IpcService.removeChannelIn(transaction, process.ownerId(),
                            uuid(args.get(0), "ipc.removeChannel channelId"));
                })
                .register("ipc", "removeTopic", args -> {
                    arity(args, 1, "ipc.removeTopic");
                    return IpcService.removeTopicIn(transaction, process.ownerId(),
                            uuid(args.get(0), "ipc.removeTopic topicId"));
                })
                .register("ipc", "subscribeChannel", args -> {
                    arity(args, 1, "ipc.subscribeChannel");
                    IpcService.subscribeChannelIn(transaction, process.ownerId(),
                            process.identity().processUid(),
                            uuid(args.get(0), "ipc.subscribeChannel channelId"), now);
                    return true;
                })
                .register("ipc", "subscribeTopic", args -> {
                    arity(args, 1, "ipc.subscribeTopic");
                    IpcService.subscribeTopicIn(transaction, process.ownerId(),
                            process.identity().processUid(),
                            string(args.get(0), "ipc.subscribeTopic topic"), now);
                    return true;
                })
                .register("ipc", "sendDirect", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "ipc.sendDirect expects receiver pid, payload and optional expiresAt");
                    long pid = integer(args.get(0), "ipc.sendDirect pid");
                    CilProcess receiver = transaction.processes().findByPid(pid)
                            .orElseThrow(() -> new FclRuntimeException("Unknown process pid: " + pid));
                    IpcMessage message = IpcService.sendDirectIn(transaction, process.ownerId(),
                            Optional.of(process.identity().processUid()),
                            receiver.identity().processUid(),
                            ipcPayload(args.get(1), "ipc.sendDirect payload"),
                            ipcExpiry(args, 2, "ipc.sendDirect expiresAt"), now);
                    return Map.of("messageId", message.messageId().toString());
                })
                .register("ipc", "sendChannel", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "ipc.sendChannel expects channelId, payload and optional expiresAt");
                    IpcMessage message = IpcService.sendChannelIn(transaction, process.ownerId(),
                            Optional.of(process.identity().processUid()),
                            uuid(args.get(0), "ipc.sendChannel channelId"),
                            ipcPayload(args.get(1), "ipc.sendChannel payload"),
                            ipcExpiry(args, 2, "ipc.sendChannel expiresAt"), now);
                    return Map.of("messageId", message.messageId().toString());
                })
                .register("ipc", "publishTopic", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "ipc.publishTopic expects topic, payload and optional expiresAt");
                    IpcMessage message = IpcService.publishTopicIn(transaction, process.ownerId(),
                            Optional.of(process.identity().processUid()),
                            string(args.get(0), "ipc.publishTopic topic"),
                            ipcPayload(args.get(1), "ipc.publishTopic payload"),
                            ipcExpiry(args, 2, "ipc.publishTopic expiresAt"), now);
                    return Map.of("messageId", message.messageId().toString());
                })
                .register("ipc", "broadcast", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "ipc.broadcast expects topic, payload and optional expiresAt");
                    IpcMessage message = IpcService.broadcastIn(transaction, process.ownerId(),
                            Optional.of(process.identity().processUid()),
                            string(args.get(0), "ipc.broadcast topic"),
                            ipcPayload(args.get(1), "ipc.broadcast payload"),
                            ipcExpiry(args, 2, "ipc.broadcast expiresAt"), now);
                    return Map.of("messageId", message.messageId().toString());
                })
                .register("ipc", "purge", args -> {
                    if (args.isEmpty() || args.size() > 2) throw new FclRuntimeException(
                            "ipc.purge expects olderThan and optional limit");
                    Instant olderThan = instant(args.getFirst(), "ipc.purge olderThan");
                    long requestedLimit = args.size() == 2
                            ? integer(args.get(1), "ipc.purge limit") : 1000;
                    if (requestedLimit < 1 || requestedLimit > IpcService.MAX_PURGE_BATCH) {
                        throw new FclRuntimeException("ipc.purge limit must be between 1 and "
                                + IpcService.MAX_PURGE_BATCH);
                    }
                    return IpcService.purgeMessagesIn(transaction, process.ownerId(),
                            olderThan, now, (int) requestedLimit);
                })
                .registerContextual("ipc", "receive", (args, invocation) -> {
                    if (!args.isEmpty()) throw new FclRuntimeException("ipc.receive takes no arguments");
                    return ipcReceive(invocation);
                })
                .register("ipc", "poll", args -> {
                    arity(args, 0, "ipc.poll");
                    return IpcService.reserveNextIn(transaction, process.ownerId(),
                                    process.identity().processUid(), UUID.randomUUID(), now)
                            .map(envelope -> IpcService.envelopeMap(envelope.delivery(),
                                    envelope.message()))
                            .orElse(null);
                })
                .register("ipc", "consume", args -> {
                    arity(args, 1, "ipc.consume");
                    return IpcService.consumeIn(transaction, process.ownerId(),
                            uuid(args.get(0), "ipc.consume deliveryId"));
                });
    }

    /** Serializes any FCL value into a durable IPC payload. */
    private IpcService.Payload ipcPayload(Object value, String field) {
        Continuation.PersistedValue persisted = typed(value);
        return IpcService.Payload.json(persisted.type(), persisted.canonicalPayload());
    }

    /**
     * Blocking receive: consumes an already-delivered envelope from the process inbox, or
     * suspends the process as WAITING_IPC until a delivery wakes it.
     */
    private Object ipcReceive(FclFunctionRegistry.Invocation invocation) {
        FclContinuation continuation = invocation.continuation();
        if (continuation.scope().contains(ProcessInbox.IPC_RESULT)) {
            Object delivered = continuation.scope().remove(ProcessInbox.IPC_RESULT);
            if (delivered instanceof Continuation.PersistedValue persisted) {
                return codec.valueFromJson(persisted.canonicalPayload());
            }
            return delivered;
        }
        continuation.waitFor("ipc:" + process.identity().processUid(),
                Map.of("ipc", "receive"));
        throw FclSuspension.suspend();
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
                    String requested = string(args.getFirst(), "system.ls path");
                    if (normalize(requested).equals("/Users") && isLocalAdministrator()) {
                        return transaction.auth().findUsersByAdministrator(process.ownerId())
                                .stream().map(this::virtualUserNode).toList();
                    }
                    RoutedPath routed = route(requested, process.ownerId());
                    VfsNode directory = requireNode(routed.path(), routed.ownerId());
                    requireType(directory, VfsNode.Type.DIRECTORY, "system.ls");
                    return transaction.vfs().findChildren(routed.ownerId(),
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
                        String path = string(args.getFirst(), "system.forceRemove path");
                        VfsNode node = requireNode(path);
                        if (!transaction.vfs().findChildren(node.ownerId(),
                                Optional.of(node.nodeId())).isEmpty()
                                || !transaction.vfs().findRevisions(node.nodeId()).isEmpty()) {
                            throw new FclRuntimeException(
                                    "system.forceRemove cannot remove a non-empty directory or "
                                            + "versioned file at " + normalize(path)
                                            + "; the two-argument form (target user, node ID) "
                                            + "has the same restriction");
                        }
                        boolean removed = transaction.vfs().deleteByAdministrator(
                                process.ownerId(), node.ownerId(), node.nodeId(),
                                UUID.randomUUID(), now);
                        if (!removed) {
                            throw new FclRuntimeException(
                                    "system.forceRemove was rejected; the node is a root, mount, "
                                            + "or was concurrently changed: " + normalize(path));
                        }
                        return true;
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
            return oneCharacter ? (input.isEmpty() ? ""
                    : input.substring(0, input.offsetByCodePoints(0, 1))) : input;
        }
        continuation.waitFor(rawKey ? "input:key" : "input",
                Map.of("readChar", oneCharacter, "rawKey", rawKey));
        throw FclSuspension.suspend();
    }

    /**
     * io.readKey returns one structured terminal event. A pending key event is consumed
     * immediately; otherwise the process waits in key mode, with an optional durable timer
     * delivering a timeout event when no key arrives.
     */
    private Object readKey(FclFunctionRegistry.Invocation invocation, long timeout) {
        FclContinuation continuation = invocation.continuation();
        if (continuation.scope().contains(ProcessInbox.TERMINAL_INPUT)) {
            return parseTerminalEvent(display(continuation.scope()
                    .remove(ProcessInbox.TERMINAL_INPUT)));
        }
        if (timeout >= 0 && continuation.scope().contains(ProcessInbox.TIMER_RESULT)) {
            Object timerResult = continuation.scope().remove(ProcessInbox.TIMER_RESULT);
            if (TimerService.TERMINAL_INPUT_TIMEOUT.equals(display(timerResult))) {
                return Map.of("kind", "timeout");
            }
            return parseTerminalEvent(display(timerResult));
        }
        if (timeout >= 0) {
            UUID timerId = UUID.randomUUID();
            transaction.timers().save(new ProcessTimer(timerId,
                    process.identity().processUid(), now.plus(Duration.ofMillis(timeout)),
                    ProcessTimer.Status.SCHEDULED, now, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(typed(TimerService.TERMINAL_INPUT_TIMEOUT))));
        }
        continuation.waitFor("input:key", Map.of("rawKey", true));
        throw FclSuspension.suspend();
    }

    /** Parses a terminal event payload into a structured FCL map. */
    private static Object parseTerminalEvent(String input) {
        if (input == null || input.isBlank()) return null;
        if (input.startsWith("{")) {
            try {
                return JSON.fromJson(input, Map.class);
            } catch (RuntimeException malformed) {
                return Map.of("kind", "raw", "sequence", input);
            }
        }
        return Map.of("kind", "key", "key", input,
                "shift", false, "ctrl", false, "alt", false, "text", "");
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
        if (status == 416 && complete && total == offset && currentHash.isPresent()) {
            if (mediaType == null) mediaType = "application/octet-stream";
            clearDownloadState(scope, state);
            String nodeId = attachDownloadedObject(path, currentHash.orElseThrow(), mediaType,
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

        scope.put(state + "offset", downloaded);
        scope.put(state + "hash", nextHash.value());
        scope.put(state + "mediaType", mediaType);
        if (response.get("validator") instanceof String validator && !validator.isBlank()) {
            scope.put(state + "validator", validator);
        }
        return download(args, invocation);
    }

    private Map<String, Object> completedDownload(String nodeId, String path, String url,
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

    private static void clearDownloadState(FclScope scope, String prefix) {
        for (String suffix : List.of("offset", "hash", "mediaType", "validator")) {
            String key = prefix + suffix;
            if (scope.contains(key)) scope.remove(key);
        }
    }

    /**
     * Stable identity of a download attempt so terminal resubmissions cannot reuse stale
     * offset state. The destination path is part of the identity: resuming with the same
     * URL but a different target must not append new chunks to the old object.
     */
    private static String downloadIdentity(String url, String destinationPath) {
        return sha256((url + "\0" + destinationPath).getBytes(StandardCharsets.UTF_8))
                .substring(0, 16);
    }

    private EffectRequest.Policy idempotentPolicy(FclFunctionRegistry.Invocation invocation,
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

    private Continuation.PersistedValue typed(Object value) {
        return new Continuation.PersistedValue(codec.valueType(value), codec.valueToJson(value));
    }

    private String readText(String path) {
        return readText(path, process.ownerId());
    }

    private String readText(String path, UUID owner) {
        return decodeUtf8(readBytes(path, owner), "file.read");
    }

    /**
     * Resolves a file-shaped path through symbolic links to its underlying FILE node.
     * Each SYMLINK node stores its target path as its object content (file.link);
     * reading follows the chain while rejecting cycles and excessive depth.
     */
    private VfsNode resolveFileNode(String path, UUID owner) {
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

    private byte[] readBytes(String path) {
        return readBytes(path, process.ownerId());
    }

    private byte[] readBytes(String path, UUID owner) {
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

    private byte[] readLogicalObject(ObjectHash hash, long maximumBytes, String field) {
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

    /** Verifies a potentially large logical VFS file without materializing it in one array. */
    private boolean downloadedFileMatches(String path, String expectedSha256,
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

    private byte[] readRange(String path, long offset, int maximum, UUID owner) {
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

    /**
     * Bounded whole-object read through the administrator path. The 16 MiB in-memory
     * cap is enforced before any bytes are materialized, so chunked manifests are
     * never loaded into one JVM array.
     */
    private byte[] readObjectByAdministrator(ObjectHash hash, UUID owner, String limitMessage) {
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

    private String writeText(String source, String content, boolean append) {
        return writeText(source, content, append, process.ownerId());
    }

    private String writeText(String source, String content, boolean append, UUID owner) {
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

    /** Publishes an already persisted logical object only after every download chunk arrived. */
    private String attachDownloadedObject(String source, ObjectHash objectHash, String mediaType,
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

    private String createDirectory(String source) {
        return createDirectory(source, process.ownerId());
    }

    private String createDirectory(String source, UUID owner) {
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

    private VfsNode existingChildAfterConflict(String source, UUID owner, ParentAndName parent,
                                               RuntimeException conflict) {
        if (!(conflict instanceof com.follarce.persistence.postgres.error.PersistenceFailure
                failure)
                || failure.kind()
                != com.follarce.persistence.postgres.error.PersistenceFailure.Kind.UNIQUE_CONFLICT) {
            throw conflict;
        }
        return existingChild(owner, parent)
                .orElseThrow(() -> new FclRuntimeException(
                        "A node already exists at this path: " + normalize(source)));
    }

    private Optional<VfsNode> existingChild(UUID owner, ParentAndName parent) {
        if (owner.equals(process.ownerId())) {
            return transaction.vfs().findChild(owner,
                    Optional.of(parent.parent().nodeId()), parent.name());
        }
        return transaction.vfs().findChildByAdministrator(process.ownerId(), owner,
                Optional.of(parent.parent().nodeId()), parent.name());
    }

    private boolean deletePath(String source, VfsNode.Type expected) {
        return deletePath(source, expected, process.ownerId());
    }

    private boolean deletePath(String source, VfsNode.Type expected, UUID owner) {
        RoutedPath routed = route(source, owner);
        source = routed.path();
        owner = routed.ownerId();
        requireFileAccess(owner, Capability.VFS_WRITE);
        VfsNode node = requireNode(source, owner);
        // A symbolic link is a leaf node and is removed through the file-shaped API.
        // Requiring FILE here made links permanent for ordinary users because no separate
        // removeLink function exists.
        if (node.type() != expected
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
        RoutedPath routed = route(source, process.ownerId());
        return resolve(routed.path(), routed.ownerId());
    }

    private Optional<VfsNode> resolve(String source, UUID owner) {
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

    private void requireFileAccess(UUID owner, Capability capability) {
        Authorization.require(transaction, process.ownerId(), capability);
        if (!owner.equals(process.ownerId())) {
            Authorization.requireAdministrator(transaction, process.ownerId());
        }
    }

    private void requireFileAccess(UUID owner, Capability primary, Capability alternative) {
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

    private String normalize(String source) {
        if (source == null || source.isBlank() || !source.replace('\\', '/').startsWith("/")) {
            throw new FclRuntimeException(
                    "Absolute VFS path required; scripts may explicitly resolve a relative path "
                            + "with path.join(env.get(\"PWD\"), path)");
        }
        return FclPath.normalizeAbsolute(source);
    }

    private static String parentDirectory(String absolutePath) {
        int separator = absolutePath.lastIndexOf('/');
        return separator <= 0 ? "/" : absolutePath.substring(0, separator);
    }

    private RoutedPath route(String source, UUID requestedOwner) {
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

    private Map<String, Object> virtualUserNode(UserAccount user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", "virtual-user-root:" + user.userId());
        result.put("ownerId", user.userId().toString());
        result.put("name", user.username());
        result.put("type", VfsNode.Type.DIRECTORY.name());
        result.put("revisionEnabled", false);
        result.put("virtual", true);
        return Map.copyOf(result);
    }

    private boolean isLocalAdministrator() {
        // User transactions intentionally cannot SELECT auth.user_account directly.
        // The security-definer capability function is the authority for administrator routing.
        return isAdministrator();
    }

    private void requireLocalAdministrator() {
        Authorization.requireAdministrator(transaction, process.ownerId());
    }

    private static String environmentName(Object value) {
        String name = string(value, "environment variable name").trim()
                .toUpperCase(Locale.ROOT);
        if (!name.matches("[A-Z_][A-Z0-9_]{0,127}")) throw new FclRuntimeException(
                "Environment variable name must match [A-Z_][A-Z0-9_]{0,127}");
        return name;
    }

    private static String environmentValue(Object value) {
        String text = string(value, "environment variable value");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_ENVIRONMENT_VALUE_BYTES) {
            throw new FclRuntimeException("Environment variable value exceeds 64 KiB");
        }
        return text;
    }

    private String runtimeEnvironment(String name) {
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

    private static void requireWritableEnvironmentName(String name, String operation) {
        if (RUNTIME_ENVIRONMENT_NAMES.contains(name)) {
            throw new FclRuntimeException(operation + " cannot change Java-managed Runtime "
                    + "environment variable " + name);
        }
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
        Continuation stoppedContinuation = target.continuation()
                .withoutWait().withoutTransientInbox();
        CilProcess terminating = target.commitStatement(stoppedContinuation,
                CilProcess.Status.TERMINATING, target.stateVersion(),
                target.executionEpoch(), now);
        requireUpdated(transaction.processes().update(terminating, target.stateVersion(),
                target.executionEpoch()), "process.kill");
        CilProcess terminated = terminating.transitionTo(CilProcess.Status.TERMINATED, now);
        requireUpdated(transaction.processes().update(terminated, terminating.stateVersion(),
                terminating.executionEpoch()), "process.kill");
        transaction.scheduler().release(target.identity().processUid(), target.executionEpoch());
        transaction.timers().deleteForProcess(target.identity().processUid());
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
        boolean allowed = capabilities.contains(Capability.SYSTEM_ADMIN)
                || (target.ownerId().equals(process.ownerId())
                ? capabilities.contains(Capability.PROCESS_CONTROL_OWN)
                : capabilities.contains(Capability.PROCESS_CONTROL_ANY));
        if (!allowed) throw new SecurityException("Missing process control capability for "
                + operation);
        return target;
    }

    private boolean isAdministrator() {
        return transaction.auth().capabilities(process.ownerId())
                .contains(Capability.SYSTEM_ADMIN);
    }

    private void audit(String action, UUID resourceId, Map<String, String> details) {
        String resourceType;
        if (action.startsWith("effect")) {
            resourceType = "effect.effect";
        } else if (action.startsWith("process")) {
            resourceType = "process.process";
        } else if (action.startsWith("network.")) {
            resourceType = "network.request";
        } else if (action.startsWith("package.")) {
            resourceType = "package.binding";
        } else {
            resourceType = "vfs.node";
        }
        transaction.audit().append(new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                process.ownerId().toString(), action, resourceType,
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
        if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof java.math.BigInteger whole) {
            try {
                return whole.longValueExact();
            } catch (ArithmeticException outOfRange) {
                throw new FclRuntimeException(field + " must be an integer");
            }
        }
        if (value instanceof java.math.BigDecimal decimal) {
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException outOfRange) {
                throw new FclRuntimeException(field + " must be an integer");
            }
        }
        if (value instanceof Number number) {
            double converted = number.doubleValue();
            if (converted == Math.rint(converted)
                    && Math.abs(converted) <= (double) Long.MAX_VALUE) {
                return (long) converted;
            }
        }
        throw new FclRuntimeException(field + " must be an integer");
    }

    private static UUID uuid(Object value, String field) {
        try {
            return UUID.fromString(string(value, field));
        } catch (IllegalArgumentException failure) {
            throw new FclRuntimeException(field + " must be a UUID", failure);
        }
    }

    private Optional<Instant> ipcExpiry(List<Object> args, int index, String field) {
        if (args.size() <= index || args.get(index) == null) return Optional.empty();
        Instant expiresAt = instant(args.get(index), field);
        if (!expiresAt.isAfter(now)) {
            throw new FclRuntimeException(field + " must be after the current time");
        }
        return Optional.of(expiresAt);
    }

    private static Instant instant(Object value, String field) {
        try {
            return Instant.parse(string(value, field));
        } catch (java.time.format.DateTimeParseException failure) {
            throw new FclRuntimeException(field + " must be an ISO-8601 instant", failure);
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
        return JSON.toJson(value);
    }

    private static String decodeUtf8(byte[] bytes, String operation) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new FclRuntimeException(operation + " encountered invalid UTF-8; "
                    + "use a binary-capable API or align chunk boundaries", invalid);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Object unavailable(String function, String reason) {
        throw new FclRuntimeException(function + " is unavailable: " + reason);
    }

    private static void arity(List<Object> args, int expected, String function) {
        if (args.size() != expected) throw new FclRuntimeException(function + " expects "
                + expected + " arguments, got " + args.size());
    }

    private record ParentAndName(VfsNode parent, String name) {}
    private record RoutedPath(UUID ownerId, String path) {}
}
