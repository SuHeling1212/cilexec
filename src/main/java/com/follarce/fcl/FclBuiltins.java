package com.follarce.fcl;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Factory for deterministic, side-effect-free built-ins. */
public final class FclBuiltins {
    private FclBuiltins() {}

    /** Returns a new registry on every call; no process-global catalog is retained. */
    public static FclFunctionRegistry pureRegistry() {
        FclFunctionRegistry registry = new FclFunctionRegistry();
        registerMath(registry);
        registerUtil(registry, new Gson());
        registerPath(registry);
        return registry;
    }

    private static void registerMath(FclFunctionRegistry registry) {
        registry.register("math", "sin", args -> Math.sin(number(args, 0, 1, "sin")))
                .register("math", "cos", args -> Math.cos(number(args, 0, 1, "cos")))
                .register("math", "tan", args -> Math.tan(number(args, 0, 1, "tan")))
                .register("math", "sqrt", args -> {
                    double value = number(args, 0, 1, "sqrt");
                    if (value < 0) throw new FclRuntimeException("sqrt requires a non-negative number");
                    return Math.sqrt(value);
                })
                .register("math", "log", args -> {
                    double value = number(args, 0, 1, "log");
                    if (value <= 0) throw new FclRuntimeException("log requires a positive number");
                    return Math.log(value);
                })
                .register("math", "abs", args -> {
                    Number value = numericArgument(args, 0, 1, "abs");
                    if (value instanceof Byte || value instanceof Short
                            || value instanceof Integer || value instanceof Long) {
                        return Math.abs(value.longValue());
                    }
                    return Math.abs(value.doubleValue());
                })
                .register("math", "round", args -> Math.round(number(args, 0, 1, "round")))
                .register("math", "floor", args -> Math.floor(number(args, 0, 1, "floor")))
                .register("math", "ceil", args -> Math.ceil(number(args, 0, 1, "ceil")))
                .register("math", "pow", args -> {
                    arity(args, 2, "pow");
                    return Math.pow(numberAt(args, 0, "pow"), numberAt(args, 1, "pow"));
                })
                .register("math", "max", args -> {
                    arity(args, 2, "max");
                    return Math.max(numberAt(args, 0, "max"), numberAt(args, 1, "max"));
                })
                .register("math", "min", args -> {
                    arity(args, 2, "min");
                    return Math.min(numberAt(args, 0, "min"), numberAt(args, 1, "min"));
                })
                .register("math", "pi", args -> {
                    arity(args, 0, "pi");
                    return Math.PI;
                })
                .register("math", "e", args -> {
                    arity(args, 0, "e");
                    return Math.E;
                });
    }

    private static void registerUtil(FclFunctionRegistry registry, Gson gson) {
        registry.register("util", "toJson", args -> {
                    arity(args, 1, "toJson");
                    return gson.toJson(args.getFirst());
                })
                .register("util", "fromJson", args -> {
                    arity(args, 1, "fromJson");
                    Object source = args.getFirst();
                    if (!(source instanceof String text)) {
                        throw new FclRuntimeException("fromJson requires a string");
                    }
                    try {
                        return gson.fromJson(text, Object.class);
                    } catch (JsonSyntaxException failure) {
                        throw new FclRuntimeException("Invalid JSON: " + failure.getMessage(), failure);
                    }
                })
                .register("util", "typeOf", args -> {
                    arity(args, 1, "typeOf");
                    return FclValues.typeOf(args.getFirst());
                })
                .register("util", "isArray", args -> {
                    arity(args, 1, "isArray");
                    Object value = args.getFirst();
                    return value instanceof List<?> || value != null && value.getClass().isArray();
                })
                .register("util", "isMap", args -> {
                    arity(args, 1, "isMap");
                    return args.getFirst() instanceof Map<?, ?>;
                })
                .register("util", "isNumber", args -> {
                    arity(args, 1, "isNumber");
                    return args.getFirst() instanceof Number;
                })
                .register("util", "isString", args -> {
                    arity(args, 1, "isString");
                    return args.getFirst() instanceof String;
                })
                .register("util", "isBool", args -> {
                    arity(args, 1, "isBool");
                    return args.getFirst() instanceof Boolean;
                })
                .register("util", "toString", args -> {
                    arity(args, 1, "toString");
                    return FclValues.display(args.getFirst());
                }, "string")
                .register("util", "length", args -> {
                    arity(args, 1, "length");
                    return FclValues.unary("#", args.getFirst());
                });
    }

    private static void registerPath(FclFunctionRegistry registry) {
        registry.register("path", "normalize", args -> {
                    arity(args, 1, "normalize");
                    return normalizePath(stringAt(args, 0, "normalize"));
                })
                .register("path", "resolve", args -> {
                    arity(args, 1, "resolve");
                    return normalizePath(stringAt(args, 0, "resolve"));
                })
                .register("path", "getFileName", args -> {
                    arity(args, 1, "getFileName");
                    String normalized = normalizePath(stringAt(args, 0, "getFileName"));
                    int slash = normalized.lastIndexOf('/');
                    return slash < 0 ? normalized : normalized.substring(slash + 1);
                })
                .register("path", "getParentPath", args -> {
                    arity(args, 1, "getParentPath");
                    String normalized = normalizePath(stringAt(args, 0, "getParentPath"));
                    int slash = normalized.lastIndexOf('/');
                    if (slash <= 0) return "/";
                    return normalized.substring(0, slash);
                }, "getParent")
                .register("path", "isAbsolute", args -> {
                    arity(args, 1, "isAbsolute");
                    return stringAt(args, 0, "isAbsolute").replace('\\', '/').startsWith("/");
                })
                .register("path", "join", args -> {
                    if (args.isEmpty()) return "/";
                    List<String> parts = new ArrayList<>();
                    for (int index = 0; index < args.size(); index++) {
                        parts.add(stringAt(args, index, "join"));
                    }
                    return normalizePath(String.join("/", parts));
                });
    }

    private static String normalizePath(String source) {
        String path = source.replace('\\', '/');
        boolean absolute = path.startsWith("/");
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.split("/+")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
                continue;
            }
            parts.addLast(part);
        }
        String joined = String.join("/", parts);
        if (absolute) return joined.isEmpty() ? "/" : "/" + joined;
        return joined.isEmpty() ? "." : joined;
    }

    private static Number numericArgument(List<Object> args, int index, int count,
                                          String function) {
        arity(args, count, function);
        Object value = args.get(index);
        if (!(value instanceof Number number)) {
            throw new FclRuntimeException(function + " requires numeric arguments");
        }
        return number;
    }

    private static double number(List<Object> args, int index, int count, String function) {
        return numericArgument(args, index, count, function).doubleValue();
    }

    private static double numberAt(List<Object> args, int index, String function) {
        Object value = args.get(index);
        if (!(value instanceof Number number)) {
            throw new FclRuntimeException(function + " requires numeric arguments");
        }
        return number.doubleValue();
    }

    private static String stringAt(List<Object> args, int index, String function) {
        Object value = args.get(index);
        if (!(value instanceof String text)) {
            throw new FclRuntimeException(function + " requires string arguments");
        }
        return text;
    }

    private static void arity(List<Object> args, int expected, String function) {
        if (args.size() != expected) {
            throw new FclRuntimeException(function + " expects " + expected
                    + " arguments, got " + args.size());
        }
    }
}
