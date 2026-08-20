package com.follarce.fcl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Statement-at-a-time FCL interpreter. */
public final class FclRuntime {
    /** Keeps a recursive FCL program from exhausting heap through persisted call frames. */
    static final int MAX_CALL_DEPTH = 256;
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
            // Exceptions raised while validating/entering a user call happen from inside this
            // catch block and therefore are not handled by the RuntimeException catch below.
            // Convert them to an ordinary durable FCL failure instead of letting the scheduler
            // roll the transaction back and retry the same broken statement forever.
            try {
                return enterCall(program, continuation, initialPointer, call.call());
            } catch (FclRuntimeException failure) {
                return handleFclFailure(program, continuation, initialPointer, failure);
            } catch (RuntimeException failure) {
                return fail(program, continuation, initialPointer, failure);
            }
        } catch (FclSuspension suspension) {
            if (continuation.waitState().kind() == FclContinuation.WaitKind.NONE) {
                return fail(program, continuation, initialPointer,
                        new FclRuntimeException("A host function suspended without a wait state"));
            }
            return result(FclStepResult.Status.WAITING, initialPointer, continuation,
                    lineAt(program, continuation.programCounter()), null);
        } catch (FclRuntimeException failure) {
            return handleFclFailure(program, continuation, initialPointer, failure);
        } catch (RuntimeException failure) {
            // A non-FCL RuntimeException is a kernel/extension fault, not language control
            // flow. It must never enter an FCL catch block.
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
                if (nativeReadOnlyMember(continuation, assignment.variable())) {
                    throw new FclRuntimeException("ImmutableValue",
                            "Exception and StackFrame members are read-only");
                } else if (FclObjectRuntime.isMemberPath(continuation, assignment.variable())) {
                    FclObjectRuntime.setMember(program, continuation, assignment.variable(), value);
                } else {
                    continuation.scope().put(assignment.variable(), value);
                }
            } else {
                boolean memberPath = FclObjectRuntime.isMemberPath(continuation, assignment.variable());
                Object root = memberPath ? FclObjectRuntime.member(program, continuation, assignment.variable())
                        : continuation.scope().get(assignment.variable());
                List<Object> indices = new ArrayList<>(assignment.indices().size());
                for (FclExpression index : assignment.indices()) {
                    indices.add(evaluator.evaluate(index));
                }
                FclValues.setIndexed(root, indices, value);
                if (memberPath) FclObjectRuntime.setMember(program, continuation, assignment.variable(), root);
            }
            advance(program, continuation, pointer + 1);
            return result(FclStepResult.Status.ADVANCED, pointer, continuation,
                    assignment.line(), value);
        }
        if (instruction instanceof FclInstruction.Link link) {
            continuation.scope().link(link.target(), link.source());
            advance(program, continuation, pointer + 1);
            return result(FclStepResult.Status.ADVANCED, pointer, continuation, link.line(), null);
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
            // A backwards compiler jump reaches this header before pruneState sees the branch
            // end pointer. Without this cleanup, an if inside an infinite while leaked one
            // persisted BranchFrame per iteration and made every later commit progressively
            // larger and slower.
            continuation.mutableBranchState().removeIf(frame ->
                    frame.callDepth() == continuation.callDepth()
                            && frame.endPointer() <= loop.endTarget());
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
        if (instruction instanceof FclInstruction.Update update) {
            Object value = evaluator.update(update.variable(), update.indices(), update.delta(),
                    Long.MIN_VALUE + pointer);
            advance(program, continuation, pointer + 1);
            return result(FclStepResult.Status.ADVANCED, pointer, continuation, update.line(), value);
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
            advance(program, continuation, pointer + 1);
            continuation.waitState(FclContinuation.WaitState.directive(
                    FclContinuation.WaitKind.IMPORT, importInstruction.target(), payload));
            return result(FclStepResult.Status.DIRECTIVE, pointer, continuation,
                    importInstruction.line(), importInstruction.target());
        }
        if (instruction instanceof FclInstruction.Include includeInstruction) {
            advance(program, continuation, pointer + 1);
            continuation.waitState(FclContinuation.WaitState.directive(
                    FclContinuation.WaitKind.INCLUDE, includeInstruction.target(), Map.of()));
            return result(FclStepResult.Status.DIRECTIVE, pointer, continuation,
                    includeInstruction.line(), includeInstruction.target());
        }
        if (instruction instanceof FclInstruction.TryStart tryStart) {
            boolean hadPrevious = continuation.scope().contains(tryStart.catchVariable());
            Object previous = hadPrevious ? continuation.scope().get(tryStart.catchVariable()) : null;
            continuation.mutableExceptionHandlers().add(new FclContinuation.ExceptionHandlerFrame(
                    tryStart.catchTarget(), tryStart.catchEndTarget(), tryStart.catchVariable(),
                    continuation.callDepth(), false, hadPrevious, previous));
            advance(program, continuation, pointer + 1);
            return result(FclStepResult.Status.ADVANCED, pointer, continuation,
                    tryStart.line(), null);
        }
        if (instruction instanceof FclInstruction.CatchEnter catchEnter) {
            FclContinuation.ExceptionHandlerFrame handler = activeHandler(continuation, pointer);
            if (handler == null || !handler.handling()) {
                throw new FclRuntimeException("Internal exception handler entry without a failure");
            }
            advance(program, continuation, pointer + 1);
            return result(FclStepResult.Status.ADVANCED, pointer, continuation,
                    catchEnter.line(), null);
        }
        if (instruction instanceof FclInstruction.CatchEnd catchEnd) {
            FclContinuation.ExceptionHandlerFrame handler = activeHandlingHandler(continuation);
            if (handler == null) throw new FclRuntimeException("Internal exception handler exit without a catch");
            removeHandler(continuation, continuation.mutableExceptionHandlers().size() - 1, true);
            advance(program, continuation, pointer + 1);
            return result(FclStepResult.Status.ADVANCED, pointer, continuation,
                    catchEnd.line(), null);
        }
        throw new FclRuntimeException("Internal jump reached semantic executor");
    }

    private FclStepResult enterCall(FclProgram program, FclContinuation continuation,
                                    int pointerBefore,
                                    FclExpressionEvaluator.UserCall call) {
        FclProgram.Function function = program.function(call.name());
        if (function == null) {
            throw new FclRuntimeException("UndefinedFunction", "Undefined user function: " + call.name());
        }
        if (call.arguments().size() != function.parameters().size()) {
            throw new FclRuntimeException("InvalidArgument", "Function " + call.name() + " expects "
                    + function.parameters().size() + " arguments, got " + call.arguments().size());
        }
        if (continuation.callDepth() >= MAX_CALL_DEPTH) {
            throw new FclRuntimeException("Maximum function call depth of "
                    + MAX_CALL_DEPTH + " exceeded");
        }
        int returnPointer = continuation.programCounter();
        FclContinuation.PendingStatement callerPending = continuation.pendingStatement();
        continuation.mutableCallStack().add(new FclContinuation.CallFrame(returnPointer,
                continuation.scope(), callerPending, call.expressionId(), call.name(),
                call.receiverPath(), call.construction()));
        FclScope functionScope = function.moduleBindings() == null
                ? new FclScope() : new FclScope(function.moduleBindings());
        if (call.receiver() != null) functionScope.put("this", call.receiver());
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
            removeHandlersAtOrAbove(continuation, 0, true);
            continuation.programCounter(program.instructions().size());
            continuation.halt(value);
            return result(FclStepResult.Status.COMPLETED, pointer, continuation, line, value);
        }
        int returningDepth = continuation.callDepth();
        removeHandlersAtOrAbove(continuation, returningDepth, true);
        continuation.mutableLoopState().removeIf(frame -> frame.callDepth() >= returningDepth);
        continuation.mutableBranchState().removeIf(frame -> frame.callDepth() >= returningDepth);
        FclContinuation.CallFrame frame = continuation.mutableCallStack()
                .removeLast();
        FclObjectValue receiver = null;
        if (frame.receiverPath() != null || frame.construction()) {
            receiver = FclObjectRuntime.currentThis(continuation);
        }
        continuation.scope(frame.callerScope());
        FclContinuation.PendingStatement pending = frame.callerPending();
        if (pending == null) pending = new FclContinuation.PendingStatement(frame.returnPointer());
        Object returned = value;
        if (frame.construction()) {
            returned = receiver;
        } else if (frame.receiverPath() != null) {
            FclObjectRuntime.replaceObject(program, continuation, frame.receiverPath(), receiver);
        }
        continuation.pendingStatement(pending.withResult(frame.callExpressionId(), returned));
        continuation.programCounter(frame.returnPointer());
        normalizePointer(program, continuation);
        pruneState(continuation);
        return result(FclStepResult.Status.RETURNED, pointer, continuation, line, value);
    }

    private FclStepResult handleFclFailure(FclProgram program, FclContinuation continuation,
                                           int pointerBefore, FclRuntimeException failure) {
        FclExceptionValue exception = exceptionValue(program, continuation, failure);
        int handlerIndex = innermostCatchableHandler(continuation);
        if (handlerIndex < 0) return fail(program, continuation, pointerBefore, failure, exception);
        FclContinuation.ExceptionHandlerFrame handler = continuation.mutableExceptionHandlers()
                .get(handlerIndex);
        // Handlers nested inside the selected region have left scope. A handler already in its
        // catch body cannot catch a second failure; discard it so the outer one can run.
        while (continuation.mutableExceptionHandlers().size() > handlerIndex + 1) {
            removeHandler(continuation, continuation.mutableExceptionHandlers().size() - 1,
                    continuation.mutableExceptionHandlers().getLast().callDepth()
                            == continuation.callDepth());
        }
        unwindToHandler(continuation, handler.callDepth());
        continuation.mutableExceptionHandlers().set(handlerIndex, handler.asHandling());
        continuation.scope().put(handler.variable(), exception);
        continuation.pendingStatement(null);
        continuation.clearWait();
        continuation.programCounter(handler.catchTarget());
        pruneState(continuation);
        return result(FclStepResult.Status.ADVANCED, pointerBefore, continuation,
                lineAt(program, handler.catchTarget()), exception);
    }

    private FclStepResult fail(FclProgram program, FclContinuation continuation,
                               int pointerBefore, RuntimeException failure) {
        return fail(program, continuation, pointerBefore, failure,
                failure instanceof FclRuntimeException fcl ? exceptionValue(program, continuation, fcl) : null);
    }

    private FclStepResult fail(FclProgram program, FclContinuation continuation,
                               int pointerBefore, RuntimeException failure,
                               FclExceptionValue exception) {
        int pointer = continuation.programCounter();
        int line = lineAt(program, pointer);
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        continuation.mutableExceptionStack().add(new FclContinuation.ExceptionFrame(
                pointer, line, exception == null ? failure.getClass().getSimpleName() : exception.type(), message,
                continuation.callDepth()));
        continuation.fail(exception == null ? message : exception);
        return result(FclStepResult.Status.FAILED, pointerBefore, continuation, line,
                exception == null ? message : exception);
    }

    private static void ensurePending(FclContinuation continuation, int pointer) {
        FclContinuation.PendingStatement pending = continuation.pendingStatement();
        if (pending == null || pending.instructionPointer() != pointer) {
            continuation.pendingStatement(new FclContinuation.PendingStatement(pointer));
        }
    }

    private static void advance(FclProgram program, FclContinuation continuation, int target) {
        if (continuation.waitState().kind() != FclContinuation.WaitKind.NONE
                && !continuation.halted()) {
            throw new IllegalStateException(
                    "A host function set a wait state without suspending execution");
        }
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
        for (int index = continuation.mutableExceptionHandlers().size() - 1; index >= 0; index--) {
            FclContinuation.ExceptionHandlerFrame handler = continuation.mutableExceptionHandlers().get(index);
            if (handler.callDepth() == depth && pointer >= handler.catchEndTarget()) {
                removeHandler(continuation, index, handler.handling());
            }
        }
    }

    private static FclContinuation.ExceptionHandlerFrame activeHandler(FclContinuation continuation,
                                                                         int pointer) {
        for (int index = continuation.mutableExceptionHandlers().size() - 1; index >= 0; index--) {
            FclContinuation.ExceptionHandlerFrame frame = continuation.mutableExceptionHandlers().get(index);
            if (frame.callDepth() == continuation.callDepth() && frame.catchTarget() == pointer) return frame;
        }
        return null;
    }

    private static FclContinuation.ExceptionHandlerFrame activeHandlingHandler(FclContinuation continuation) {
        for (int index = continuation.mutableExceptionHandlers().size() - 1; index >= 0; index--) {
            FclContinuation.ExceptionHandlerFrame frame = continuation.mutableExceptionHandlers().get(index);
            if (frame.callDepth() == continuation.callDepth() && frame.handling()) return frame;
        }
        return null;
    }

    private static int innermostCatchableHandler(FclContinuation continuation) {
        for (int index = continuation.mutableExceptionHandlers().size() - 1; index >= 0; index--) {
            FclContinuation.ExceptionHandlerFrame frame = continuation.mutableExceptionHandlers().get(index);
            if (!frame.handling() && frame.callDepth() <= continuation.callDepth()) return index;
        }
        return -1;
    }

    private static void unwindToHandler(FclContinuation continuation, int depth) {
        while (continuation.callDepth() > depth) {
            FclContinuation.CallFrame discarded = continuation.mutableCallStack().removeLast();
            continuation.scope(discarded.callerScope());
        }
        continuation.mutableLoopState().removeIf(frame -> frame.callDepth() > depth);
        continuation.mutableBranchState().removeIf(frame -> frame.callDepth() > depth);
    }

    private static void removeHandlersAtOrAbove(FclContinuation continuation, int depth,
                                                 boolean restoreCurrentScope) {
        for (int index = continuation.mutableExceptionHandlers().size() - 1; index >= 0; index--) {
            if (continuation.mutableExceptionHandlers().get(index).callDepth() >= depth) {
                removeHandler(continuation, index, restoreCurrentScope
                        && continuation.mutableExceptionHandlers().get(index).callDepth()
                        == continuation.callDepth());
            }
        }
    }

    private static void removeHandler(FclContinuation continuation, int index,
                                      boolean restoreCurrentScope) {
        FclContinuation.ExceptionHandlerFrame handler = continuation.mutableExceptionHandlers().remove(index);
        if (restoreCurrentScope && handler.handling()) {
            if (handler.hadPreviousBinding()) continuation.scope().put(handler.variable(), handler.previousValue());
            else continuation.scope().mutableValues().remove(handler.variable());
        }
    }

    private static FclExceptionValue exceptionValue(FclProgram program, FclContinuation continuation,
                                                    FclRuntimeException failure) {
        List<FclStackFrame> stack = new ArrayList<>();
        int pointer = continuation.programCounter();
        String function = continuation.callStack().isEmpty() ? "<main>"
                : continuation.callStack().getLast().functionName();
        stack.add(new FclStackFrame(function, "<main>", Math.max(1, lineAt(program, pointer)), 1));
        for (int index = continuation.callStack().size() - 2; index >= 0; index--) {
            FclContinuation.CallFrame frame = continuation.callStack().get(index);
            stack.add(new FclStackFrame(frame.functionName(), "<main>",
                    Math.max(1, lineAt(program, frame.returnPointer())), 1));
        }
        return new FclExceptionValue(failure.type(), failure.getMessage() == null
                ? failure.type() : failure.getMessage(), stack);
    }

    private static boolean hasLoop(FclContinuation continuation, int header, int depth) {
        return continuation.mutableLoopState().stream().anyMatch(frame ->
                frame.headerPointer() == header && frame.callDepth() == depth);
    }

    private static boolean nativeReadOnlyMember(FclContinuation continuation, String path) {
        int separator = path.indexOf('.');
        if (separator < 1) return false;
        String root = path.substring(0, separator);
        Object value;
        if (continuation.scope().contains(root)) value = continuation.scope().get(root);
        else if (continuation.globalScope() != continuation.scope()
                && continuation.globalScope().contains(root)) value = continuation.globalScope().get(root);
        else return false;
        return value instanceof FclExceptionValue || value instanceof FclStackFrame;
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
