package com.follarce.domain.process;

import com.follarce.domain.Invariant;
import com.follarce.domain.vfs.ObjectHash;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Complete, persistence-safe interpreter continuation. */
public record Continuation(
        UUID programId,
        ObjectHash programHash,
        int programCounter,
        List<CallFrame> callStack,
        List<ScopeFrame> scopeStack,
        List<ExceptionFrame> exceptionStack,
        List<ControlFrame> controlStack,
        Optional<WaitState> waitState,
        Map<String, PersistedValue> globalVariables,
        Map<String, ObjectHash> packageBindings,
        String languageVersion,
        String runtimeFormatVersion
) {
    public Continuation {
        Invariant.required(programId, "programId");
        Invariant.required(programHash, "programHash");
        Invariant.nonNegative(programCounter, "programCounter");
        callStack = Invariant.list(callStack, "callStack");
        scopeStack = Invariant.list(scopeStack, "scopeStack");
        exceptionStack = Invariant.list(exceptionStack, "exceptionStack");
        controlStack = Invariant.list(controlStack, "controlStack");
        waitState = Invariant.required(waitState, "waitState");
        globalVariables = Invariant.map(globalVariables, "globalVariables");
        packageBindings = Invariant.map(packageBindings, "packageBindings");
        languageVersion = Invariant.text(languageVersion, "languageVersion");
        runtimeFormatVersion = Invariant.text(runtimeFormatVersion, "runtimeFormatVersion");
    }

    public Continuation advanceTo(int nextProgramCounter) {
        Invariant.nonNegative(nextProgramCounter, "nextProgramCounter");
        return new Continuation(programId, programHash, nextProgramCounter, callStack, scopeStack,
                exceptionStack, controlStack, Optional.empty(), globalVariables, packageBindings,
                languageVersion, runtimeFormatVersion);
    }

    public Continuation withWait(Optional<WaitState> wait) {
        return new Continuation(programId, programHash, programCounter, callStack, scopeStack,
                exceptionStack, controlStack, Invariant.required(wait, "wait"),
                globalVariables, packageBindings, languageVersion, runtimeFormatVersion);
    }

    public Continuation withoutWait() {
        return withWait(Optional.empty());
    }

    public Continuation withGlobalVariables(Map<String, PersistedValue> variables) {
        return new Continuation(programId, programHash, programCounter, callStack, scopeStack,
                exceptionStack, controlStack, waitState,
                Invariant.map(variables, "variables"), packageBindings,
                languageVersion, runtimeFormatVersion);
    }

    /** Removes delivery values that must never leak into a fork or a terminal process. */
    public Continuation withoutTransientInbox() {
        Map<String, PersistedValue> retained = new java.util.LinkedHashMap<>(globalVariables);
        ProcessInbox.keys().forEach(retained::remove);
        return withGlobalVariables(Map.copyOf(retained));
    }

    public record CallFrame(
            UUID frameId,
            String functionName,
            int returnAddress,
            UUID scopeId
    ) {
        public CallFrame {
            Invariant.required(frameId, "frameId");
            functionName = Invariant.text(functionName, "functionName");
            Invariant.nonNegative(returnAddress, "returnAddress");
            Invariant.required(scopeId, "scopeId");
        }
    }

    public record ScopeFrame(
            UUID scopeId,
            Optional<UUID> parentScopeId,
            Map<String, PersistedValue> variables
    ) {
        public ScopeFrame {
            Invariant.required(scopeId, "scopeId");
            parentScopeId = Invariant.required(parentScopeId, "parentScopeId");
            variables = Invariant.map(variables, "variables");
            Invariant.check(parentScopeId.isEmpty() || !parentScopeId.get().equals(scopeId),
                    "scope cannot be its own parent");
        }
    }

    public record ExceptionFrame(
            int handlerAddress,
            UUID scopeId,
            Optional<PersistedValue> pendingException
    ) {
        public ExceptionFrame {
            Invariant.nonNegative(handlerAddress, "handlerAddress");
            Invariant.required(scopeId, "scopeId");
            pendingException = Invariant.required(pendingException, "pendingException");
        }
    }

    public record ControlFrame(
            ControlKind kind,
            int entryAddress,
            int exitAddress,
            UUID scopeId
    ) {
        public ControlFrame {
            Invariant.required(kind, "kind");
            Invariant.nonNegative(entryAddress, "entryAddress");
            Invariant.nonNegative(exitAddress, "exitAddress");
            Invariant.check(exitAddress >= entryAddress,
                    "control frame exit must not precede its entry");
            Invariant.required(scopeId, "scopeId");
        }
    }

    public enum ControlKind {
        BLOCK,
        BRANCH,
        LOOP,
        FUNCTION
    }

    public record WaitState(
            WaitKind kind,
            Optional<UUID> targetId,
            Optional<Long> targetPid
    ) {
        public WaitState {
            Invariant.required(kind, "kind");
            targetId = Invariant.required(targetId, "targetId");
            targetPid = Invariant.required(targetPid, "targetPid");
            targetPid.ifPresent(value -> Invariant.positive(value, "targetPid"));
            if (kind.requiresTargetId()) {
                Invariant.check(targetId.isPresent(), kind + " requires a target ID");
            }
            if (kind == WaitKind.PROCESS || kind == WaitKind.CHILD) {
                Invariant.check(targetPid.isPresent(), kind + " wait requires a target PID");
            }
        }
    }

    public enum WaitKind {
        IPC(true),
        TIMER(true),
        EFFECT(true),
        INPUT(false),
        CHILD(false),
        PROCESS(false);

        private final boolean requiresTargetId;

        WaitKind(boolean requiresTargetId) {
            this.requiresTargetId = requiresTargetId;
        }

        public boolean requiresTargetId() {
            return requiresTargetId;
        }
    }

    /** Canonical, immutable representation of an FCL value. */
    public record PersistedValue(String type, String canonicalPayload) {
        public PersistedValue {
            type = Invariant.text(type, "type");
            canonicalPayload = Invariant.required(canonicalPayload, "canonicalPayload");
        }
    }
}
