package com.follarce.fcl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete persisted state required to resume FCL at a statement boundary.
 *
 * <p>The state deliberately contains no thread, callback, JDBC object, or host handle.
 *
 * <p>The continuation format is coupled to the executable program format. V004 is the active
 * write format; V002 and V003 remain readable so an upgraded Runtime can finish an
 * already-persisted process before it is explicitly recompiled.
 */
public final class FclContinuation {
    public static final int FORMAT_VERSION = FclProgramCodec.FORMAT_VERSION;

    public enum WaitKind {
        NONE,
        IMPORT,
        INCLUDE,
        EXTERNAL
    }

    public record PendingStatement(int instructionPointer, Map<Long, Object> callResults) {
        public PendingStatement {
            Map<Long, Object> copy = new LinkedHashMap<>();
            if (callResults != null) {
                callResults.forEach((key, value) -> copy.put(key, FclValues.deepCopy(value)));
            }
            callResults = Collections.unmodifiableMap(copy);
        }

        public PendingStatement(int instructionPointer) {
            this(instructionPointer, Map.of());
        }

        public boolean hasResult(long expressionId) {
            return callResults.containsKey(expressionId);
        }

        public Object result(long expressionId) {
            return FclValues.deepCopy(callResults.get(expressionId));
        }

        public PendingStatement withResult(long expressionId, Object value) {
            Map<Long, Object> copy = new LinkedHashMap<>(callResults);
            copy.put(expressionId, FclValues.deepCopy(value));
            return new PendingStatement(instructionPointer, copy);
        }
    }

    public record CallFrame(int returnPointer, FclScope callerScope,
                            PendingStatement callerPending, long callExpressionId,
                            String functionName, String receiverPath, boolean construction) {
        public CallFrame {
            callerScope = Objects.requireNonNull(callerScope, "callerScope");
            callerPending = callerPending == null ? null
                    : new PendingStatement(callerPending.instructionPointer(),
                    callerPending.callResults());
            Objects.requireNonNull(functionName, "functionName");
        }

    }

    public record ExceptionFrame(int instructionPointer, int sourceLine, String type,
                                 String message, int callDepth) {
        public ExceptionFrame {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(message, "message");
        }
    }

    /** A persisted try/catch region; no Java call-stack state is required to resume it. */
    public record ExceptionHandlerFrame(int catchTarget, int catchEndTarget, String variable,
                                        int callDepth, boolean handling,
                                        boolean hadPreviousBinding, Object previousValue) {
        public ExceptionHandlerFrame {
            Objects.requireNonNull(variable, "variable");
            previousValue = FclValues.deepCopy(previousValue);
        }

        public ExceptionHandlerFrame asHandling() {
            return new ExceptionHandlerFrame(catchTarget, catchEndTarget, variable, callDepth,
                    true, hadPreviousBinding, previousValue);
        }
    }

    public record LoopFrame(int headerPointer, int endPointer, int callDepth) {}

    public record BranchFrame(int endPointer, int callDepth, boolean taken) {}

    public record WaitState(WaitKind kind, String key, Map<String, Object> payload) {
        public WaitState {
            Objects.requireNonNull(kind, "kind");
            Map<String, Object> copy = new LinkedHashMap<>();
            if (payload != null) {
                payload.forEach((name, value) -> copy.put(name, FclValues.deepCopy(value)));
            }
            payload = Collections.unmodifiableMap(copy);
        }

        public static WaitState ready() {
            return new WaitState(WaitKind.NONE, null, Map.of());
        }

        public static WaitState directive(WaitKind kind, String target,
                                          Map<String, Object> payload) {
            if (kind != WaitKind.IMPORT && kind != WaitKind.INCLUDE) {
                throw new IllegalArgumentException("A directive must be IMPORT or INCLUDE");
            }
            return new WaitState(kind, target, payload);
        }
    }

    private final int formatVersion;
    private int programCounter;
    private FclScope scope;
    private final List<CallFrame> callStack;
    private final List<ExceptionFrame> exceptionStack;
    private final List<ExceptionHandlerFrame> exceptionHandlers;
    private final List<LoopFrame> loopState;
    private final List<BranchFrame> branchState;
    private WaitState waitState;
    private PendingStatement pendingStatement;
    private boolean halted;
    private boolean failed;
    private Object result;

    public FclContinuation() {
        this(FORMAT_VERSION, 0, new FclScope(), List.of(), List.of(), List.of(), List.of(),
                List.of(), WaitState.ready(), null, false, false, null);
    }

    /** Creates a pristine continuation for a supported persisted program format. */
    public FclContinuation(int formatVersion) {
        this(formatVersion, 0, new FclScope(), List.of(), List.of(), List.of(), List.of(),
                List.of(), WaitState.ready(), null, false, false, null);
    }

    private FclContinuation(int formatVersion, int programCounter, FclScope scope,
                            List<CallFrame> callStack,
                            List<ExceptionFrame> exceptionStack,
                            List<ExceptionHandlerFrame> exceptionHandlers,
                            List<LoopFrame> loopState,
                            List<BranchFrame> branchState,
                            WaitState waitState,
                            PendingStatement pendingStatement,
                            boolean halted, boolean failed, Object result) {
        if (!FclProgramCodec.supportsFormat(formatVersion)) {
            throw new IllegalArgumentException("Unsupported continuation format: "
                    + formatVersion);
        }
        this.formatVersion = formatVersion;
        this.programCounter = programCounter;
        this.scope = Objects.requireNonNull(scope, "scope").copy();
        this.callStack = new ArrayList<>();
        for (CallFrame frame : callStack) {
            this.callStack.add(new CallFrame(frame.returnPointer(), frame.callerScope().copy(),
                    frame.callerPending(), frame.callExpressionId(), frame.functionName(),
                    frame.receiverPath(), frame.construction()));
        }
        this.exceptionStack = new ArrayList<>(exceptionStack);
        this.exceptionHandlers = new ArrayList<>(exceptionHandlers);
        this.loopState = new ArrayList<>(loopState);
        this.branchState = new ArrayList<>(branchState);
        WaitState persistedWait = Objects.requireNonNull(waitState, "waitState");
        this.waitState = new WaitState(persistedWait.kind(), persistedWait.key(),
                persistedWait.payload());
        this.pendingStatement = pendingStatement == null ? null
                : new PendingStatement(pendingStatement.instructionPointer(),
                pendingStatement.callResults());
        this.halted = halted;
        this.failed = failed;
        this.result = FclValues.deepCopy(result);
    }

    public int formatVersion() {
        return formatVersion;
    }

    public int programCounter() {
        return programCounter;
    }

    public FclScope scope() {
        return scope;
    }

    /** Resolves a base-program variable in the current invocation and then its global scope. */
    public Object variable(String name) {
        if (scope.contains(name)) return scope.get(name);
        FclScope root = globalScope();
        if (root != scope && root.contains(name)) return root.get(name);
        return scope.get(name);
    }

    /** Durable outermost scope retained across function returns and terminal submissions. */
    public FclScope globalScope() {
        return callStack.isEmpty() ? scope : callStack.getFirst().callerScope();
    }

    public List<CallFrame> callStack() {
        return List.copyOf(callStack);
    }

    public List<ExceptionFrame> exceptionStack() {
        return List.copyOf(exceptionStack);
    }

    public List<ExceptionHandlerFrame> exceptionHandlers() {
        return List.copyOf(exceptionHandlers);
    }

    public List<LoopFrame> loopState() {
        return List.copyOf(loopState);
    }

    public List<BranchFrame> branchState() {
        return List.copyOf(branchState);
    }

    public WaitState waitState() {
        return waitState;
    }

    public PendingStatement pendingStatement() {
        return pendingStatement;
    }

    public boolean halted() {
        return halted;
    }

    public boolean failed() {
        return failed;
    }

    public Object result() {
        return FclValues.deepCopy(result);
    }

    public void clearWait() {
        waitState = WaitState.ready();
    }

    public void waitFor(String key, Map<String, Object> payload) {
        if (halted) {
            throw new IllegalStateException("A halted continuation cannot wait");
        }
        waitState = new WaitState(WaitKind.EXTERNAL, key, payload);
    }

    /** Requests normal process completion from a host function such as {@code util.exit}. */
    public void exit(Object value) {
        halt(value);
    }

    /** Seeds the result of the current call in a cloned continuation, used by durable fork. */
    public void cacheCallResult(long expressionId, Object value) {
        if (pendingStatement == null) {
            pendingStatement = new PendingStatement(programCounter);
        }
        pendingStatement = pendingStatement.withResult(expressionId, value);
    }

    /** Completes a host-resolved directive with a durable interpreter failure. */
    public void rejectDirective(String message) {
        if (waitState.kind() != WaitKind.IMPORT && waitState.kind() != WaitKind.INCLUDE) {
            throw new IllegalStateException("No import/include directive is pending");
        }
        String safeMessage = Objects.requireNonNull(message, "message");
        exceptionStack.add(new ExceptionFrame(programCounter, -1,
                "DirectiveResolutionException", safeMessage, callDepth()));
        fail(safeMessage);
    }

    /** Replaces runtime variable scopes from PostgreSQL's normalized authority. */
    public void restoreProjectedScopes(List<Map<String, Object>> projectedScopes) {
        Objects.requireNonNull(projectedScopes, "projectedScopes");
        if (projectedScopes.size() != callStack.size() + 1) {
            throw new IllegalArgumentException("Projected scope count does not match call depth");
        }
        for (int index = 0; index < callStack.size(); index++) {
            CallFrame current = callStack.get(index);
            callStack.set(index, new CallFrame(current.returnPointer(),
                    new FclScope(projectedScopes.get(index)), current.callerPending(),
                    current.callExpressionId(), current.functionName(), current.receiverPath(),
                    current.construction()));
        }
        scope = new FclScope(projectedScopes.getLast());
    }

    public FclContinuation snapshot() {
        return new FclContinuation(formatVersion, programCounter, scope, callStack,
                exceptionStack, exceptionHandlers, loopState, branchState, waitState, pendingStatement,
                halted, failed, result);
    }

    /**
     * Prepares the next terminal submission without replacing the process context.
     * Execution-only state is cleared while the durable outermost/global scope is
     * retained. If the previous input failed inside a function, its local frames are
     * deliberately discarded and the caller's global scope survives.
     */
    public FclContinuation nextSubmission() {
        if (!halted) {
            throw new IllegalStateException(
                    "Only a completed continuation can accept the next submission");
        }
        FclScope global = globalScope();
        return new FclContinuation(formatVersion, 0, global, List.of(), List.of(),
                List.of(), List.of(), List.of(), WaitState.ready(), null, false, false, null);
    }

    /**
     * Cancels the current terminal submission while retaining only its durable global scope.
     *
     * <p>Unlike {@link #nextSubmission()}, this may be called while the process is still
     * waiting for host input (for example by
     * {@code ProcessStatementExecutor.cancelTerminalSubmission}): the pending submission and
     * any in-flight call frames are discarded and the continuation is returned in a halted
     * terminal state.
     */
    public FclContinuation cancelSubmission() {
        FclScope global = globalScope();
        return new FclContinuation(formatVersion, 0, global, List.of(), List.of(),
                List.of(), List.of(), List.of(), WaitState.ready(), null, true, false, null);
    }

    static FclContinuation restore(int formatVersion, int programCounter, FclScope scope,
                                   List<CallFrame> callStack,
                                   List<ExceptionFrame> exceptionStack,
                                   List<ExceptionHandlerFrame> exceptionHandlers,
                                   List<LoopFrame> loopState,
                                   List<BranchFrame> branchState,
                                   WaitState waitState,
                                   PendingStatement pendingStatement,
                                   boolean halted, boolean failed, Object result) {
        return new FclContinuation(formatVersion, programCounter, scope, callStack,
                exceptionStack, exceptionHandlers, loopState, branchState, waitState, pendingStatement,
                halted, failed, result);
    }

    int callDepth() {
        return callStack.size();
    }

    void programCounter(int value) {
        programCounter = value;
    }

    void scope(FclScope value) {
        scope = Objects.requireNonNull(value, "scope");
    }

    List<CallFrame> mutableCallStack() {
        return callStack;
    }

    List<ExceptionFrame> mutableExceptionStack() {
        return exceptionStack;
    }

    List<ExceptionHandlerFrame> mutableExceptionHandlers() {
        return exceptionHandlers;
    }

    List<LoopFrame> mutableLoopState() {
        return loopState;
    }

    List<BranchFrame> mutableBranchState() {
        return branchState;
    }

    void waitState(WaitState value) {
        waitState = Objects.requireNonNull(value, "waitState");
    }

    void pendingStatement(PendingStatement value) {
        pendingStatement = value;
    }

    void halt(Object value) {
        halted = true;
        failed = false;
        result = FclValues.deepCopy(value);
        pendingStatement = null;
        waitState = WaitState.ready();
    }

    void fail(Object value) {
        halted = true;
        failed = true;
        result = FclValues.deepCopy(value);
        pendingStatement = null;
        waitState = WaitState.ready();
    }
}
