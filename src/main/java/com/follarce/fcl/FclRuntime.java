package com.follarce.fcl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Statement-at-a-time FCL interpreter. */
public final class FclRuntime {
    private final FclFunctionRegistry functions;

    public FclRuntime(FclFunctionRegistry functions) {
        this.functions = Objects.requireNonNull(functions, "functions");
    }

    public FclStepResult executeOne(FclProgram program, FclContinuation continuation) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(continuation, "continuation");
        int initialPointer = continuation.programCounter();
        if (continuation.halted()) {
            return result(continuation.failed() ? FclStepResult.Status.FAILED
                    : FclStepResult.Status.COMPLETED, initialPointer, continuation,
                    -1, continuation.result());
        }
        if (continuation.waitState().kind() != FclContinuation.WaitKind.NONE) {
            return result(FclStepResult.Status.WAITING, initialPointer, continuation,
                    -1, null);
        }

        try {
            normalizePointer(program, continuation);
            if (continuation.programCounter() >= program.instructions().size()) {
                continuation.halt(continuation.result());
                return result(FclStepResult.Status.COMPLETED, initialPointer, continuation,
                        -1, continuation.result());
            }
            if (continuation.programCounter() < 0) {
                throw new FclRuntimeException("Negative instruction pointer");
            }

            int pointer = continuation.programCounter();
            FclInstruction instruction = program.instructions().get(pointer);
            ensurePending(continuation, pointer);
            return executeInstruction(program, continuation, instruction, pointer);
        } catch (FclExpressionEvaluator.UserCallSignal call) {
            return enterCall(program, continuation, initialPointer, call.call());
        } catch (RuntimeException failure) {
            return fail(program, continuation, initialPointer, failure);
        }
    }

    private FclStepResult executeInstruction(FclProgram program,
                                             FclContinuation continuation,
                                             FclInstruction instruction,
                                             int pointer) {
        FclExpressionEvaluator evaluator = new FclExpressionEvaluator(program, functions,
                continuation);
        if (instruction instanceof FclInstruction.Assignment assignment) {
            Object value = evaluator.evaluate(assignment.value());
            if (assignment.indices().isEmpty()) {
                continuation.scope().put(assignment.variable(), value);
            } else {
                Object root = continuation.scope().get(assignment.variable());
                List<Object> indices = new ArrayList<>(assignment.indices().size());
                for (FclExpression index : assignment.indices()) {
                    indices.add(evaluator.evaluate(index));
                }
                FclValues.setIndexed(root, indices, value);
            }
            advance(program, continuation, pointer + 1);
            return result(FclStepResult.Status.ADVANCED, pointer, continuation,
                    assignment.line(), value);
        }
        if (instruction instanceof FclInstruction.Evaluation evaluation) {
            Object value = evaluator.evaluate(evaluation.expression());
            advance(program, continuation, pointer + 1);
            return result(FclStepResult.Status.ADVANCED, pointer, continuation,
                    evaluation.line(), value);
        }
        if (instruction instanceof FclInstruction.Conditional conditional) {
            boolean taken = FclValues.truthy(evaluator.evaluate(conditional.condition()));
            continuation.mutableBranchState().add(new FclContinuation.BranchFrame(
                    conditional.endTarget(), continuation.callDepth(), taken));
            advance(program, continuation, taken ? pointer + 1 : conditional.falseTarget());
            return result(FclStepResult.Status.ADVANCED, pointer, continuation,
                    conditional.line(), taken);
        }
        if (instruction instanceof FclInstruction.Loop loop) {
            boolean taken = FclValues.truthy(evaluator.evaluate(loop.condition()));
            if (taken) {
                if (!hasLoop(continuation, pointer, continuation.callDepth())) {
                    continuation.mutableLoopState().add(new FclContinuation.LoopFrame(
                            pointer, loop.endTarget(), continuation.callDepth()));
                }
                advance(program, continuation, loop.bodyTarget());
            } else {
                removeLoop(continuation, pointer, continuation.callDepth());
                advance(program, continuation, loop.endTarget());
            }
            return result(FclStepResult.Status.ADVANCED, pointer, continuation,
                    loop.line(), taken);
        }
        if (instruction instanceof FclInstruction.Break breakInstruction) {
            int loopIndex = innermostLoop(continuation);
            if (loopIndex < 0) throw new FclRuntimeException("break has no active loop");
            FclContinuation.LoopFrame loop = continuation.mutableLoopState().get(loopIndex);
            continuation.mutableLoopState().subList(loopIndex,
                    continuation.mutableLoopState().size()).clear();
            removeInnerBranches(continuation, loop);
            advance(program, continuation, loop.endPointer());
            return result(FclStepResult.Status.ADVANCED, pointer, continuation,
                    breakInstruction.line(), null);
        }
        if (instruction instanceof FclInstruction.Continue continueInstruction) {
            int loopIndex = innermostLoop(continuation);
            if (loopIndex < 0) throw new FclRuntimeException("continue has no active loop");
            FclContinuation.LoopFrame loop = continuation.mutableLoopState().get(loopIndex);
            if (loopIndex + 1 < continuation.mutableLoopState().size()) {
                continuation.mutableLoopState().subList(loopIndex + 1,
                        continuation.mutableLoopState().size()).clear();
            }
            removeInnerBranches(continuation, loop);
            advance(program, continuation, loop.headerPointer());
            return result(FclStepResult.Status.ADVANCED, pointer, continuation,
                    continueInstruction.line(), null);
        }
        if (instruction instanceof FclInstruction.Return returnInstruction) {
            Object value = returnInstruction.value() == null
                    ? null : evaluator.evaluate(returnInstruction.value());
            return completeReturn(program, continuation, pointer, returnInstruction.line(), value);
        }
        if (instruction instanceof FclInstruction.FunctionDeclaration declaration) {
            advance(program, continuation, declaration.endTarget());
            return result(FclStepResult.Status.ADVANCED, pointer, continuation,
                    declaration.line(), null);
        }
        if (instruction instanceof FclInstruction.Import importInstruction) {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (importInstruction.alias() != null) {
                payload.put("alias", importInstruction.alias());
            }
            payload.put("wildcard", importInstruction.wildcard());
            continuation.waitState(FclContinuation.WaitState.directive(
                    FclContinuation.WaitKind.IMPORT, importInstruction.target(), payload));
            advanceWithoutClearingWait(program, continuation, pointer + 1);
            return result(FclStepResult.Status.DIRECTIVE, pointer, continuation,
                    importInstruction.line(), importInstruction.target());
        }
        if (instruction instanceof FclInstruction.Include includeInstruction) {
            continuation.waitState(FclContinuation.WaitState.directive(
                    FclContinuation.WaitKind.INCLUDE, includeInstruction.target(), Map.of()));
            advanceWithoutClearingWait(program, continuation, pointer + 1);
            return result(FclStepResult.Status.DIRECTIVE, pointer, continuation,
                    includeInstruction.line(), includeInstruction.target());
        }
        throw new FclRuntimeException("Internal jump reached semantic executor");
    }

    private FclStepResult enterCall(FclProgram program, FclContinuation continuation,
                                    int pointerBefore,
                                    FclExpressionEvaluator.UserCall call) {
        FclProgram.Function function = program.function(call.name());
        if (function == null) {
            throw new FclRuntimeException("Undefined user function: " + call.name());
        }
        if (call.arguments().size() != function.parameters().size()) {
            throw new FclRuntimeException("Function " + call.name() + " expects "
                    + function.parameters().size() + " arguments, got " + call.arguments().size());
        }
        int returnPointer = continuation.programCounter();
        FclContinuation.PendingStatement callerPending = continuation.pendingStatement();
        continuation.mutableCallStack().add(new FclContinuation.CallFrame(returnPointer,
                continuation.scope(), callerPending, call.expressionId(), call.name()));
        FclScope functionScope = new FclScope();
        for (int index = 0; index < function.parameters().size(); index++) {
            functionScope.put(function.parameters().get(index), call.arguments().get(index));
        }
        continuation.scope(functionScope);
        continuation.pendingStatement(null);
        continuation.programCounter(function.entryPoint());
        normalizePointer(program, continuation);
        return result(FclStepResult.Status.CALL_ENTERED, pointerBefore, continuation,
                lineAt(program, returnPointer), call.name());
    }

    private FclStepResult completeReturn(FclProgram program, FclContinuation continuation,
                                         int pointer, int line, Object value) {
        if (continuation.mutableCallStack().isEmpty()) {
            continuation.programCounter(program.instructions().size());
            continuation.halt(value);
            return result(FclStepResult.Status.COMPLETED, pointer, continuation, line, value);
        }
        int returningDepth = continuation.callDepth();
        continuation.mutableLoopState().removeIf(frame -> frame.callDepth() >= returningDepth);
        continuation.mutableBranchState().removeIf(frame -> frame.callDepth() >= returningDepth);
        FclContinuation.CallFrame frame = continuation.mutableCallStack()
                .removeLast();
        continuation.scope(frame.callerScope());
        FclContinuation.PendingStatement pending = frame.callerPending();
        if (pending == null) pending = new FclContinuation.PendingStatement(frame.returnPointer());
        continuation.pendingStatement(pending.withResult(frame.callExpressionId(), value));
        continuation.programCounter(frame.returnPointer());
        normalizePointer(program, continuation);
        pruneState(continuation);
        return result(FclStepResult.Status.RETURNED, pointer, continuation, line, value);
    }

    private FclStepResult fail(FclProgram program, FclContinuation continuation,
                               int pointerBefore, RuntimeException failure) {
        int pointer = continuation.programCounter();
        int line = lineAt(program, pointer);
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        continuation.mutableExceptionStack().add(new FclContinuation.ExceptionFrame(
                pointer, line, failure.getClass().getSimpleName(), message,
                continuation.callDepth()));
        continuation.fail(message);
        return result(FclStepResult.Status.FAILED, pointerBefore, continuation, line, message);
    }

    private static void ensurePending(FclContinuation continuation, int pointer) {
        FclContinuation.PendingStatement pending = continuation.pendingStatement();
        if (pending == null || pending.instructionPointer() != pointer) {
            continuation.pendingStatement(new FclContinuation.PendingStatement(pointer));
        }
    }

    private static void advance(FclProgram program, FclContinuation continuation, int target) {
        continuation.pendingStatement(null);
        continuation.programCounter(target);
        normalizePointer(program, continuation);
        pruneState(continuation);
    }

    private static void advanceWithoutClearingWait(FclProgram program,
                                                   FclContinuation continuation,
                                                   int target) {
        continuation.pendingStatement(null);
        continuation.programCounter(target);
        normalizePointer(program, continuation);
        pruneState(continuation);
    }

    private static void normalizePointer(FclProgram program,
                                         FclContinuation continuation) {
        int traversed = 0;
        while (continuation.programCounter() >= 0
                && continuation.programCounter() < program.instructions().size()
                && program.instructions().get(continuation.programCounter())
                instanceof FclInstruction.Jump jump) {
            continuation.programCounter(jump.target());
            if (++traversed > program.instructions().size()) {
                throw new FclRuntimeException("Internal jump cycle detected");
            }
        }
        if (continuation.programCounter() > program.instructions().size()) {
            throw new FclRuntimeException("Instruction pointer exceeds program bounds");
        }
    }

    private static void pruneState(FclContinuation continuation) {
        int pointer = continuation.programCounter();
        int depth = continuation.callDepth();
        continuation.mutableBranchState().removeIf(frame -> frame.callDepth() == depth
                && pointer >= frame.endPointer());
        continuation.mutableLoopState().removeIf(frame -> frame.callDepth() == depth
                && pointer >= frame.endPointer());
    }

    private static boolean hasLoop(FclContinuation continuation, int header, int depth) {
        return continuation.mutableLoopState().stream().anyMatch(frame ->
                frame.headerPointer() == header && frame.callDepth() == depth);
    }

    private static void removeLoop(FclContinuation continuation, int header, int depth) {
        continuation.mutableLoopState().removeIf(frame ->
                frame.headerPointer() == header && frame.callDepth() == depth);
    }

    private static int innermostLoop(FclContinuation continuation) {
        int depth = continuation.callDepth();
        for (int index = continuation.mutableLoopState().size() - 1; index >= 0; index--) {
            if (continuation.mutableLoopState().get(index).callDepth() == depth) return index;
        }
        return -1;
    }

    private static void removeInnerBranches(FclContinuation continuation,
                                            FclContinuation.LoopFrame loop) {
        continuation.mutableBranchState().removeIf(frame ->
                frame.callDepth() == loop.callDepth()
                        && frame.endPointer() <= loop.endPointer());
    }

    private static int lineAt(FclProgram program, int pointer) {
        if (pointer < 0 || pointer >= program.instructions().size()) return -1;
        return program.instructions().get(pointer).line();
    }

    private static FclStepResult result(FclStepResult.Status status, int pointerBefore,
                                        FclContinuation continuation, int line,
                                        Object value) {
        return new FclStepResult(status, pointerBefore, continuation.programCounter(),
                line, value, continuation.waitState());
    }
}
