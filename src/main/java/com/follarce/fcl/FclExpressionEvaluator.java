package com.follarce.fcl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluates one expression, signaling when execution must enter a base-program or linked-module function. */
final class FclExpressionEvaluator {
    record UserCall(long expressionId, String name, List<Object> arguments,
                    FclObjectValue receiver, String receiverPath, boolean construction) {}

    /** A resolved method receiver; a non-null {@code path} is the name to write mutations back to. */
    private record Receiver(FclObjectValue object, FclSystemHandle handle, String path) {}

    @SuppressWarnings("serial") // Internal stackless control signal; never crosses a JVM boundary.
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
            return readMember(evaluate(member.target()), member.name());
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
            List<Object> arguments = evaluateArguments(call.arguments());
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
            int separator = call.name().lastIndexOf('.');
            if (separator > 0) {
                Receiver receiver = namedReceiver(call.name().substring(0, separator));
                if (receiver != null) {
                    String methodName = call.name().substring(separator + 1);
                    if (receiver.object() != null) {
                        return dispatchUserMethod(call.id(), receiver.object(), receiver.path(),
                                methodName, arguments);
                    }
                    return dispatchHandleMethod(call.id(), receiver.handle(), methodName, arguments);
                }
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
            recordResult(call.id(), value);
            return value;
        }
        if (expression instanceof FclExpression.MethodCall call) {
            FclContinuation.PendingStatement pending = continuation.pendingStatement();
            if (pending != null && pending.hasResult(call.id())) return pending.result(call.id());
            Receiver receiver = resolveMethodReceiver(call.target());
            List<Object> arguments = evaluateArguments(call.arguments());
            if (receiver.object() != null) {
                return dispatchUserMethod(call.id(), receiver.object(), receiver.path(), call.name(),
                        arguments);
            }
            return dispatchHandleMethod(call.id(), receiver.handle(), call.name(), arguments);
        }
        throw new FclRuntimeException("Unsupported expression node: "
                + expression.getClass().getSimpleName());
    }

    private List<Object> evaluateArguments(List<FclExpression> expressions) {
        List<Object> arguments = new ArrayList<>(expressions.size());
        for (FclExpression expression : expressions) arguments.add(evaluate(expression));
        return arguments;
    }

    /**
     * Resolves a dotted name whose root is bound in the current or the durable global scope.
     * The root value decides dispatch: a user-object member path (including {@code this.field})
     * or a system handle. Anything else returns {@code null} so qualified registry functions,
     * hash-qualified package functions, and user functions still resolve normally.
     *
     * <p>Namespace roots such as {@code io}, {@code file}, or {@code process} are not reserved
     * names: if a program binds such a root name, receiver dispatch deliberately takes
     * precedence over the registry.
     */
    private Receiver namedReceiver(String path) {
        int separator = path.indexOf('.');
        String rootName = separator < 1 ? path : path.substring(0, separator);
        if (!FclObjectRuntime.hasRoot(continuation, rootName)) return null;
        Object rootValue = FclObjectRuntime.root(continuation, rootName);
        if (rootValue instanceof FclObjectValue) {
            Object value = path.equals(rootName) ? rootValue
                    : FclObjectRuntime.receiver(program, continuation, path);
            if (value instanceof FclObjectValue object) return new Receiver(object, null, path);
            if (value instanceof FclSystemHandle handle) return new Receiver(null, handle, path);
            throw new FclRuntimeException("Cannot call member of non-object");
        }
        if (rootValue instanceof FclSystemHandle handle && path.equals(rootName)) {
            return new Receiver(null, handle, path);
        }
        return null;
    }

    /**
     * Resolves a {@link FclExpression.MethodCall} target. A purely named target follows exactly
     * the same named-receiver rules as a plain dotted call, so {@code (a).increment()} writes a
     * mutated receiver back precisely like {@code a.increment()}. Any other target is a
     * temporary expression: it is evaluated with ordinary value-copy semantics and its method
     * result is usable, but there is no named location to write the mutated receiver back to.
     */
    private Receiver resolveMethodReceiver(FclExpression target) {
        String path = namedPath(target);
        if (path != null) {
            Receiver receiver = namedReceiver(path);
            if (receiver != null) return receiver;
        }
        Object value = evaluate(target);
        if (value instanceof FclObjectValue object) return new Receiver(object, null, null);
        if (value instanceof FclSystemHandle handle) return new Receiver(null, handle, null);
        throw new FclRuntimeException("InvalidReceiver", "Method receiver is not an object");
    }

    /** Returns the dotted name of a pure Variable/Member path, or null for any other expression. */
    private static String namedPath(FclExpression expression) {
        if (expression instanceof FclExpression.Variable variable) return variable.name();
        if (expression instanceof FclExpression.Member member) {
            String target = namedPath(member.target());
            return target == null ? null : target + "." + member.name();
        }
        return null;
    }

    private Object dispatchUserMethod(long expressionId, FclObjectValue receiver,
                                      String receiverPath, String name,
                                      List<Object> arguments) {
        FclProgram.Method method = FclObjectRuntime.method(program, receiver.className(), name,
                arguments.size());
        if (method == null) throw new FclRuntimeException("UndefinedMethod", "Undefined method "
                + receiver.className() + "." + name + "/" + arguments.size());
        FclObjectRuntime.requireMethodAccess(program, continuation, receiver.className(), name,
                arguments.size(), method);
        throw new UserCallSignal(new UserCall(expressionId, method.functionKey(), arguments,
                receiver, receiverPath, false));
    }

    private Object dispatchHandleMethod(long expressionId, FclSystemHandle handle, String name,
                                        List<Object> arguments) {
        Object value = functions.invokeMethod(handle, name, arguments,
                new FclFunctionRegistry.Invocation(expressionId, continuation,
                        currentPackageIdentity(), program));
        recordResult(expressionId, value);
        return value;
    }

    private void recordResult(long expressionId, Object value) {
        FclContinuation.PendingStatement current = continuation.pendingStatement();
        if (current == null) {
            current = new FclContinuation.PendingStatement(continuation.programCounter());
        }
        continuation.pendingStatement(current.withResult(expressionId, value));
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

    /** Reads a member of a temporary expression value, including user-object fields. */
    private Object readMember(Object target, String name) {
        if (target instanceof FclExceptionValue || target instanceof FclStackFrame) {
            return nativeMember(target, name);
        }
        if (target instanceof FclObjectValue object) {
            FclObjectRuntime.requireFieldAccess(program, continuation, object, name);
            return object.field(name);
        }
        throw new FclRuntimeException("Cannot access member '" + name + "' of non-object");
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
