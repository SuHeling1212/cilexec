package com.follarce.application;

import com.follarce.fcl.FclBuiltins;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.fcl.FclRuntimeException;

import java.util.Objects;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Executes deliberately non-durable FCL processes.
 *
 * <p>No database adapter is reachable from this service: a task receives only pure FCL
 * built-ins, and its source, continuation, result, failure, and lifecycle disappear with the
 * JVM.  A shutdown interrupts all running tasks rather than trying to recover them.
 */
final class VolatileProcessService implements AutoCloseable {
    private final ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Consumer<VolatileProcessCompletion> completion;

    VolatileProcessService() {
        this(ignored -> { });
    }

    /** Visible to package tests; production intentionally retains no completion history. */
    VolatileProcessService(Consumer<VolatileProcessCompletion> completion) {
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    void launch(VolatileProcessRequest request) {
        Objects.requireNonNull(request, "request");
        if (!accepting.get()) {
            return;
        }
        try {
            executor.submit(() -> execute(request));
        } catch (RejectedExecutionException ignored) {
            // Shutdown raced with the post-commit launch. Volatile work is intentionally lost.
        }
    }

    private void execute(VolatileProcessRequest request) {
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(registry(request.arguments()));
        VolatileProcessCompletion completed = null;
        try {
            while (!continuation.halted()) {
                if (Thread.currentThread().isInterrupted()) return;
                FclStepResult step = runtime.executeOne(request.program(), continuation);
                if (step.status() == FclStepResult.Status.WAITING
                        || step.status() == FclStepResult.Status.DIRECTIVE) {
                    throw new IllegalStateException("volatile processes cannot suspend");
                }
                if (step.status() == FclStepResult.Status.FAILED) {
                    throw new IllegalStateException(String.valueOf(step.value()));
                }
            }
            completed = VolatileProcessCompletion.success(request, continuation.result());
        } catch (RuntimeException runtimeFailure) {
            completed = VolatileProcessCompletion.failure(request, message(runtimeFailure));
        } finally {
            if (completed != null) completion.accept(completed);
        }
    }

    private static FclFunctionRegistry registry(List<Object> arguments) {
        FclFunctionRegistry registry = FclBuiltins.pureRegistry();
        registry.register("process", "args", supplied -> {
            arity(supplied, 0, "process.args");
            return arguments;
        }).register("process", "arg", supplied -> {
            arity(supplied, 1, "process.arg");
            long index = index(supplied.getFirst());
            if (index < 0 || index >= arguments.size()) {
                throw new FclRuntimeException("process.arg index is out of range");
            }
            return arguments.get((int) index);
        });
        return registry;
    }

    private static long index(Object value) {
        if (value instanceof Number number && number.doubleValue() == number.longValue()) {
            return number.longValue();
        }
        throw new FclRuntimeException("process.arg index must be an integer");
    }

    private static void arity(List<Object> supplied, int expected, String function) {
        if (supplied.size() != expected) {
            throw new FclRuntimeException(function + " expects " + expected + " arguments, got "
                    + supplied.size());
        }
    }

    private static String message(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        if (accepting.compareAndSet(true, false)) {
            executor.shutdownNow();
        }
    }
}
