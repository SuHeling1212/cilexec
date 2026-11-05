package com.follarce.fcl;

import java.util.List;

/** Runs a pure function with disposable interpreter state inside the caller's slice. */
final class FclVolatileFunction {
    private FclVolatileFunction() {}

    static Object call(FclProgram program, FclProgram.Function function, List<Object> arguments) {
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
        while (!state.halted()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new FclRuntimeException("Volatile function interrupted");
            }
            FclStepResult step = runtime.executeOne(program, state);
            if (step.status() == FclStepResult.Status.WAITING
                    || step.status() == FclStepResult.Status.DIRECTIVE) {
                throw new FclRuntimeException("Volatile functions cannot suspend or use directives");
            }
            if (step.status() == FclStepResult.Status.FAILED) {
                throw new FclRuntimeException("Volatile function failed: " + step.value());
            }
        }
        return FclValues.deepCopy(state.result());
    }
}
