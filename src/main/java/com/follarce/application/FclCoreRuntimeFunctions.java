package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.port.EnvironmentRepository;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.timer.ProcessTimer;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclScope;
import com.follarce.fcl.TerminalModeState;
import com.follarce.fcl.FclSuspension;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Registers path, environment, utility, I/O, resource-control, and memory APIs. */
final class FclCoreRuntimeFunctions extends FclVfsRuntimeSupport {
    FclCoreRuntimeFunctions(FclVfsRuntimeSupport source) {
        super(source);
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

}
