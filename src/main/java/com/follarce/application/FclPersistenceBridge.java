package com.follarce.application;

import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.program.Program;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Lossless boundary between the interpreter continuation and the durable domain model.
 *
 * <p>The FCL codec owns the runtime format. The domain projections remain useful for
 * queries and invariant checks, while the versioned envelope is the exact resumable
 * value. Keeping the envelope in the continuation also lets timer/effect/IPC services
 * add their own result variables without understanding interpreter internals.
 */
final class FclPersistenceBridge {
    static final String ENVELOPE_KEY = "cilexec.fcl.continuation";
    static final String ENVELOPE_TYPE =
            "application/vnd.cilexec.fcl-continuation+json;version=1";

    private final FclContinuationCodec codec;

    FclPersistenceBridge(FclContinuationCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    FclContinuation restore(Continuation persisted) {
        Objects.requireNonNull(persisted, "persisted");
        Continuation.PersistedValue envelope = persisted.globalVariables().get(ENVELOPE_KEY);
        if (envelope == null) {
            if (!isPristine(persisted)) {
                throw new IllegalStateException(
                        "Continuation has runtime state but no FCL persistence envelope");
            }
            return new FclContinuation();
        }
        if (!ENVELOPE_TYPE.equals(envelope.type())) {
            throw new IllegalStateException("Unsupported FCL continuation envelope: "
                    + envelope.type());
        }

        FclContinuation restored = codec.fromJson(envelope.canonicalPayload());
        if (restored.formatVersion() != runtimeFormat(persisted.runtimeFormatVersion())) {
            throw new IllegalStateException("FCL continuation runtime format mismatch");
        }
        if (restored.programCounter() != persisted.programCounter()) {
            throw new IllegalStateException("FCL continuation program counter mismatch");
        }
        restoreAuthoritativeScopes(persisted, restored);

        // Durable wait rows are authoritative for asynchronous wake-up. Services clear
        // the domain wait atomically when delivering a result, so mirror that transition
        // into the interpreter envelope at the next safe point.
        if (persisted.waitState().isEmpty()
                && restored.waitState().kind() != FclContinuation.WaitKind.NONE) {
            restored.clearWait();
        } else if (persisted.waitState().isPresent()
                && restored.waitState().kind() == FclContinuation.WaitKind.NONE) {
            Continuation.WaitState wait = persisted.waitState().orElseThrow();
            restored.waitFor(externalWaitKey(wait), Map.of());
        }
        injectDurableInbox(persisted, restored);
        return restored;
    }

    Continuation persist(UUID processUid, Program program, Continuation previous,
                         FclContinuation runtime) {
        Objects.requireNonNull(processUid, "processUid");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(runtime, "runtime");
        ensureProgramIdentity(program, previous);

        Map<String, Continuation.PersistedValue> globals =
                new LinkedHashMap<>(previous.globalVariables());
        ProcessInbox.keys().forEach(globals::remove);
        globals.put(ENVELOPE_KEY, new Continuation.PersistedValue(
                ENVELOPE_TYPE, codec.toJson(runtime)));

        List<Continuation.CallFrame> calls = projectCalls(processUid, program, runtime);
        List<Continuation.ScopeFrame> scopes = projectScopes(processUid, program, runtime);
        List<Continuation.ExceptionFrame> exceptions = projectExceptions(
                processUid, program, runtime);
        List<Continuation.ControlFrame> controls = projectControls(processUid, program, runtime);

        return new Continuation(program.programId(), program.programHash(),
                runtime.programCounter(), calls, scopes, exceptions, controls,
                projectWait(runtime.waitState()), Map.copyOf(globals),
                previous.packageBindings(), program.languageVersion(),
                Integer.toString(runtime.formatVersion()));
    }

    static void ensureProgramIdentity(Program program, Continuation continuation) {
        if (!program.programId().equals(continuation.programId())
                || !program.programHash().equals(continuation.programHash())) {
            throw new IllegalStateException("Process continuation points at a different program");
        }
        if (!program.languageVersion().equals(continuation.languageVersion())) {
            throw new IllegalStateException("Process continuation language version mismatch");
        }
        if (!Integer.toString(program.runtimeFormatVersion())
                .equals(continuation.runtimeFormatVersion())) {
            throw new IllegalStateException("Process continuation runtime format mismatch");
        }
    }

    private static boolean isPristine(Continuation continuation) {
        return continuation.programCounter() == 0
                && continuation.callStack().isEmpty()
                && continuation.scopeStack().isEmpty()
                && continuation.exceptionStack().isEmpty()
                && continuation.controlStack().isEmpty()
                && continuation.waitState().isEmpty()
                && continuation.globalVariables().isEmpty()
                && runtimeFormat(continuation.runtimeFormatVersion())
                == FclContinuation.FORMAT_VERSION;
    }

    private static int runtimeFormat(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Invalid runtime format version: " + value, failure);
        }
    }

    private static List<Continuation.CallFrame> projectCalls(
            UUID processUid, Program program, FclContinuation runtime) {
        List<Continuation.CallFrame> projected = new ArrayList<>();
        for (int index = 0; index < runtime.callStack().size(); index++) {
            FclContinuation.CallFrame frame = runtime.callStack().get(index);
            UUID scopeId = stableId(processUid, program.programId(), "scope", index);
            projected.add(new Continuation.CallFrame(
                    stableId(processUid, program.programId(), "call", index),
                    frame.functionName(), frame.returnPointer(), scopeId));
        }
        return List.copyOf(projected);
    }

    private List<Continuation.ScopeFrame> projectScopes(
            UUID processUid, Program program, FclContinuation runtime) {
        int count = runtime.callStack().size() + 1;
        List<Continuation.ScopeFrame> projected = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            UUID scopeId = stableId(processUid, program.programId(), "scope", index);
            Optional<UUID> parent = index == 0 ? Optional.empty()
                    : Optional.of(stableId(processUid, program.programId(), "scope", index - 1));
            Map<String, Object> values = index < runtime.callStack().size()
                    ? runtime.callStack().get(index).callerScope().values()
                    : runtime.scope().values();
            projected.add(new Continuation.ScopeFrame(scopeId, parent,
                    projectVariables(values)));
        }
        return List.copyOf(projected);
    }

    private Map<String, Continuation.PersistedValue> projectVariables(
            Map<String, Object> variables) {
        Map<String, Continuation.PersistedValue> projected = new LinkedHashMap<>();
        variables.forEach((name, value) -> projected.put(name,
                new Continuation.PersistedValue(codec.valueType(value),
                        codec.valueToJson(value))));
        return Map.copyOf(projected);
    }

    private void injectDurableInbox(Continuation persisted, FclContinuation runtime) {
        for (String key : ProcessInbox.keys()) {
            Continuation.PersistedValue value = persisted.globalVariables().get(key);
            if (value != null) {
                runtime.scope().put(key, decodeInboxValue(value));
            }
        }
    }

    private Object decodeInboxValue(Continuation.PersistedValue value) {
        return switch (value.type()) {
            case "null", "bool", "long", "double", "string", "array", "map" ->
                    codec.valueFromJson(value.canonicalPayload());
            case "json", "application/json" ->
                    codec.documentFromJson(value.canonicalPayload());
            default -> value.type().endsWith("+json")
                    ? codec.documentFromJson(value.canonicalPayload())
                    : value.canonicalPayload();
        };
    }

    private void restoreAuthoritativeScopes(Continuation persisted,
                                            FclContinuation runtime) {
        if (persisted.scopeStack().size() != runtime.callStack().size() + 1) {
            throw new IllegalStateException(
                    "Normalized scope projection does not match FCL call depth");
        }
        List<Map<String, Object>> scopes = new ArrayList<>(persisted.scopeStack().size());
        for (Continuation.ScopeFrame scope : persisted.scopeStack()) {
            Map<String, Object> values = new LinkedHashMap<>();
            scope.variables().forEach((name, value) -> {
                Object decoded = codec.valueFromJson(value.canonicalPayload());
                if (!codec.valueType(decoded).equals(value.type())) {
                    throw new IllegalStateException(
                            "Normalized variable type disagrees with its payload: " + name);
                }
                values.put(name, decoded);
            });
            scopes.add(Map.copyOf(values));
        }
        runtime.restoreProjectedScopes(scopes);
    }

    private static List<Continuation.ExceptionFrame> projectExceptions(
            UUID processUid, Program program, FclContinuation runtime) {
        List<Continuation.ExceptionFrame> projected = new ArrayList<>();
        for (int index = 0; index < runtime.exceptionStack().size(); index++) {
            FclContinuation.ExceptionFrame frame = runtime.exceptionStack().get(index);
            int scopeIndex = Math.min(frame.callDepth(), runtime.callStack().size());
            projected.add(new Continuation.ExceptionFrame(frame.instructionPointer(),
                    stableId(processUid, program.programId(), "scope", scopeIndex),
                    Optional.of(new Continuation.PersistedValue(
                            "fcl.exception." + frame.type(), frame.message()))));
        }
        return List.copyOf(projected);
    }

    private static List<Continuation.ControlFrame> projectControls(
            UUID processUid, Program program, FclContinuation runtime) {
        List<Continuation.ControlFrame> projected = new ArrayList<>();
        for (FclContinuation.LoopFrame loop : runtime.loopState()) {
            int scopeIndex = Math.min(loop.callDepth(), runtime.callStack().size());
            projected.add(new Continuation.ControlFrame(Continuation.ControlKind.LOOP,
                    loop.headerPointer(), loop.endPointer(),
                    stableId(processUid, program.programId(), "scope", scopeIndex)));
        }
        for (FclContinuation.BranchFrame branch : runtime.branchState()) {
            int scopeIndex = Math.min(branch.callDepth(), runtime.callStack().size());
            projected.add(new Continuation.ControlFrame(Continuation.ControlKind.BRANCH,
                    0, branch.endPointer(),
                    stableId(processUid, program.programId(), "scope", scopeIndex)));
        }
        return List.copyOf(projected);
    }

    private static Optional<Continuation.WaitState> projectWait(
            FclContinuation.WaitState wait) {
        if (wait.kind() == FclContinuation.WaitKind.NONE) return Optional.empty();
        if (wait.kind() == FclContinuation.WaitKind.EXTERNAL) {
            Optional<Continuation.WaitState> parsed = parseExternalWait(wait.key());
            if (parsed.isPresent()) return parsed;
        }
        UUID target = UUID.nameUUIDFromBytes((wait.kind().name() + ":" + wait.key())
                .getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Continuation.WaitState(Continuation.WaitKind.EFFECT,
                Optional.of(target), Optional.empty()));
    }

    private static Optional<Continuation.WaitState> parseExternalWait(String key) {
        if (key == null) return Optional.empty();
        if (key.equals("input") || key.startsWith("input:")) {
            return Optional.of(new Continuation.WaitState(Continuation.WaitKind.INPUT,
                    Optional.empty(), Optional.empty()));
        }
        int separator = key.indexOf(':');
        if (separator < 1 || separator == key.length() - 1) return Optional.empty();
        String kind = key.substring(0, separator);
        try {
            UUID target = UUID.fromString(key.substring(separator + 1));
            Continuation.WaitKind waitKind = switch (kind) {
                case "ipc" -> Continuation.WaitKind.IPC;
                case "timer" -> Continuation.WaitKind.TIMER;
                case "effect" -> Continuation.WaitKind.EFFECT;
                default -> null;
            };
            return waitKind == null ? Optional.empty()
                    : Optional.of(new Continuation.WaitState(waitKind,
                    Optional.of(target), Optional.empty()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static String externalWaitKey(Continuation.WaitState wait) {
        return switch (wait.kind()) {
            case INPUT -> "input";
            case IPC -> "ipc:" + requiredTarget(wait);
            case TIMER -> "timer:" + requiredTarget(wait);
            case EFFECT -> "effect:" + requiredTarget(wait);
            case CHILD -> "child:" + wait.targetPid().map(Object::toString).orElse("unknown");
            case PROCESS -> "process:" + wait.targetPid().map(Object::toString).orElse("unknown");
        };
    }

    private static UUID requiredTarget(Continuation.WaitState wait) {
        return wait.targetId().orElseThrow(
                () -> new IllegalStateException(wait.kind() + " wait has no target ID"));
    }

    private static UUID stableId(UUID processUid, UUID programId, String kind, int index) {
        return UUID.nameUUIDFromBytes((processUid + ":" + programId + ":" + kind + ":" + index)
                .getBytes(StandardCharsets.UTF_8));
    }
}
