package com.follarce.fcl;

import java.util.List;
import java.time.Duration;

/** Runs a pure function with disposable interpreter state inside the caller's slice. */
final class FclVolatileFunction {
    private static final long MAX_STEPS = 10_000_000L;
    private static final long MAX_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int MAX_NESTED_CALLS = 64;
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private FclVolatileFunction() {}

    static Object call(FclProgram program, FclProgram.Function function, List<Object> arguments) {
        return call(program, function, arguments, MAX_STEPS, MAX_NANOS);
    }

    static Object call(FclProgram program, FclProgram.Function function, List<Object> arguments,
                       long maxSteps, long maxNanos) {
        if (maxSteps < 1 || maxNanos < 1) throw new IllegalArgumentException("Positive limits required");
        int previousDepth = DEPTH.get();
        if (previousDepth >= MAX_NESTED_CALLS) {
            throw new FclRuntimeException("VolatileLimitExceeded",
                    "Volatile function nesting exceeds " + MAX_NESTED_CALLS);
        }
        DEPTH.set(previousDepth + 1);
        try {
            return execute(program, function, arguments, maxSteps, maxNanos);
        } finally {
            if (previousDepth == 0) DEPTH.remove();
            else DEPTH.set(previousDepth);
        }
    }

    private static Object execute(FclProgram program, FclProgram.Function function,
                                  List<Object> arguments, long maxSteps, long maxNanos) {
        if (arguments.size() != function.parameters().size()) {
            throw new FclRuntimeException("InvalidArgument", "Function expects "
                    + function.parameters().size() + " arguments, got " + arguments.size());
        }
        FclContinuation state = new FclContinuation();
        FclScope scope = new FclScope();
        for (int i = 0; i < arguments.size(); i++) {
            scope.put(function.parameters().get(i), FclValues.deepCopy(arguments.get(i)));
        }
        state.scope(scope);
        state.programCounter(function.entryPoint());
        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry(), false);
        long started = System.nanoTime();
        long steps = 0;
        while (!state.halted()) {
            if (steps++ >= maxSteps || System.nanoTime() - started >= maxNanos) {
                throw new FclRuntimeException("VolatileLimitExceeded",
                        "Volatile function exceeded its execution budget");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new FclRuntimeException("Volatile function interrupted");
            }
            FclStepResult step = runtime.executeOne(program, state);
            if (step.status() == FclStepResult.Status.WAITING
                    || step.status() == FclStepResult.Status.DIRECTIVE) {
                throw new FclRuntimeException("Volatile functions cannot suspend or use directives");
            }
            if (step.status() == FclStepResult.Status.FAILED) {
                Object failure = state.result();
                if (failure instanceof FclExceptionValue exception) {
                    throw new FclRuntimeException(exception.type(), exception.message());
                }
                throw new FclRuntimeException("Volatile function failed: " + step.value());
            }
        }
        return FclValues.deepCopy(state.result());
    }
}
