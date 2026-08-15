package com.follarce.fcl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluates one pure expression, suspending when a user function must run. */
final class FclExpressionEvaluator {
    record UserCall(long expressionId, String name, List<Object> arguments) {}

    static final class UserCallSignal extends RuntimeException {
        private final UserCall call;

        private UserCallSignal(UserCall call) {
            super(null, null, false, false);
            this.call = call;
        }

        UserCall call() {
            return call;
        }
    }

    private final FclProgram program;
    private final FclFunctionRegistry functions;
    private final FclContinuation continuation;

    FclExpressionEvaluator(FclProgram program, FclFunctionRegistry functions,
                           FclContinuation continuation) {
        this.program = program;
        this.functions = functions;
        this.continuation = continuation;
    }

    Object evaluate(FclExpression expression) {
        if (expression instanceof FclExpression.Literal literal) {
            return FclValues.deepCopy(literal.value());
        }
        if (expression instanceof FclExpression.Variable variable) {
            return FclValues.deepCopy(continuation.scope().get(variable.name()));
        }
        if (expression instanceof FclExpression.ArrayLiteral array) {
            List<Object> result = new ArrayList<>(array.elements().size());
            for (FclExpression element : array.elements()) result.add(evaluate(element));
            return result;
        }
        if (expression instanceof FclExpression.MapLiteral map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (FclExpression.MapEntry entry : map.entries()) {
                result.put(evaluate(entry.key()), evaluate(entry.value()));
            }
            return result;
        }
        if (expression instanceof FclExpression.Unary unary) {
            return FclValues.unary(unary.operator(), evaluate(unary.operand()));
        }
        if (expression instanceof FclExpression.Binary binary) {
            Object left = evaluate(binary.left());
            if (binary.operator().equals("and")) {
                return FclValues.truthy(left) && FclValues.truthy(evaluate(binary.right()));
            }
            if (binary.operator().equals("or")) {
                return FclValues.truthy(left) || FclValues.truthy(evaluate(binary.right()));
            }
            return FclValues.binary(binary.operator(), left, evaluate(binary.right()));
        }
        if (expression instanceof FclExpression.Index index) {
            return FclValues.index(evaluate(index.target()), evaluate(index.index()));
        }
        if (expression instanceof FclExpression.Call call) {
            FclContinuation.PendingStatement pending = continuation.pendingStatement();
            if (pending != null && pending.hasResult(call.id())) {
                return pending.result(call.id());
            }
            List<Object> arguments = new ArrayList<>(call.arguments().size());
            for (FclExpression argument : call.arguments()) arguments.add(evaluate(argument));
            FclProgram.Function userFunction = program.function(call.name());
            if (userFunction != null) {
                throw new UserCallSignal(new UserCall(call.id(), call.name(), arguments));
            }
            Object value = functions.invoke(call.name(), arguments,
                    new FclFunctionRegistry.Invocation(call.id(), continuation,
                            currentPackageIdentity()));
            FclContinuation.PendingStatement current = continuation.pendingStatement();
            if (current == null) {
                current = new FclContinuation.PendingStatement(continuation.programCounter());
            }
            continuation.pendingStatement(current.withResult(call.id(), value));
            return value;
        }
        throw new FclRuntimeException("Unsupported expression node: "
                + expression.getClass().getSimpleName());
    }

    /**
     * Resolves the package identity of the function currently being executed from
     * the innermost call frame. Top-level program code has no package identity.
     */
    private String currentPackageIdentity() {
        List<FclContinuation.CallFrame> frames = continuation.callStack();
        if (frames.isEmpty()) return null;
        FclProgram.Function function = program.function(frames.getLast().functionName());
        return function == null ? null : function.packageIdentity();
    }
}
