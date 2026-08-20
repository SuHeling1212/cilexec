package com.follarce.fcl;

import java.util.LinkedHashMap;
import java.util.Map;

/** Class hierarchy lookup and value-object member access. */
final class FclObjectRuntime {
    private FclObjectRuntime() { }

    static FclProgram.ClassDefinition requireClass(FclProgram program, String name) {
        FclProgram.ClassDefinition definition = program.classDefinition(name);
        if (definition == null) throw new FclRuntimeException("UndefinedClass", "Undefined class: " + name);
        return definition;
    }

    static Map<String, FclProgram.Field> fields(FclProgram program, String name) {
        FclProgram.ClassDefinition definition = requireClass(program, name);
        Map<String, FclProgram.Field> fields = new LinkedHashMap<>();
        if (definition.parent() != null) fields.putAll(fields(program, definition.parent()));
        definition.fields().forEach((fieldName, field) -> {
            if (fields.putIfAbsent(fieldName, field) != null) throw new FclRuntimeException(
                    "Inherited field conflict: " + name + "." + fieldName);
        });
        return fields;
    }

    static FclProgram.Method method(FclProgram program, String className, String name, int arity) {
        FclProgram.ClassDefinition definition = requireClass(program, className);
        FclProgram.Method local = definition.methods().get(name + "/" + arity);
        if (local != null) return local;
        return definition.parent() == null ? null : method(program, definition.parent(), name, arity);
    }

    static boolean isMemberPath(FclContinuation continuation, String expression) {
        int separator = expression.indexOf('.');
        if (separator < 1) return false;
        String rootName = expression.substring(0, separator);
        return hasRoot(continuation, rootName) && root(continuation, rootName) instanceof FclObjectValue;
    }

    static Object member(FclProgram program, FclContinuation continuation, String expression) {
        String[] segments = expression.split("\\.");
        if (segments.length < 2) throw new IllegalArgumentException("Member path requires a dot");
        Object current = root(continuation, segments[0]);
        for (int index = 1; index < segments.length; index++) {
            if (!(current instanceof FclObjectValue object)) throw new FclRuntimeException(
                    "Cannot access member '" + segments[index] + "' of non-object");
            requireFieldAccess(program, continuation, object, segments[index]);
            current = object.mutableField(segments[index]);
        }
        return FclValues.deepCopy(current);
    }

    static void setMember(FclProgram program, FclContinuation continuation, String expression, Object value) {
        String[] segments = expression.split("\\.");
        if (segments.length < 2) throw new IllegalArgumentException("Member path requires a dot");
        Object current = root(continuation, segments[0]);
        for (int index = 1; index < segments.length - 1; index++) {
            if (!(current instanceof FclObjectValue object)) throw new FclRuntimeException(
                    "Cannot assign member of non-object");
            requireFieldAccess(program, continuation, object, segments[index]);
            object.prepareForMutation();
            current = object.mutableField(segments[index]);
        }
        if (!(current instanceof FclObjectValue object)) throw new FclRuntimeException(
                "Cannot assign member of non-object");
        String field = segments[segments.length - 1];
        requireFieldAccess(program, continuation, object, field);
        object.field(field, value);
    }

    static FclObjectValue methodReceiver(FclContinuation continuation, String expression) {
        String[] segments = expression.split("\\.");
        if (segments.length < 2) throw new IllegalArgumentException("Method path requires a dot");
        Object current = root(continuation, segments[0]);
        for (int index = 1; index < segments.length - 1; index++) {
            if (!(current instanceof FclObjectValue object)) throw new FclRuntimeException(
                    "Cannot call member of non-object");
            current = object.mutableField(segments[index]);
        }
        if (!(current instanceof FclObjectValue object)) throw new FclRuntimeException(
                "Cannot call member of non-object");
        return object;
    }

    /** Replaces the receiver value after an instance method completed. */
    static void replaceObject(FclProgram program, FclContinuation continuation, String expression,
                              FclObjectValue value) {
        if (expression.indexOf('.') < 0) continuation.scope().put(expression, value);
        else setMember(program, continuation, expression, value);
    }

    static void requireMethodAccess(FclProgram program, FclContinuation continuation,
                                    String className, String name, int arity,
                                    FclProgram.Method method) {
        if (method.access() == FclProgram.Access.PUBLIC) return;
        String owner = methodOwner(program, className, name, arity);
        if (!owner.equals(lexicalClass(program, continuation))) throw new FclRuntimeException(
                "PrivateAccess", "Private member access: " + owner + "." + name);
    }

    static String currentClass(FclProgram program, FclContinuation continuation) {
        return lexicalClass(program, continuation);
    }

    static FclObjectValue currentThis(FclContinuation continuation) {
        if (!continuation.scope().contains("this")) throw new FclRuntimeException(
                "InvalidThis", "this is only valid inside an instance method");
        Object value = continuation.scope().get("this");
        if (!(value instanceof FclObjectValue object)) throw new FclRuntimeException("InvalidThis", "Invalid this");
        return object;
    }

    private static boolean hasRoot(FclContinuation continuation, String name) {
        return continuation.scope().contains(name) || (continuation.globalScope() != continuation.scope()
                && continuation.globalScope().contains(name));
    }

    private static Object root(FclContinuation continuation, String name) {
        if (continuation.scope().contains(name)) return continuation.scope().get(name);
        if (continuation.globalScope() != continuation.scope() && continuation.globalScope().contains(name)) {
            return continuation.globalScope().get(name);
        }
        throw new FclRuntimeException("UndefinedVariable", "Undefined variable: " + name);
    }

    private static void requireFieldAccess(FclProgram program, FclContinuation continuation,
                                           FclObjectValue object, String fieldName) {
        FieldOwner field = fieldOwner(program, object.className(), fieldName);
        if (field.field().access() == FclProgram.Access.PRIVATE
                && !field.owner().equals(lexicalClass(program, continuation))) {
            throw new FclRuntimeException("PrivateAccess", "Private member access: "
                    + field.owner() + "." + fieldName);
        }
    }

    private static FieldOwner fieldOwner(FclProgram program, String className, String name) {
        FclProgram.ClassDefinition definition = requireClass(program, className);
        FclProgram.Field field = definition.fields().get(name);
        if (field != null) return new FieldOwner(definition.name(), field);
        if (definition.parent() != null) return fieldOwner(program, definition.parent(), name);
        throw new FclRuntimeException("UndefinedField", "Undefined field " + className + "." + name);
    }

    private static String methodOwner(FclProgram program, String className, String name, int arity) {
        FclProgram.ClassDefinition definition = requireClass(program, className);
        if (definition.methods().containsKey(name + "/" + arity)) return definition.name();
        if (definition.parent() != null) return methodOwner(program, definition.parent(), name, arity);
        throw new FclRuntimeException("UndefinedMethod", "Undefined method " + className + "." + name + "/" + arity);
    }

    private static String lexicalClass(FclProgram program, FclContinuation continuation) {
        if (continuation.callStack().isEmpty()) return null;
        String function = continuation.callStack().getLast().functionName();
        int dot = function.indexOf('.');
        if (dot < 1) return null;
        String candidate = function.substring(0, dot);
        return program.classDefinition(candidate) == null ? null : candidate;
    }

    private record FieldOwner(String owner, FclProgram.Field field) { }
}
