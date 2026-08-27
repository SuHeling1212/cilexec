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
import com.follarce.domain.port.DurableStorageFailure;
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
import com.follarce.auth.AccountCapabilityProfiles;
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
import java.util.function.Consumer;

/** Explicit application adapter that exposes durable CilExec services to one FCL statement. */
public class FclRuntimeFunctions {
    protected static final String TEXT = "text/plain;charset=utf-8";
    protected static final long MAX_FILE_BYTES = VfsFileLimits.MAX_FILE_BYTES;
    protected static final long MAX_IN_MEMORY_READ_BYTES = 16L * 1024 * 1024;
    protected static final long MAX_PACKAGE_DATABASE_BYTES = 64L * 1024 * 1024;
    protected static final int MAX_ENVIRONMENT_VALUE_BYTES = 64 * 1024;
    protected static final int DOWNLOAD_CHUNK_BYTES = 4 * 1024 * 1024;
    protected static final int MAX_SYMLINK_DEPTH = 16;
    protected static final int MAX_LINK_TARGET_BYTES = 4 * 1024;
    protected static final Set<String> RUNTIME_ENVIRONMENT_NAMES = Set.of(
            "PWD", "USER", "USER_ID", "PID");
    protected static final com.google.gson.Gson JSON = new com.google.gson.Gson();
    protected static final EffectRequest.Policy MANUAL_EFFECT = new EffectRequest.Policy(
            false, Optional.empty(), false, false, EffectRequest.UnknownAction.MANUAL);

    protected final TransactionContext transaction;
    protected final CilProcess process;
    protected final Program program;
    protected final FclContinuation continuation;
    protected final Instant now;
    protected final JavaExtensionCatalog extensions;
    protected final FclContinuationCodec codec = new FclContinuationCodec();
    protected final FclFunctionRegistry registry;
    /** Requests are held only for the enclosing transaction and launched after its commit. */
    protected final Consumer<VolatileProcessRequest> volatileProcessRequests;
    /** Process output is a disposable delivery hint emitted only after state commit. */
    protected final Consumer<ProcessOutput> processOutputs;

    protected FclRuntimeFunctions(TransactionContext transaction, CilProcess process, Program program,
                                FclContinuation continuation, Instant now,
                                JavaExtensionCatalog extensions) {
        this(transaction, process, program, continuation, now, extensions,
                FclBuiltins.pureRegistry(), FclRuntimeFunctions::volatileUnavailable,
                FclRuntimeFunctions::terminalRenderUnavailable);
    }

    protected FclRuntimeFunctions(FclRuntimeFunctions source) {
        this(source.transaction, source.process, source.program, source.continuation, source.now,
                source.extensions, source.registry, source.volatileProcessRequests,
                source.processOutputs);
    }

    protected FclRuntimeFunctions(TransactionContext transaction, CilProcess process, Program program,
                                FclContinuation continuation, Instant now,
                                JavaExtensionCatalog extensions, FclFunctionRegistry registry) {
        this(transaction, process, program, continuation, now, extensions, registry,
                FclRuntimeFunctions::volatileUnavailable,
                FclRuntimeFunctions::terminalRenderUnavailable);
    }

    protected FclRuntimeFunctions(TransactionContext transaction, CilProcess process, Program program,
                                FclContinuation continuation, Instant now,
                                JavaExtensionCatalog extensions, FclFunctionRegistry registry,
                                Consumer<VolatileProcessRequest> volatileProcessRequests,
                                Consumer<ProcessOutput> processOutputs) {
        this.transaction = java.util.Objects.requireNonNull(transaction, "transaction");
        this.process = java.util.Objects.requireNonNull(process, "process");
        this.program = java.util.Objects.requireNonNull(program, "program");
        this.continuation = java.util.Objects.requireNonNull(continuation, "continuation");
        this.now = java.util.Objects.requireNonNull(now, "now");
        this.extensions = java.util.Objects.requireNonNull(extensions, "extensions");
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.volatileProcessRequests = java.util.Objects.requireNonNull(volatileProcessRequests,
                "volatileProcessRequests");
        this.processOutputs = java.util.Objects.requireNonNull(processOutputs, "processOutputs");
    }

    static FclFunctionRegistry create(TransactionContext transaction, CilProcess process,
                                      Program program, FclContinuation continuation, Instant now) {
        return create(transaction, process, program, continuation, now,
                SourceExtensionIndex.catalog());
    }

    static FclFunctionRegistry create(TransactionContext transaction, CilProcess process,
                                      Program program, FclContinuation continuation, Instant now,
                                      JavaExtensionCatalog extensions) {
        return create(transaction, process, program, continuation, now, extensions,
                FclRuntimeFunctions::volatileUnavailable,
                FclRuntimeFunctions::terminalRenderUnavailable);
    }

    static FclFunctionRegistry create(TransactionContext transaction, CilProcess process,
                                      Program program, FclContinuation continuation, Instant now,
                                      JavaExtensionCatalog extensions,
                                      Consumer<VolatileProcessRequest> volatileProcessRequests) {
        return create(transaction, process, program, continuation, now, extensions,
                volatileProcessRequests, FclRuntimeFunctions::terminalRenderUnavailable);
    }

    static FclFunctionRegistry create(TransactionContext transaction, CilProcess process,
                                      Program program, FclContinuation continuation, Instant now,
                                      JavaExtensionCatalog extensions,
                                      Consumer<VolatileProcessRequest> volatileProcessRequests,
                                      Consumer<ProcessOutput> processOutputs) {
        FclRuntimeFunctions functions = new FclRuntimeFunctions(transaction, process, program,
                continuation, now, extensions, FclBuiltins.pureRegistry(),
                volatileProcessRequests, processOutputs);
        functions.register();
        return functions.registry;
    }

    private static void volatileUnavailable(VolatileProcessRequest request) {
        throw new FclRuntimeException(
                "process.run is only available while executing a durable process");
    }

    private static void terminalRenderUnavailable(ProcessOutput output) {
        throw new FclRuntimeException(
                "term.render is only available while executing a durable process");
    }

    protected void register() {
        registerPathState();
        registerEnvironment();
        registerUtilityAndIo();
        registerMemory();
        new FclFileRuntimeFunctions(this).registerFiles();
        new FclProcessRuntimeFunctions(this).registerProcesses();
        registerUsers();
        registerResourceControl();
        new FclNetworkRuntimeFunctions(this).registerNetworkAndSockets();
        FclPackageRuntimeFunctions packages = new FclPackageRuntimeFunctions(this);
        packages.registerPackages();
        packages.registerPackageData();
        packages.registerMarket();
        FclProcessRuntimeFunctions processes = new FclProcessRuntimeFunctions(this);
        processes.registerSwapPool();
        processes.registerIpc();
        processes.registerSystem();
        extensions.installFunctions(registry, transaction, process, continuation, now);
    }

    /**
     * Resolves the database file that owns the private package-data space for the linked
     * package currently executing this function.  Both {@code packageData.*} and the
     * package-private {@code process.run} source URI use this check, so a package can never
     * make volatile work execute source from another package's private space.
     */
    protected ObjectHash currentPackageDataFile(FclFunctionRegistry.Invocation invocation) {
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        String identity = invocation.packageIdentity();
        if (identity == null) {
            throw new FclRuntimeException(
                    "package-private operations can only be called from installed package code");
        }
        PackageRelease release = transaction.packages().findRelease(
                        new PackageRelease.Hash(new ObjectHash(identity)))
                .orElseThrow(() -> new FclRuntimeException("Linked package release is missing"));
        if (transaction.packages().findInstalledReleaseByDatabaseFileHash(
                process.ownerId(), release.databaseFileHash()).isEmpty()) {
            throw new FclRuntimeException("Linked package is not installed for the current user");
        }
        return release.databaseFileHash();
    }

    protected void registerPathState() {
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

    protected void registerEnvironment() {
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

    protected static Map<String, Object> aliases(FclContinuation continuation) {
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

    protected void registerUtilityAndIo() {
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
                    InteractionViewport.Size size = InteractionViewport.current(process.ownerId());
                    return Map.of("width", (long) size.width(),
                            "height", (long) size.height());
                }, "size")
                .register("term", "sanitize", args -> {
                    arity(args, 1, "term.sanitize");
                    return ConsoleTextSanitizer.sanitize(display(args.getFirst()));
                })
                .registerContextual("term", "render", (args, invocation) -> {
                    arity(args, 1, "term.render");
                    String text = string(args.getFirst(), "term.render frame");
                    if (text.length() > 4 * 1024 * 1024) {
                        throw new FclRuntimeException(
                                "term.render frame exceeds 4194304 characters");
                    }
                    FclScope global = invocation.continuation().globalScope();
                    if (!global.contains(InteractiveProcessState.OUTPUT_ROUTE_SCOPE_KEY)) {
                        throw new FclRuntimeException(
                                "term.render requires a process started from a terminal");
                    }
                    String route = string(global.get(
                            InteractiveProcessState.OUTPUT_ROUTE_SCOPE_KEY),
                            "term.render terminal route");
                    UUID routeId;
                    try {
                        routeId = UUID.fromString(route);
                    } catch (IllegalArgumentException invalid) {
                        throw new FclRuntimeException("term.render terminal route is invalid",
                                invalid);
                    }
                    TerminalModeState.capture(global, text);
                    processOutputs.accept(ProcessOutput.interactionFrame(routeId, text));
                    return null;
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
                    if (args.size() > 2) {
                        throw new FclRuntimeException(
                                "io.readKey expects optional timeout and coalesceText arguments");
                    }
                    long timeout = -1;
                    boolean coalesceText = false;
                    if (!args.isEmpty()) {
                        if (args.getFirst() instanceof Boolean value) {
                            if (args.size() != 1) {
                                throw new FclRuntimeException(
                                        "io.readKey boolean coalesceText form expects one argument");
                            }
                            coalesceText = value;
                        } else {
                            timeout = integer(args.getFirst(), "io.readKey timeout milliseconds");
                            if (timeout < 0 || timeout > 86_400_000L) {
                                throw new FclRuntimeException(
                                        "io.readKey timeout must be between 0 and 86400000 milliseconds");
                            }
                        }
                    }
                    if (args.size() == 2) {
                        if (!(args.get(1) instanceof Boolean value)) {
                            throw new FclRuntimeException(
                                    "io.readKey coalesceText must be boolean");
                        }
                        coalesceText = value;
                    }
                    return readKey(invocation, timeout, coalesceText);
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
                })
                .register("storage", "purgeUnreferenced", args -> {
                    if (args.size() > 1) throw new FclRuntimeException(
                            "storage.purgeUnreferenced expects zero arguments or one limit");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    int limit = args.isEmpty() ? 1000
                            : positiveLimit(args.getFirst(), 10000,
                            "storage.purgeUnreferenced limit");
                    long deleted = transaction.vfs().garbageCollectObjects(
                            process.ownerId(), limit);
                    transaction.audit().append(new AuditEvent(UUID.randomUUID(),
                            AuditEvent.ActorType.USER, process.ownerId().toString(),
                            "storage.purgeUnreferenced", "object_store", process.ownerId().toString(),
                            AuditEvent.Result.SUCCEEDED,
                            Map.of("limit", Integer.toString(limit),
                                    "deleted", Long.toString(deleted)), now));
                    return deleted;
                });
    }

    /**
     * Explicit resource-history control for administrators. Every function here removes
     * durable rows on demand; nothing in this group ever runs automatically.
     */
    protected void registerResourceControl() {
        registry.register("program", "remove", args -> {
                    arity(args, 1, "program.remove");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    UUID programId = uuid(args.getFirst(), "program.remove program");
                    Map<String, Object> report = transaction.programs().removeByAdministrator(
                            process.ownerId(), programId, UUID.randomUUID(), now);
                    audit("program.remove", programId, Map.of(
                            "removed", Boolean.toString(Boolean.TRUE.equals(report.get("removed"))),
                            "processCount", numberText(report.get("processCount")),
                            "importedByCount", numberText(report.get("importedByCount"))));
                    return report;
                })
                .register("terminal", "remove", args -> {
                    arity(args, 1, "terminal.remove");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    UUID sessionId = uuid(args.getFirst(), "terminal.remove session");
                    com.follarce.domain.terminal.TerminalSession session =
                            transaction.terminal().findSession(sessionId).orElseThrow(
                                    () -> new FclRuntimeException(
                                            "Unknown terminal session: " + sessionId));
                    if (session.status() != com.follarce.domain.terminal.TerminalSession.Status.CLOSED) {
                        throw new FclRuntimeException(
                                "terminal.remove requires a closed session; session "
                                        + sessionId + " is still open");
                    }
                    boolean removed = transaction.terminal().removeClosedSession(sessionId);
                    audit("terminal.remove", sessionId, Map.of(
                            "owner", session.ownerId().toString(),
                            "removed", Boolean.toString(removed)));
                    return removed;
                })
                .register("timer", "purge", args -> {
                    if (args.isEmpty() || args.size() > 2) throw new FclRuntimeException(
                            "timer.purge expects a cutoff instant and an optional limit");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    Instant before = instant(args.getFirst(), "timer.purge before");
                    Integer limit = args.size() == 2 ? positiveLimit(args.get(1), 100000,
                            "timer.purge limit") : null;
                    int purged = transaction.timers().purgeFinishedBefore(before, limit);
                    audit("timer.purge", process.identity().processUid(), Map.of(
                            "before", before.toString(),
                            "limit", limit == null ? "all" : Integer.toString(limit),
                            "purged", Integer.toString(purged)));
                    return purged;
                })
                .register("audit", "purge", args -> {
                    if (args.isEmpty() || args.size() > 2) throw new FclRuntimeException(
                            "audit.purge expects a cutoff instant and an optional limit");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    Instant before = instant(args.getFirst(), "audit.purge before");
                    Integer limit = args.size() == 2 ? positiveLimit(args.get(1), 100000,
                            "audit.purge limit") : null;
                    int purged = transaction.audit().purgeBeforeByAdministrator(
                            process.ownerId(), before, limit);
                    audit("audit.purge", process.identity().processUid(), Map.of(
                            "before", before.toString(),
                            "limit", limit == null ? "all" : Integer.toString(limit),
                            "purged", Integer.toString(purged)));
                    return purged;
                });
    }

    private static int positiveLimit(Object value, int maximum, String field) {
        long limit = integer(value, field);
        if (limit < 1 || limit > maximum) throw new FclRuntimeException(
                field + " must be between 1 and " + maximum);
        return (int) limit;
    }

    private static String numberText(Object value) {
        return value == null ? "0" : Long.toString(((Number) value).longValue());
    }

    /**
     * Returns 0 for a runtime or extension function and the database-file SHA-256 for an
     * descriptor-listed export or entrypoint in a package bound to this process. Returns null
     * for source-imported and base-program functions, missing names, and ambiguous unqualified
     * package exports.
     */
    protected Object functionOrigin(String identifier) {
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

    protected boolean packagePublishes(ProcessPackageBinding binding, String name) {
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

    protected String marketPackageId(ProcessPackageBinding binding) {
        return transaction.packages().findRelease(binding.packageHash())
                .orElseThrow(() -> new IllegalStateException(
                        "Pinned package release is missing"))
                .databaseFileHash().value();
    }

    protected Map<String, Object> outputPayload(String text, boolean newline) {
        FclScope global = continuation.globalScope();
        Object route = global.contains(InteractiveProcessState.OUTPUT_ROUTE_SCOPE_KEY)
                ? global.get(InteractiveProcessState.OUTPUT_ROUTE_SCOPE_KEY)
                : global.contains(InteractiveProcessState.SESSION_SCOPE_KEY)
                ? global.get(InteractiveProcessState.SESSION_SCOPE_KEY)
                : process.identity().processUid().toString();
        if (route instanceof String) TerminalModeState.capture(global, text);
        return Map.of("text", text, "newline", newline, "routeId", display(route));
    }

    /** Installs process-local name inspection and deletion without mutating runtime functions. */
    protected void registerMemory() {
        FclMemoryFunctions.install(registry, extensions);
    }

    protected void registerUsers() {
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
                        if (transaction.auth().findVisibleUsername(process.ownerId())
                                .map(username -> username.equalsIgnoreCase(value)).orElse(false)) {
                            return true;
                        }
                        if (!isAdministrator()) return false;
                        return transaction.auth().findUsersByAdministrator(process.ownerId())
                                .stream().anyMatch(user -> user.username().equalsIgnoreCase(value));
                    }
                })
                .register("user", "list", args -> {
                    arity(args, 0, "user.list");
                    return transaction.auth().findUsersByAdministrator(process.ownerId()).stream()
                            .map(FclRuntimeFunctions::userMap).toList();
                })
                .register("user", "create", args -> {
                    if (args.size() < 2 || args.size() > 3) {
                        throw new FclRuntimeException("user.create expects 2 or 3 arguments, got "
                                + args.size());
                    }
                    String username = string(args.get(0), "user.create username");
                    String password = string(args.get(1), "user.create password");
                    Set<Capability> capabilities = AccountCapabilityProfiles.USER;
                    String administratorUsername = null;
                    String administratorPassword = null;
                    if (args.size() > 2) {
                        // Creating an administrator is a delegation: an existing
                        // administrator's identity and password must be supplied, and
                        // the database re-checks that identity's current effective
                        // SYSTEM_ADMIN atomically with the creation.
                        if (!(args.get(2) instanceof List<?> credentials)
                                || credentials.size() != 2) {
                            throw new FclRuntimeException(
                                    "user.create administrator credentials must be "
                                            + "[administratorUsername, administratorPassword]");
                        }
                        administratorUsername = string(credentials.get(0),
                                "user.create administrator username");
                        administratorPassword = string(credentials.get(1),
                                "user.create administrator password");
                        capabilities = AccountCapabilityProfiles.ADMIN;
                    }
                    String normalized = UsernamePolicy.normalize(username);
                    char[] secret = password.toCharArray();
                    char[] secretAdmin = administratorPassword == null
                            ? null : administratorPassword.toCharArray();
                    try {
                        PasswordPolicy.require(secret);
                        UserAccount created = transaction.auth().createUserByCredential(
                                administratorUsername, secretAdmin,
                                UUID.randomUUID(), normalized, secret, capabilities,
                                UUID.randomUUID(), now);
                        // The VFS root is provisioned idempotently on the new user's first login
                        // (TerminalAccessService.ensureRoot); user transactions cannot insert a
                        // node owned by another user under forced RLS.
                        return userMap(created);
                    } finally {
                        Arrays.fill(secret, '\0');
                        if (secretAdmin != null) Arrays.fill(secretAdmin, '\0');
                    }
                })
                .register("user", "disable", args -> {
                    arity(args, 1, "user.disable");
                    UUID userId = uuid(args.getFirst(), "user.disable user");
                    return userMap(transaction.auth().disableUserByAdministrator(
                            process.ownerId(), userId, UUID.randomUUID(), now));
                })
                .register("user", "remove", args -> {
                    arity(args, 1, "user.remove");
                    UUID userId = uuid(args.getFirst(), "user.remove user");
                    return transaction.auth().removeUserByAdministrator(
                            process.ownerId(), userId, UUID.randomUUID(), now);
                })
                .register("user", "switchUser", args -> unavailable("user.switchUser",
                        "a durable process identity cannot be changed in place"));
    }

    protected static Map<String, Object> userMap(UserAccount user) {
        return Map.of("userId", user.userId().toString(), "username", user.username(),
                "status", user.status().name(), "credentialVersion", user.credentialVersion());
    }

    protected static Map<String, Object> processMap(CilProcess process) {
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

    protected String readText(String path) {
        return readText(path, process.ownerId());
    }

    protected String readText(String path, UUID owner) {
        return decodeUtf8(readBytes(path, owner), "file.read");
    }

    /**
     * Resolves a file-shaped path through symbolic links to its underlying FILE node.
     * Each SYMLINK node stores its target path as its object content (file.link);
     * reading follows the chain while rejecting cycles and excessive depth.
     */
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

    /** Verifies a potentially large logical VFS file without materializing it in one array. */
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

    /**
     * Bounded whole-object read through the administrator path. The 16 MiB in-memory
     * cap is enforced before any bytes are materialized, so chunked manifests are
     * never loaded into one JVM array.
     */
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

    /** Publishes an already persisted logical object only after every download chunk arrived. */
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

    /**
     * Empties a directory's entire contents recursively and keeps the directory itself.
     * Roots are refused, mounts and versioned files abort the whole clear, and the
     * surrounding transaction rolls back so a failed call removes nothing.
     */
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

    protected boolean terminate(long pid) {
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
        transaction.timers().cancelForProcess(target.identity().processUid());
        audit("process.kill", target.identity().processUid(), Map.of("pid", Long.toString(pid)));
        return true;
    }

    protected boolean changeProcess(long pid, boolean pause) {
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

    protected Object waitForProcess(CilProcess target,
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

    /**
     * A fork child inherits its parent's execution context and terminal output route, but not
     * the terminal process's lifecycle markers: only the interactive terminal root pauses when
     * its appended bytecode runs out and waits for the next submission. Without this the child
     * would be treated as a terminal process and never reach TERMINATED after a natural end or
     * {@code util.exit()}.
     */
    protected static void stripTerminalLifecycle(FclContinuation runtime) {
        FclScope root = runtime.globalScope();
        preserveTerminalOutputRoute(root);
        removeIfPresent(root, InteractiveProcessState.PROCESS_SCOPE_KEY);
        removeIfPresent(root, InteractiveProcessState.SESSION_SCOPE_KEY);
        removeIfPresent(root, InteractiveProcessState.LIBRARY_SCOPE_KEY);
        for (FclContinuation.CallFrame frame : runtime.callStack()) {
            removeIfPresent(frame.callerScope(), InteractiveProcessState.PROCESS_SCOPE_KEY);
            removeIfPresent(frame.callerScope(), InteractiveProcessState.SESSION_SCOPE_KEY);
            removeIfPresent(frame.callerScope(), InteractiveProcessState.LIBRARY_SCOPE_KEY);
        }
    }

    private static void preserveTerminalOutputRoute(FclScope root) {
        if (!root.contains(InteractiveProcessState.OUTPUT_ROUTE_SCOPE_KEY)
                && root.contains(InteractiveProcessState.SESSION_SCOPE_KEY)) {
            root.put(InteractiveProcessState.OUTPUT_ROUTE_SCOPE_KEY,
                    root.get(InteractiveProcessState.SESSION_SCOPE_KEY));
        }
    }

    protected static void removeIfPresent(FclScope scope, String key) {
        if (scope.contains(key)) scope.remove(key);
    }

    protected CilProcess targetProcess(long pid, String operation) {        CilProcess target = transaction.processes().findByPid(pid)
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

    protected boolean isAdministrator() {
        return transaction.auth().capabilities(process.ownerId())
                .contains(Capability.SYSTEM_ADMIN);
    }

    protected void audit(String action, UUID resourceId, Map<String, String> details) {
        transaction.audit().append(new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                process.ownerId().toString(), action, auditResourceType(action),
                resourceId.toString(), AuditEvent.Result.SUCCEEDED, details, now));
    }

    static String auditResourceType(String action) {
        if (action.startsWith("effect")) {
            return "effect.effect";
        } else if (action.startsWith("process")) {
            return "process.process";
        } else if (action.startsWith("network.")) {
            return "network.request";
        } else if (action.startsWith("package.")) {
            return "package.binding";
        } else if (action.startsWith("program.")) {
            return "program.program";
        } else if (action.startsWith("terminal.")) {
            return "terminal.session";
        } else if (action.startsWith("timer.")) {
            return "process.timer";
        } else if (action.startsWith("audit.")) {
            return "audit.event";
        }
        return "vfs.node";
    }

    protected static void requireUpdated(ProcessRepository.UpdateResult result, String operation) {
        if (result != ProcessRepository.UpdateResult.UPDATED) {
            throw new FclRuntimeException(operation + " was rejected: " + result);
        }
    }

    protected static void requireType(VfsNode node, VfsNode.Type type, String operation) {
        if (node.type() != type) throw new FclRuntimeException(operation
                + " requires " + type.name().toLowerCase(java.util.Locale.ROOT));
    }

    protected static String path(List<Object> args, int index, int count, String function) {
        arity(args, count, function);
        return string(args.get(index), function + " path");
    }

    protected static long integerAt(List<Object> args, int index, int count, String function) {
        arity(args, count, function);
        return integer(args.get(index), function);
    }

    protected static long integer(Object value, String field) {
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

    protected static UUID uuid(Object value, String field) {
        try {
            return UUID.fromString(string(value, field));
        } catch (IllegalArgumentException failure) {
            throw new FclRuntimeException(field + " must be a UUID", failure);
        }
    }

    protected Optional<Instant> ipcExpiry(List<Object> args, int index, String field) {
        if (args.size() <= index || args.get(index) == null) return Optional.empty();
        Instant expiresAt = instant(args.get(index), field);
        if (!expiresAt.isAfter(now)) {
            throw new FclRuntimeException(field + " must be after the current time");
        }
        return Optional.of(expiresAt);
    }

    protected static Instant instant(Object value, String field) {
        try {
            return Instant.parse(string(value, field));
        } catch (java.time.format.DateTimeParseException failure) {
            throw new FclRuntimeException(field + " must be an ISO-8601 instant", failure);
        }
    }

    protected static String string(Object value, String field) {
        if (!(value instanceof String text)) throw new FclRuntimeException(field
                + " must be a string");
        return text;
    }

    protected static boolean bool(Object value, String field) {
        if (!(value instanceof Boolean result)) throw new FclRuntimeException(field
                + " must be a boolean");
        return result;
    }

    protected static String display(Object value) {
        return FclValues.display(value);
    }

    protected static String decodeUtf8(byte[] bytes, String operation) {
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

    protected static String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    protected static Object unavailable(String function, String reason) {
        throw new FclRuntimeException(function + " is unavailable: " + reason);
    }

    protected static void arity(List<Object> args, int expected, String function) {
        if (args.size() != expected) throw new FclRuntimeException(function + " expects "
                + expected + " arguments, got " + args.size());
    }

    protected record ParentAndName(VfsNode parent, String name) {}
    protected record RoutedPath(UUID ownerId, String path) {}
    protected Object external(FclFunctionRegistry.Invocation invocation, String effectType,
                            Map<String, Object> payload, EffectRequest.Policy policy,
                            boolean returnValue) {
        return new EffectInvocationService(transaction, process.ownerId(),
                process.identity().processUid(), now, codec).await(invocation.continuation(),
                new EffectInvocationService.Call(effectType, payload, policy, returnValue,
                        Map.of("effectType", effectType), "effect.request",
                        Map.of("effectType", effectType)), FclRuntimeFunctions::display);
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

    /**
     * Stable identity of a download attempt so terminal resubmissions cannot reuse stale
     * offset state. The destination path is part of the identity: resuming with the same
     * URL but a different target must not append new chunks to the old object.
     */
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

    protected Continuation.PersistedValue typed(Object value) {
        return new Continuation.PersistedValue(codec.valueType(value), codec.valueToJson(value));
    }

    protected static long positiveMillis(Object value, String field) {
        long millis = integer(value, field);
        if (millis < 1) throw new FclRuntimeException(field + " must be positive");
        return millis;
    }

    protected static Map<String, Object> lockMap(
            com.follarce.domain.port.IpcRepository.SwapLock lock) {
        return Map.of("fencingToken", lock.fencingToken(),
                "leaseUntil", lock.leaseUntil().toString());
    }

    protected static Map<String, Object> fileLockMap(
            com.follarce.domain.port.VfsRepository.FileLock lock) {
        return Map.of("fencingToken", lock.fencingToken(),
                "leaseUntil", lock.leaseUntil().toString());
    }


    protected Object terminalInput(FclFunctionRegistry.Invocation invocation, boolean oneCharacter) {
        return terminalInput(invocation, oneCharacter, false);
    }

    protected Object terminalInput(FclFunctionRegistry.Invocation invocation, boolean oneCharacter,
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
    protected Object readKey(FclFunctionRegistry.Invocation invocation, long timeout,
                           boolean coalesceText) {
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
        UUID timerId = timeout >= 0 ? UUID.randomUUID() : null;
        if (timerId != null) {
            transaction.timers().save(new ProcessTimer(timerId,
                    process.identity().processUid(), now.plus(Duration.ofMillis(timeout)),
                    ProcessTimer.Status.SCHEDULED, now, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(typed(TimerService.TERMINAL_INPUT_TIMEOUT))));
        }
        continuation.waitFor(timerId != null ? "input:key:" + timerId : "input:key",
                Map.of("rawKey", true, "coalesceText", coalesceText));
        throw FclSuspension.suspend();
    }

    /** Parses a terminal event payload into a structured FCL map. */
    protected static Object parseTerminalEvent(String input) {
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

    protected Program createProgram(String source) {
        FclProgram compiled = new FclCompiler().compile(source);
        StoredObject sourceObject = StoredObject.create(new BinaryContent(
                source.getBytes(StandardCharsets.UTF_8)), ProgramService.SOURCE_MEDIA_TYPE, now);
        StoredObject compiledObject = StoredObject.create(new BinaryContent(
                new FclProgramCodec().toBytes(compiled)), ProgramService.COMPILED_MEDIA_TYPE, now);
        transaction.vfs().saveObject(sourceObject);
        transaction.vfs().saveObject(compiledObject);
        int statements = Math.toIntExact(compiled.instructions().stream()
                .filter(instruction -> !(instruction instanceof FclInstruction.Jump)).count());
        return transaction.programs().saveIfAbsent(new Program(UUID.randomUUID(),
                sourceObject.objectHash(), ProgramService.LANGUAGE_VERSION,
                FclProgramCodec.FORMAT_VERSION, sourceObject.objectHash(),
                Optional.of(compiledObject.objectHash()), statements, now));
    }


    protected static Map<String, Object> packageMap(PackageRelease release) {
        return Map.of("coordinate", release.coordinate().key(),
                "name", release.coordinate().name(),
                "hash", release.packageHash().value().value(),
                "sha256", release.databaseFileHash().value(),
                "importedAt", release.importedAt().toString());
    }

}
