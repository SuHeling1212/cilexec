package com.follarce.fcl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Lossless JSON/Map codec for every persisted continuation field. */
public final class FclContinuationCodec {
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Object>>() { }
            .getType();

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public Map<String, Object> encode(FclContinuation continuation) {
        Objects.requireNonNull(continuation, "continuation");
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("formatVersion", continuation.formatVersion());
        encoded.put("programCounter", continuation.programCounter());
        encoded.put("scope", encodeValue(continuation.scope().values()));

        List<Object> calls = new ArrayList<>();
        for (FclContinuation.CallFrame frame : continuation.callStack()) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("returnPointer", frame.returnPointer());
            call.put("callerScope", encodeValue(frame.callerScope().values()));
            call.put("callerPending", encodePending(frame.callerPending()));
            call.put("callExpressionId", Long.toString(frame.callExpressionId()));
            call.put("functionName", frame.functionName());
            calls.add(call);
        }
        encoded.put("callStack", calls);

        List<Object> exceptions = new ArrayList<>();
        for (FclContinuation.ExceptionFrame frame : continuation.exceptionStack()) {
            Map<String, Object> exception = new LinkedHashMap<>();
            exception.put("instructionPointer", frame.instructionPointer());
            exception.put("sourceLine", frame.sourceLine());
            exception.put("type", frame.type());
            exception.put("message", frame.message());
            exception.put("callDepth", frame.callDepth());
            exceptions.add(exception);
        }
        encoded.put("exceptionStack", exceptions);

        List<Object> loops = new ArrayList<>();
        for (FclContinuation.LoopFrame frame : continuation.loopState()) {
            Map<String, Object> loop = new LinkedHashMap<>();
            loop.put("headerPointer", frame.headerPointer());
            loop.put("endPointer", frame.endPointer());
            loop.put("callDepth", frame.callDepth());
            loops.add(loop);
        }
        encoded.put("loopState", loops);

        List<Object> branches = new ArrayList<>();
        for (FclContinuation.BranchFrame frame : continuation.branchState()) {
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("endPointer", frame.endPointer());
            branch.put("callDepth", frame.callDepth());
            branch.put("taken", frame.taken());
            branches.add(branch);
        }
        encoded.put("branchState", branches);

        Map<String, Object> wait = new LinkedHashMap<>();
        wait.put("kind", continuation.waitState().kind().name());
        wait.put("key", continuation.waitState().key());
        wait.put("payload", encodeValue(continuation.waitState().payload()));
        encoded.put("waitState", wait);
        encoded.put("pendingStatement", encodePending(continuation.pendingStatement()));
        encoded.put("halted", continuation.halted());
        encoded.put("failed", continuation.failed());
        encoded.put("result", encodeValue(continuation.result()));
        return encoded;
    }

    public String toJson(FclContinuation continuation) {
        return gson.toJson(encode(continuation));
    }

    /** Canonical lossless encoding used by normalized variable projections. */
    public String valueToJson(Object value) {
        return gson.toJson(encodeValue(value));
    }

    public String valueType(Object value) {
        return string(encodeValue(value).get("type"), "value type");
    }

    public Object valueFromJson(String json) {
        Objects.requireNonNull(json, "json");
        Map<String, Object> encoded = gson.fromJson(json, MAP_TYPE);
        if (encoded == null) {
            throw new IllegalArgumentException("Encoded FCL value cannot be null");
        }
        return decodeValue(encoded);
    }

    /** Decodes an ordinary JSON document into FCL-compatible scalar/array/map values. */
    public Object documentFromJson(String json) {
        Objects.requireNonNull(json, "json");
        return gson.fromJson(json, Object.class);
    }

    public FclContinuation decode(Map<String, ?> encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int formatVersion = integer(encoded.get("formatVersion"), "formatVersion");
        int programCounter = integer(encoded.get("programCounter"), "programCounter");
        FclScope scope = scope(encoded.get("scope"), "scope");

        List<FclContinuation.CallFrame> calls = new ArrayList<>();
        for (Object item : list(encoded.get("callStack"), "callStack")) {
            Map<String, ?> call = map(item, "callStack item");
            calls.add(new FclContinuation.CallFrame(
                    integer(call.get("returnPointer"), "returnPointer"),
                    scope(call.get("callerScope"), "callerScope"),
                    decodePending(call.get("callerPending")),
                    longString(call.get("callExpressionId"), "callExpressionId"),
                    string(call.get("functionName"), "functionName")));
        }

        List<FclContinuation.ExceptionFrame> exceptions = new ArrayList<>();
        for (Object item : list(encoded.get("exceptionStack"), "exceptionStack")) {
            Map<String, ?> exception = map(item, "exceptionStack item");
            exceptions.add(new FclContinuation.ExceptionFrame(
                    integer(exception.get("instructionPointer"), "instructionPointer"),
                    integer(exception.get("sourceLine"), "sourceLine"),
                    string(exception.get("type"), "type"),
                    string(exception.get("message"), "message"),
                    integer(exception.get("callDepth"), "callDepth")));
        }

        List<FclContinuation.LoopFrame> loops = new ArrayList<>();
        for (Object item : list(encoded.get("loopState"), "loopState")) {
            Map<String, ?> loop = map(item, "loopState item");
            loops.add(new FclContinuation.LoopFrame(
                    integer(loop.get("headerPointer"), "headerPointer"),
                    integer(loop.get("endPointer"), "endPointer"),
                    integer(loop.get("callDepth"), "callDepth")));
        }

        List<FclContinuation.BranchFrame> branches = new ArrayList<>();
        for (Object item : list(encoded.get("branchState"), "branchState")) {
            Map<String, ?> branch = map(item, "branchState item");
            branches.add(new FclContinuation.BranchFrame(
                    integer(branch.get("endPointer"), "endPointer"),
                    integer(branch.get("callDepth"), "callDepth"),
                    bool(branch.get("taken"), "taken")));
        }

        Map<String, ?> waitMap = map(encoded.get("waitState"), "waitState");
        FclContinuation.WaitKind waitKind;
        try {
            waitKind = FclContinuation.WaitKind.valueOf(
                    string(waitMap.get("kind"), "wait kind"));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown wait kind", failure);
        }
        String waitKey = nullableString(waitMap.get("key"), "wait key");
        Map<String, Object> waitPayload = stringMap(
                decodeValue(waitMap.get("payload")), "wait payload");
        FclContinuation.WaitState waitState = new FclContinuation.WaitState(
                waitKind, waitKey, waitPayload);

        return FclContinuation.restore(formatVersion, programCounter, scope, calls,
                exceptions, loops, branches, waitState,
                decodePending(encoded.get("pendingStatement")),
                bool(encoded.get("halted"), "halted"),
                bool(encoded.get("failed"), "failed"),
                decodeValue(encoded.get("result")));
    }

    public FclContinuation fromJson(String json) {
        Objects.requireNonNull(json, "json");
        Map<String, Object> encoded = gson.fromJson(json, MAP_TYPE);
        if (encoded == null) {
            throw new IllegalArgumentException("Continuation JSON cannot be null");
        }
        return decode(encoded);
    }

    private Map<String, Object> encodePending(FclContinuation.PendingStatement pending) {
        if (pending == null) return null;
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("instructionPointer", pending.instructionPointer());
        List<Object> results = new ArrayList<>();
        pending.callResults().forEach((expressionId, value) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("expressionId", Long.toString(expressionId));
            result.put("value", encodeValue(value));
            results.add(result);
        });
        encoded.put("callResults", results);
        return encoded;
    }

    private FclContinuation.PendingStatement decodePending(Object encoded) {
        if (encoded == null) return null;
        Map<String, ?> pending = map(encoded, "pendingStatement");
        Map<Long, Object> results = new LinkedHashMap<>();
        for (Object item : list(pending.get("callResults"), "callResults")) {
            Map<String, ?> result = map(item, "callResults item");
            long expressionId = longString(result.get("expressionId"), "expressionId");
            results.put(expressionId, decodeValue(result.get("value")));
        }
        return new FclContinuation.PendingStatement(
                integer(pending.get("instructionPointer"), "instructionPointer"), results);
    }

    private Map<String, Object> encodeValue(Object value) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        if (value == null) {
            encoded.put("type", "null");
            return encoded;
        }
        if (value instanceof Boolean bool) {
            encoded.put("type", "bool");
            encoded.put("value", bool);
            return encoded;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            encoded.put("type", "long");
            encoded.put("value", Long.toString(((Number) value).longValue()));
            return encoded;
        }
        if (value instanceof Float || value instanceof Double) {
            encoded.put("type", "double");
            encoded.put("value", Double.toHexString(((Number) value).doubleValue()));
            return encoded;
        }
        if (value instanceof String || value instanceof Character) {
            encoded.put("type", "string");
            encoded.put("value", value.toString());
            return encoded;
        }
        if (value instanceof List<?> list) {
            encoded.put("type", "array");
            List<Object> elements = new ArrayList<>();
            list.forEach(item -> elements.add(encodeValue(item)));
            encoded.put("value", elements);
            return encoded;
        }
        if (value.getClass().isArray()) {
            encoded.put("type", "array");
            List<Object> elements = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                elements.add(encodeValue(Array.get(value, index)));
            }
            encoded.put("value", elements);
            return encoded;
        }
        if (value instanceof Map<?, ?> map) {
            encoded.put("type", "map");
            List<Object> entries = new ArrayList<>();
            map.forEach((key, item) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", encodeValue(key));
                entry.put("value", encodeValue(item));
                entries.add(entry);
            });
            encoded.put("value", entries);
            return encoded;
        }
        throw new IllegalArgumentException("Unsupported FCL value type: "
                + value.getClass().getName());
    }

    private Object decodeValue(Object encoded) {
        Map<String, ?> value = map(encoded, "typed value");
        String type = string(value.get("type"), "value type");
        return switch (type) {
            case "null" -> null;
            case "bool" -> bool(value.get("value"), "bool value");
            case "long" -> longString(value.get("value"), "long value");
            case "double" -> doubleString(value.get("value"), "double value");
            case "string" -> string(value.get("value"), "string value");
            case "array" -> {
                List<Object> elements = new ArrayList<>();
                for (Object item : list(value.get("value"), "array value")) {
                    elements.add(decodeValue(item));
                }
                yield elements;
            }
            case "map" -> {
                Map<Object, Object> result = new LinkedHashMap<>();
                for (Object item : list(value.get("value"), "map value")) {
                    Map<String, ?> entry = map(item, "map entry");
                    result.put(decodeValue(entry.get("key")),
                            decodeValue(entry.get("value")));
                }
                yield result;
            }
            default -> throw new IllegalArgumentException("Unknown FCL value type: " + type);
        };
    }

    private FclScope scope(Object encoded, String field) {
        return new FclScope(stringMap(decodeValue(encoded), field));
    }

    private static Map<String, Object> stringMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(field + " must be a map");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException(field + " keys must be strings");
            }
            result.put(name, item);
        });
        return result;
    }

    private static Map<String, ?> map(Object value, String field) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException(field + " keys must be strings");
                }
                result.put(name, item);
            });
            return result;
        }
        throw new IllegalArgumentException(field + " must be an object");
    }

    private static List<?> list(Object value, String field) {
        if (value instanceof List<?> list) return list;
        throw new IllegalArgumentException(field + " must be an array");
    }

    private static int integer(Object value, String field) {
        if (value instanceof Number number && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        throw new IllegalArgumentException(field + " must be an integer");
    }

    private static long longString(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be an encoded integer");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " is outside integer range", failure);
        }
    }

    private static double doubleString(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be an encoded decimal");
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " is not a decimal", failure);
        }
    }

    private static boolean bool(Object value, String field) {
        if (value instanceof Boolean bool) return bool;
        throw new IllegalArgumentException(field + " must be a boolean");
    }

    private static String string(Object value, String field) {
        if (value instanceof String text) return text;
        throw new IllegalArgumentException(field + " must be a string");
    }

    private static String nullableString(Object value, String field) {
        if (value == null) return null;
        return string(value, field);
    }
}
