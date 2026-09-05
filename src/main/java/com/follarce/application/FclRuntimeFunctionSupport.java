package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.program.Program;
import com.follarce.domain.timer.ProcessTimer;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.domain.vfs.VfsFileLimits;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclInstruction;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclScope;
import com.follarce.fcl.FclValues;
import com.follarce.fcl.FclSuspension;
import com.follarce.extension.JavaExtensionCatalog;
import com.follarce.timer.TimerService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Consumer;

/** Shared, statement-scoped capabilities used by focused FCL function registrars. */
abstract class FclRuntimeFunctionSupport {
    FclRuntimeFunctionSupport(FclRuntimeFunctionSupport source) {
        this(source.transaction, source.process, source.program, source.continuation, source.now,
                source.extensions, source.registry, source.volatileProcessRequests,
                source.processOutputs);
    }

    FclRuntimeFunctionSupport(TransactionContext transaction, CilProcess process, Program program,
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

































































































    /**
     * Empties a directory's entire contents recursively and keeps the directory itself.
     * Roots are refused, mounts and versioned files abort the whole clear, and the
     * surrounding transaction rolls back so a failed call removes nothing.
     */
































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

    protected CilProcess targetProcess(long pid, String operation) {
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







    /**
     * Stable identity of a download attempt so terminal resubmissions cannot reuse stale
     * offset state. The destination path is part of the identity: resuming with the same
     * URL but a different target must not append new chunks to the old object.
     */




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

