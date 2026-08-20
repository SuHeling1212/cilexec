package com.follarce.fcl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluates one expression, signaling when execution must enter a base-program or linked-module function. */
final class FclExpressionEvaluator {
    record UserCall(long expressionId, String name, List<Object> arguments,
                    FclObjectValue receiver, String receiverPath, boolean construction) {}

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
            Object nativeMember = nativeMemberPath(variable.name());
            if (nativeMember != null) return nativeMember;
            if (FclObjectRuntime.isMemberPath(continuation, variable.name())) {
                return FclValues.deepCopy(FclObjectRuntime.member(program, continuation, variable.name()));
            }
            if (continuation.scope().contains(variable.name())) {
                return FclValues.deepCopy(continuation.scope().get(variable.name()));
            }
            FclProgram.Function function = currentFunction();
            if (function != null && function.moduleBindings() != null) {
                // Imported modules have their own globals and must not fall through to the
                // importing program's root scope.
                return FclValues.deepCopy(continuation.scope().get(variable.name()));
            }
            return FclValues.deepCopy(continuation.variable(variable.name()));
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
        if (expression instanceof FclExpression.Member member) {
            return nativeMember(evaluate(member.target()), member.name());
        }
        if (expression instanceof FclExpression.Update update) {
            return update(update.variable(), update.indices(), update.delta(), update.id());
        }
        if (expression instanceof FclExpression.DestroyTarget destroy) {
            return destroyTarget(destroy);
        }
        if (expression instanceof FclExpression.NewObject allocation) {
            FclContinuation.PendingStatement pending = continuation.pendingStatement();
            if (pending != null && pending.hasResult(allocation.id())) {
                return pending.result(allocation.id());
            }
            return newObject(allocation);
        }
        if (expression instanceof FclExpression.SuperConstructor call) {
            FclContinuation.PendingStatement pending = continuation.pendingStatement();
            if (pending != null && pending.hasResult(call.id())) return pending.result(call.id());
            return superConstructor(call);
        }
        if (expression instanceof FclExpression.Call call) {
            FclContinuation.PendingStatement pending = continuation.pendingStatement();
            if (pending != null && pending.hasResult(call.id())) {
                return pending.result(call.id());
            }
            List<Object> arguments = new ArrayList<>(call.arguments().size());
            for (FclExpression argument : call.arguments()) arguments.add(evaluate(argument));
            if (call.name().startsWith("super.")) {
                String lexicalClass = FclObjectRuntime.currentClass(program, continuation);
                if (lexicalClass == null) throw new FclRuntimeException("Invalid super access");
                FclProgram.ClassDefinition definition = FclObjectRuntime.requireClass(program, lexicalClass);
                if (definition.parent() == null) throw new FclRuntimeException("Invalid super access");
                String methodName = call.name().substring("super.".length());
                FclProgram.Method method = FclObjectRuntime.method(program, definition.parent(), methodName,
                        arguments.size());
                if (method == null) throw new FclRuntimeException("UndefinedMethod", "Undefined method "
                        + definition.parent() + "." + methodName + "/" + arguments.size());
                FclObjectValue receiver = FclObjectRuntime.currentThis(continuation);
                FclObjectRuntime.requireMethodAccess(program, continuation, definition.parent(), methodName,
                        arguments.size(), method);
                throw new UserCallSignal(new UserCall(call.id(), method.functionKey(), arguments,
                        receiver, "this", false));
            }
            if (FclObjectRuntime.isMemberPath(continuation, call.name())) {
                FclObjectValue receiver = FclObjectRuntime.methodReceiver(continuation, call.name());
                String methodName = call.name().substring(call.name().lastIndexOf('.') + 1);
                FclProgram.Method method = FclObjectRuntime.method(program,
                        receiver.className(), methodName,
                        arguments.size());
                if (method == null) throw new FclRuntimeException("UndefinedMethod", "Undefined method "
                        + receiver.className() + "."
                        + methodName + "/" + arguments.size());
                FclObjectRuntime.requireMethodAccess(program, continuation,
                        receiver.className(), methodName,
                        arguments.size(), method);
                throw new UserCallSignal(new UserCall(call.id(), method.functionKey(), arguments,
                        receiver, call.name().substring(0, call.name().lastIndexOf('.')), false));
            }
            FclProgram.Function userFunction = program.function(call.name());
            if (userFunction != null && !userFunction.publicBinding()
                    && continuation.callStack().isEmpty()) {
                userFunction = null;
            }
            if (userFunction != null) {
                throw new UserCallSignal(new UserCall(call.id(), call.name(), arguments, null, null, false));
            }
            Object value = functions.invoke(call.name(), arguments,
                    new FclFunctionRegistry.Invocation(call.id(), continuation,
                            currentPackageIdentity(), program));
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
     * Resolves the imported-module origin of the function in the innermost call frame.
     * Base-program code and functions have no module origin.
     */
    private String currentPackageIdentity() {
        FclProgram.Function function = currentFunction();
        return function == null ? null : function.packageIdentity();
    }

    /**
     * A {@code memory.destroy(target)} call passes the symbol root and the evaluated index
     * path to the registered destroy implementation instead of the deep-copied target value,
     * so the deletion can operate on the real continuation scope and containers.
     */
    private Object destroyTarget(FclExpression.DestroyTarget destroy) {
        List<Object> indices = new ArrayList<>(destroy.indices().size());
        for (FclExpression index : destroy.indices()) indices.add(evaluate(index));
        return functions.invoke(destroy.functionName(), List.of(destroy.rootName(), indices),
                new FclFunctionRegistry.Invocation(destroy.id(), continuation,
                        currentPackageIdentity(), program));
    }

    Object update(String variable, List<FclExpression> expressions, int delta, long id) {
        FclContinuation.PendingStatement pending = continuation.pendingStatement();
        if (pending != null && pending.hasResult(id)) return pending.result(id);
        List<Object> indices = new ArrayList<>(expressions.size());
        for (FclExpression expression : expressions) indices.add(evaluate(expression));
        Object oldValue;
        if (indices.isEmpty()) {
            oldValue = FclObjectRuntime.isMemberPath(continuation, variable)
                    ? FclObjectRuntime.member(program, continuation, variable)
                    : continuation.scope().get(variable);
        } else {
            Object root = FclObjectRuntime.isMemberPath(continuation, variable)
                    ? FclObjectRuntime.member(program, continuation, variable)
                    : continuation.scope().get(variable);
            oldValue = FclValues.index(root, indices.getFirst());
            for (int index = 1; index < indices.size(); index++) {
                oldValue = FclValues.index(oldValue, indices.get(index));
            }
        }
        Object updated = FclValues.increment(oldValue, delta);
        if (indices.isEmpty()) {
            if (nativeReadOnlyPath(variable)) throw new FclRuntimeException("ImmutableValue",
                    "Exception and StackFrame members are read-only");
            if (FclObjectRuntime.isMemberPath(continuation, variable)) {
                FclObjectRuntime.setMember(program, continuation, variable, updated);
            } else {
                continuation.scope().put(variable, updated);
            }
        } else {
            boolean memberPath = FclObjectRuntime.isMemberPath(continuation, variable);
            Object root = memberPath ? FclObjectRuntime.member(program, continuation, variable)
                    : continuation.scope().get(variable);
            FclValues.setIndexed(root, indices, updated);
            if (memberPath) FclObjectRuntime.setMember(program, continuation, variable, root);
        }
        FclContinuation.PendingStatement next = continuation.pendingStatement();
        if (next == null) next = new FclContinuation.PendingStatement(continuation.programCounter());
        continuation.pendingStatement(next.withResult(id, updated));
        return updated;
    }

    private Object newObject(FclExpression.NewObject allocation) {
        FclProgram.ClassDefinition definition = FclObjectRuntime.requireClass(program, allocation.className());
        List<Object> arguments = new ArrayList<>();
        for (FclExpression argument : allocation.arguments()) arguments.add(evaluate(argument));
        FclProgram.Method constructor = FclObjectRuntime.method(program, definition.name(), "init",
                allocation.arguments().size());
        if (constructor == null && !allocation.arguments().isEmpty()) throw new FclRuntimeException("InvalidConstructor",
                "Invalid constructor " + definition.name() + ".init/" + allocation.arguments().size());
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FclProgram.Field field : FclObjectRuntime.fields(program, definition.name()).values()) {
            fields.put(field.name(), evaluate(field.defaultValue()));
        }
        FclObjectValue object = new FclObjectValue(definition.name(), fields);
        if (constructor == null) {
            return object;
        }
        throw new UserCallSignal(new UserCall(allocation.id(), constructor.functionKey(), arguments,
                object, null, true));
    }

    private Object superConstructor(FclExpression.SuperConstructor call) {
        String lexicalClass = FclObjectRuntime.currentClass(program, continuation);
        if (lexicalClass == null) throw new FclRuntimeException("Invalid super access");
        FclProgram.ClassDefinition definition = FclObjectRuntime.requireClass(program, lexicalClass);
        if (definition.parent() == null) throw new FclRuntimeException("Invalid super access");
        List<Object> arguments = new ArrayList<>();
        for (FclExpression argument : call.arguments()) arguments.add(evaluate(argument));
        FclProgram.Method constructor = FclObjectRuntime.method(program, definition.parent(), "init",
                arguments.size());
        if (constructor == null) throw new FclRuntimeException("InvalidConstructor", "Invalid constructor "
                + definition.parent() + ".init/" + arguments.size());
        FclObjectValue receiver = FclObjectRuntime.currentThis(continuation);
        throw new UserCallSignal(new UserCall(call.id(), constructor.functionKey(), arguments,
                receiver, "this", false));
    }

    private FclProgram.Function currentFunction() {
        List<FclContinuation.CallFrame> frames = continuation.callStack();
        return frames.isEmpty() ? null : program.function(frames.getLast().functionName());
    }

    private Object nativeMemberPath(String path) {
        int separator = path.indexOf('.');
        if (separator < 1) return null;
        String root = path.substring(0, separator);
        Object value;
        if (continuation.scope().contains(root)) value = continuation.scope().get(root);
        else if (continuation.globalScope() != continuation.scope()
                && continuation.globalScope().contains(root)) value = continuation.globalScope().get(root);
        else return null;
        if (!(value instanceof FclExceptionValue) && !(value instanceof FclStackFrame)) return null;
        String[] members = path.substring(separator + 1).split("\\.");
        for (String member : members) value = nativeMember(value, member);
        return value;
    }

    private boolean nativeReadOnlyPath(String path) {
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

    private static Object nativeMember(Object value, String name) {
        if (value instanceof FclExceptionValue exception) {
            return switch (name) {
                case "type" -> exception.type();
                case "message" -> exception.message();
                case "stack" -> exception.stack();
                default -> throw new FclRuntimeException("Undefined exception member: " + name);
            };
        }
        if (value instanceof FclStackFrame frame) {
            return switch (name) {
                case "function" -> frame.function();
                case "source" -> frame.source();
                case "line" -> frame.line();
                case "column" -> frame.column();
                default -> throw new FclRuntimeException("Undefined stack frame member: " + name);
            };
        }
        throw new FclRuntimeException("Cannot access member '" + name + "' of non-object");
    }
}
