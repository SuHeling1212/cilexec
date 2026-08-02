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
    private static final int MAX_TEXT_RESULT_CHARS = 16 * 1024 * 1024;

    private FclBuiltins() {}

    /** Returns a new registry on every call; no process-global catalog is retained. */
    public static FclFunctionRegistry pureRegistry() {
        FclFunctionRegistry registry = new FclFunctionRegistry();
        registerMath(registry);
        registerUtil(registry, new Gson());
        registerArray(registry);
        registerText(registry);
        registerPath(registry);
        registerTerminal(registry);
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
                        if (value.longValue() == Long.MIN_VALUE) {
                            throw new FclRuntimeException("abs result exceeds the integer range");
                        }
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
                    requireTextSize(text.length(), "fromJson input");
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

    private static void registerArray(FclFunctionRegistry registry) {
        registry.register("array", "insert", args -> {
                    arity(args, 3, "array.insert");
                    if (!(args.getFirst() instanceof List<?> values)) {
                        throw new FclRuntimeException(
                                "array.insert requires an array as its first argument");
                    }
                    long requested = integral(args.get(1), "array.insert index");
                    if (requested < 0 || requested > values.size()) {
                        throw new FclRuntimeException(
                                "array.insert index must be between 0 and " + values.size());
                    }
                    List<Object> result = new ArrayList<>(values.size() + 1);
                    values.forEach(item -> result.add(FclValues.deepCopy(item)));
                    result.add((int) requested, FclValues.deepCopy(args.get(2)));
                    return result;
                })
                .register("array", "removeAt", args -> {
                    arity(args, 2, "array.removeAt");
                    if (!(args.getFirst() instanceof List<?> values)) {
                        throw new FclRuntimeException(
                                "array.removeAt requires an array as its first argument");
                    }
                    long requested = integral(args.get(1), "array.removeAt index");
                    if (requested < 0 || requested >= values.size()) {
                        throw new FclRuntimeException(
                                "array.removeAt index must be between 0 and "
                                        + (values.size() - 1));
                    }
                    List<Object> result = new ArrayList<>(values.size() - 1);
                    for (int index = 0; index < values.size(); index++) {
                        if (index != requested) {
                            result.add(FclValues.deepCopy(values.get(index)));
                        }
                    }
                    return result;
                });
    }

    private static void registerText(FclFunctionRegistry registry) {
        registry.register("text", "slice", args -> {
                    if (args.size() < 2 || args.size() > 3) {
                        throw new FclRuntimeException("slice expects text, start, and optional end");
                    }
                    String value = stringAt(args, 0, "slice");
                    int start = textIndex(args.get(1), value.length(), "slice start");
                    int end = args.size() == 3
                            ? textIndex(args.get(2), value.length(), "slice end")
                            : value.length();
                    if (end < start) throw new FclRuntimeException(
                            "slice end cannot precede start");
                    return value.substring(start, end);
                })
                .register("text", "split", args -> {
                    arity(args, 2, "split");
                    String value = stringAt(args, 0, "split");
                    String delimiter = stringAt(args, 1, "split");
                    if (delimiter.isEmpty()) {
                        return value.codePoints().mapToObj(codePoint ->
                                new String(Character.toChars(codePoint))).toList();
                    }
                    return List.of(value.split(java.util.regex.Pattern.quote(delimiter), -1));
                })
                .register("text", "join", args -> {
                    arity(args, 2, "join");
                    if (!(args.getFirst() instanceof List<?> values)) {
                        throw new FclRuntimeException("join requires an array as its first argument");
                    }
                    String delimiter = stringAt(args, 1, "join");
                    StringBuilder result = new StringBuilder();
                    for (int index = 0; index < values.size(); index++) {
                        String item = FclValues.display(values.get(index));
                        long nextLength = (long) result.length() + item.length()
                                + (index == 0 ? 0 : delimiter.length());
                        requireTextSize(nextLength, "join result");
                        if (index != 0) result.append(delimiter);
                        result.append(item);
                    }
                    return result.toString();
                })
                .register("text", "indexOf", args -> {
                    if (args.size() < 2 || args.size() > 3) {
                        throw new FclRuntimeException(
                                "indexOf expects text, search text, and optional start");
                    }
                    String value = stringAt(args, 0, "indexOf");
                    String search = stringAt(args, 1, "indexOf");
                    int start = args.size() == 3
                            ? textIndex(args.get(2), value.length(), "indexOf start") : 0;
                    return (long) value.indexOf(search, start);
                })
                .register("text", "lastIndexOf", args -> {
                    if (args.size() < 2 || args.size() > 3) {
                        throw new FclRuntimeException(
                                "lastIndexOf expects text, search text, and optional start");
                    }
                    String value = stringAt(args, 0, "lastIndexOf");
                    String search = stringAt(args, 1, "lastIndexOf");
                    int start = args.size() == 3
                            ? textIndex(args.get(2), value.length(), "lastIndexOf start")
                            : value.length();
                    return (long) value.lastIndexOf(search, start);
                })
                .register("text", "repeat", args -> {
                    arity(args, 2, "repeat");
                    String value = stringAt(args, 0, "repeat");
                    long count = integral(args.get(1), "repeat count");
                    if (count < 0 || count > 1_000_000) {
                        throw new FclRuntimeException(
                                "repeat count must be between 0 and 1000000");
                    }
                    requireTextSize((long) value.length() * count, "repeat result");
                    return value.repeat((int) count);
                })
                .register("text", "replace", args -> {
                    arity(args, 3, "replace");
                    String value = stringAt(args, 0, "replace");
                    String target = stringAt(args, 1, "replace");
                    String replacement = stringAt(args, 2, "replace");
                    requireTextSize(replacedLength(value, target, replacement),
                            "replace result");
                    return value.replace(target, replacement);
                });
    }

    private static void registerTerminal(FclFunctionRegistry registry) {
        registry.register("term", "color", args -> {
                    arity(args, 2, "color");
                    return ansiColor(stringAt(args, 0, "color"))
                            + String.valueOf(args.get(1)) + "\u001b[0m";
                }, "paint")
                .register("term", "bold", args -> ansiWrap(args, "bold", "1"))
                .register("term", "dim", args -> ansiWrap(args, "dim", "2"))
                .register("term", "reset", args -> {
                    arity(args, 0, "reset");
                    return "\u001b[0m";
                })
                .register("term", "clear", args -> {
                    arity(args, 0, "clear");
                    return "\u001b[2J\u001b[H";
                })
                .register("term", "eraseLine", args -> {
                    arity(args, 0, "eraseLine");
                    return "\u001b[2K\r";
                })
                .register("term", "inverse", args -> ansiWrap(args, "inverse", "7"))
                .register("term", "hideCursor", args -> {
                    arity(args, 0, "hideCursor");
                    return "\u001b[?25l";
                })
                .register("term", "showCursor", args -> {
                    arity(args, 0, "showCursor");
                    return "\u001b[?25h";
                })
                .register("term", "displayWidth", args -> {
                    arity(args, 1, "displayWidth");
                    return (long) com.follarce.terminal.TerminalColumns.width(
                            stringAt(args, 0, "displayWidth"));
                })
                .register("term", "truncate", args -> {
                    arity(args, 2, "truncate");
                    String value = stringAt(args, 0, "truncate");
                    long width = integral(args.get(1), "truncate width");
                    if (width < 0 || width > 1_000_000) throw new FclRuntimeException(
                            "truncate width must be between 0 and 1000000");
                    return com.follarce.terminal.TerminalColumns.truncate(value, (int) width);
                })
                .register("term", "cursorTo", args -> {
                    arity(args, 2, "cursorTo");
                    long row = integral(args.get(0), "cursorTo row");
                    long column = integral(args.get(1), "cursorTo column");
                    if (row < 1 || column < 1) throw new FclRuntimeException(
                            "cursorTo requires positive row and column values");
                    return "\u001b[" + row + ";" + column + "H";
                })
                .register("term", "cursorUp", args -> cursor(args, "cursorUp", "A"))
                .register("term", "cursorDown", args -> cursor(args, "cursorDown", "B"))
                .register("term", "cursorForward", args -> cursor(args, "cursorForward", "C"))
                .register("term", "cursorBack", args -> cursor(args, "cursorBack", "D"));
        String[] colors = {"red", "green", "yellow", "blue", "magenta", "cyan", "white"};
        for (String color : colors) {
            registry.register("term", color, args -> {
                arity(args, 1, color);
                return ansiColor(color) + String.valueOf(args.getFirst()) + "\u001b[0m";
            });
        }
    }

    private static String ansiWrap(List<Object> args, String function, String code) {
        arity(args, 1, function);
        return "\u001b[" + code + "m" + String.valueOf(args.getFirst()) + "\u001b[0m";
    }

    private static String cursor(List<Object> args, String function, String suffix) {
        arity(args, 1, function);
        long count = integral(args.getFirst(), function + " count");
        if (count < 1 || count > 1_000_000) {
            throw new FclRuntimeException(function + " requires a positive count");
        }
        return "\u001b[" + count + suffix;
    }

    private static String ansiColor(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "black" -> "\u001b[30m";
            case "red" -> "\u001b[31m";
            case "green" -> "\u001b[32m";
            case "yellow" -> "\u001b[33m";
            case "blue" -> "\u001b[34m";
            case "magenta" -> "\u001b[35m";
            case "cyan" -> "\u001b[36m";
            case "white" -> "\u001b[37m";
            default -> throw new FclRuntimeException("Unknown terminal color: " + name);
        };
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

    private static int textIndex(Object value, int maximum, String description) {
        long index = integral(value, description);
        if (index < 0 || index > maximum) {
            throw new FclRuntimeException(description + " must be between 0 and " + maximum);
        }
        return (int) index;
    }

    private static long integral(Object value, String description) {
        if (!(value instanceof Number number)
                || number.doubleValue() != number.longValue()) {
            throw new FclRuntimeException(description + " requires an integer");
        }
        return number.longValue();
    }

    private static long replacedLength(String value, String target, String replacement) {
        if (target.isEmpty()) {
            return (long) value.length() + (long) (value.length() + 1) * replacement.length();
        }
        long occurrences = 0;
        int from = 0;
        while (true) {
            int match = value.indexOf(target, from);
            if (match < 0) break;
            occurrences++;
            from = match + target.length();
        }
        return (long) value.length()
                + occurrences * ((long) replacement.length() - target.length());
    }

    private static void requireTextSize(long length, String description) {
        if (length > MAX_TEXT_RESULT_CHARS) {
            throw new FclRuntimeException(description + " exceeds the 16 Mi-character limit");
        }
    }

    private static void arity(List<Object> args, int expected, String function) {
        if (args.size() != expected) {
            throw new FclRuntimeException(function + " expects " + expected
                    + " arguments, got " + args.size());
        }
    }
}
