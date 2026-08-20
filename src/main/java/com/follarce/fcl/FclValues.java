package com.follarce.fcl;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** FCL value semantics kept independent of storage and host services. */
public final class FclValues {
    /** Sentinel for an absent final map key: the destroy reported "nothing removed". */
    public static final Object NO_ENTRY = new Object();

    private FclValues() {}

    static Object deepCopy(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value instanceof FclObjectValue object) return object.copy();
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(deepCopy(item)));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(normalizeKey(deepCopy(key)), deepCopy(item)));
            return copy;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                copy.add(deepCopy(Array.get(value, index)));
            }
            return copy;
        }
        return value;
    }

    static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            return numeric != 0.0d && !Double.isNaN(numeric);
        }
        if (value instanceof String text) return !text.isEmpty();
        if (value instanceof List<?> list) return !list.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    static Object unary(String operator, Object value) {
        return switch (operator) {
            case "!" -> !truthy(value);
            case "-" -> negate(requireNumber(value, "Unary -"));
            case "#" -> length(value);
            default -> throw new FclRuntimeException("Unsupported unary operator: " + operator);
        };
    }

    static Object binary(String operator, Object left, Object right) {
        return switch (operator) {
            case "+" -> add(left, right);
            case "-" -> numeric(left, right, operator);
            case "*" -> numeric(left, right, operator);
            case "/" -> numeric(left, right, operator);
            case "%" -> numeric(left, right, operator);
            case "==" -> equal(left, right);
            case "!=" -> !equal(left, right);
            case "<" -> compare(left, right) < 0;
            case "<=" -> compare(left, right) <= 0;
            case ">" -> compare(left, right) > 0;
            case ">=" -> compare(left, right) >= 0;
            default -> throw new FclRuntimeException("Unsupported binary operator: " + operator);
        };
    }

    static Object index(Object target, Object index) {
        if (target instanceof List<?> list) {
            int position = listIndex(index, list.size());
            return deepCopy(list.get(position));
        }
        if (target instanceof Map<?, ?> map) {
            Object key = normalizeKey(index);
            if (!map.containsKey(key)) {
                throw new FclRuntimeException("Map key does not exist: " + display(index));
            }
            return deepCopy(map.get(key));
        }
        if (target instanceof String text) {
            int position = listIndex(index, text.length());
            return String.valueOf(text.charAt(position));
        }
        throw new FclRuntimeException("Value is not indexable: " + typeOf(target));
    }

    @SuppressWarnings("unchecked")
    static void setIndexed(Object root, List<Object> indices, Object value) {
        if (indices.isEmpty()) {
            throw new IllegalArgumentException("Indexed assignment requires an index");
        }
        Object target = root;
        for (int offset = 0; offset < indices.size() - 1; offset++) {
            target = indexReference(target, indices.get(offset));
        }
        Object finalIndex = indices.getLast();
        if (target instanceof List<?> rawList) {
            List<Object> list = (List<Object>) rawList;
            list.set(listIndex(finalIndex, list.size()), deepCopy(value));
            return;
        }
        if (target instanceof Map<?, ?> rawMap) {
            ((Map<Object, Object>) rawMap).put(normalizeKey(deepCopy(finalIndex)), deepCopy(value));
            return;
        }
        throw new FclRuntimeException("Value is not assignable by index: " + typeOf(target));
    }

    /**
     * Removes the real element at the end of the index path from the authoritative
     * container tree. List elements shift left (no holes); Map entries are removed by
     * the normalized key. Returns the removed value, or {@link #NO_ENTRY} when the final
     * Map key is absent. Out-of-range list indexes, non-indexable intermediates, and
     * String element targets raise a runtime error.
     */
    @SuppressWarnings("unchecked")
    public static Object removeIndexed(Object root, List<Object> indices) {
        if (indices.isEmpty()) {
            throw new IllegalArgumentException("Indexed removal requires an index");
        }
        Object target = root;
        for (int offset = 0; offset < indices.size() - 1; offset++) {
            Object index = indices.get(offset);
            if (target instanceof List<?> list) {
                target = list.get(listIndex(index, list.size()));
            } else if (target instanceof Map<?, ?> map) {
                Object key = normalizeKey(deepCopy(index));
                if (!map.containsKey(key)) {
                    return NO_ENTRY;
                }
                target = map.get(key);
            } else {
                throw new FclRuntimeException("Value is not indexable: " + typeOf(target));
            }
        }
        Object finalIndex = indices.getLast();
        if (target instanceof List<?> rawList) {
            List<Object> list = (List<Object>) rawList;
            return list.remove(listIndex(finalIndex, list.size()));
        }
        if (target instanceof Map<?, ?> rawMap) {
            Map<Object, Object> map = (Map<Object, Object>) rawMap;
            Object key = normalizeKey(deepCopy(finalIndex));
            if (!map.containsKey(key)) {
                return NO_ENTRY;
            }
            return map.remove(key);
        }
        if (target instanceof String) {
            throw new FclRuntimeException("memory.destroy cannot remove a string element");
        }
        throw new FclRuntimeException("Value is not indexable: " + typeOf(target));
    }

    static Number requireNumber(Object value, String operation) {
        if (value instanceof Number number) return number;
        throw new FclRuntimeException(operation + " requires a number, got " + typeOf(value));
    }

    static Object increment(Object value, int delta) {
        if (delta != 1 && delta != -1) throw new IllegalArgumentException("Increment delta must be ±1");
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            try {
                return Math.addExact(((Number) value).longValue(), delta);
            } catch (ArithmeticException failure) {
                throw new FclRuntimeException("NumericOverflow", "Increment exceeds integer range", failure);
            }
        }
        if (value instanceof Float || value instanceof Double) {
            double result = ((Number) value).doubleValue() + delta;
            if (!Double.isFinite(result)) throw new FclRuntimeException("NumericOverflow",
                    "Increment produces a non-finite number");
            return result;
        }
        throw new FclRuntimeException("InvalidArgument", "++ and -- require a number, got " + typeOf(value));
    }

    static String typeOf(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return "bool";
        if (value instanceof Number) return "number";
        if (value instanceof String || value instanceof Character) return "string";
        if (value instanceof List<?> || value.getClass().isArray()) return "array";
        if (value instanceof Map<?, ?>) return "map";
        return value.getClass().getSimpleName();
    }

    /**
     * FCL display text. Top-level strings keep their exact text (string
     * concatenation and {@code text.join} must not quote or alter them); values
     * nested inside arrays and maps use the FCL literal/JSON syntax so container
     * contents stay unambiguous and host {@code toString()} representations never
     * leak into the language layer.
     */
    public static String display(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return text;
        if (value instanceof Character character) return String.valueOf(character);
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof List<?> list) {
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) result.append(',');
                result.append(displayNested(list.get(index)));
            }
            return result.append(']').toString();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < length; index++) {
                if (index > 0) result.append(',');
                result.append(displayNested(Array.get(value, index)));
            }
            return result.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) result.append(',');
                first = false;
                result.append(displayMapKey(entry.getKey())).append(':')
                        .append(displayNested(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof FclObjectValue object) return object.toString();
        return String.valueOf(value);
    }

    /** Display of a nested value: strings are quoted with JSON escapes. */
    private static String displayNested(Object value) {
        if (value instanceof String text) return jsonQuoted(text);
        if (value instanceof Character character) return jsonQuoted(String.valueOf(character));
        return display(value);
    }

    /** Display of a map key: quoted when it is a string, else plain. */
    private static String displayMapKey(Object key) {
        if (key instanceof String text) return jsonQuoted(text);
        if (key instanceof Character character) return jsonQuoted(String.valueOf(character));
        return display(key);
    }

    private static String jsonQuoted(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            switch (codePoint) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                default -> {
                    if (codePoint < 0x20) {
                        result.append(String.format("\\u%04x", codePoint));
                    } else {
                        result.appendCodePoint(codePoint);
                    }
                }
            }
            offset += Character.charCount(codePoint);
        }
        return result.append('"').toString();
    }

    private static Object add(Object left, Object right) {
        if (left instanceof String || right instanceof String) {
            return display(left) + display(right);
        }
        if (left instanceof List<?> || right instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            if (left instanceof List<?> list) list.forEach(item -> result.add(deepCopy(item)));
            else result.add(deepCopy(left));
            if (right instanceof List<?> list) list.forEach(item -> result.add(deepCopy(item)));
            else result.add(deepCopy(right));
            return result;
        }
        return numeric(left, right, "+");
    }

    private static Object numeric(Object left, Object right, String operator) {
        Number leftNumber = requireNumber(left, operator);
        Number rightNumber = requireNumber(right, operator);
        boolean integral = isIntegral(leftNumber) && isIntegral(rightNumber);
        if (operator.equals("/") && rightNumber.doubleValue() == 0.0d) {
            throw new FclRuntimeException("Division by zero");
        }
        if (operator.equals("%") && rightNumber.doubleValue() == 0.0d) {
            throw new FclRuntimeException("Division by zero");
        }
        if (integral && !operator.equals("/")) {
            if (leftNumber instanceof BigInteger || rightNumber instanceof BigInteger) {
                BigInteger a = toBigInteger(leftNumber);
                BigInteger b = toBigInteger(rightNumber);
                return switch (operator) {
                    case "+" -> a.add(b);
                    case "-" -> a.subtract(b);
                    case "*" -> a.multiply(b);
                    case "%" -> a.remainder(b);
                    default -> throw new FclRuntimeException("Unsupported numeric operator: " + operator);
                };
            }
            long a = leftNumber.longValue();
            long b = rightNumber.longValue();
            try {
                return switch (operator) {
                    case "+" -> Math.addExact(a, b);
                    case "-" -> Math.subtractExact(a, b);
                    case "*" -> Math.multiplyExact(a, b);
                    case "%" -> a % b;
                    default -> throw new FclRuntimeException("Unsupported numeric operator: " + operator);
                };
            } catch (ArithmeticException overflow) {
                throw new FclRuntimeException("Integer overflow in " + operator);
            }
        }
        double a = leftNumber.doubleValue();
        double b = rightNumber.doubleValue();
        return switch (operator) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> a / b;
            case "%" -> a % b;
            default -> throw new FclRuntimeException("Unsupported numeric operator: " + operator);
        };
    }

    private static Object negate(Number number) {
        if (number instanceof BigInteger big) return big.negate();
        if (isIntegral(number)) {
            long value = number.longValue();
            if (value == Long.MIN_VALUE) {
                throw new FclRuntimeException("Integer overflow in unary -");
            }
            return -value;
        }
        return -number.doubleValue();
    }

    private static long length(Object value) {
        if (value == null) return 0L;
        if (value instanceof String text) return text.length();
        if (value instanceof List<?> list) return list.size();
        if (value instanceof Map<?, ?> map) return map.size();
        if (value.getClass().isArray()) return Array.getLength(value);
        return 1L;
    }

    private static boolean equal(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return compareNumbers(a, b) == 0;
        }
        if (left instanceof Character character && right instanceof String text) {
            return text.length() == 1 && text.charAt(0) == character;
        }
        if (left instanceof String text && right instanceof Character character) {
            return text.length() == 1 && text.charAt(0) == character;
        }
        return Objects.equals(left, right);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return compareNumbers(a, b);
        }
        if (left == null || right == null) {
            throw new FclRuntimeException("null cannot be ordered");
        }
        if ((left instanceof Number && right instanceof String)
                || (left instanceof String && right instanceof Number)) {
            throw new FclRuntimeException("Cannot compare string and number");
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            throw new FclRuntimeException("bool cannot be ordered");
        }
        if (left.getClass().isInstance(right) && left instanceof Comparable comparable) {
            return comparable.compareTo(right);
        }
        return display(left).compareTo(display(right));
    }

    private static Object indexReference(Object target, Object index) {
        if (target instanceof List<?> list) {
            return list.get(listIndex(index, list.size()));
        }
        if (target instanceof Map<?, ?> map) {
            Object key = normalizeKey(index);
            if (!map.containsKey(key)) {
                throw new FclRuntimeException("Map key does not exist: " + display(index));
            }
            return map.get(key);
        }
        throw new FclRuntimeException("Value is not indexable: " + typeOf(target));
    }

    private static Object normalizeKey(Object key) {
        if (key instanceof Number number) {
            if (number instanceof Long || number instanceof Integer || number instanceof Short
                    || number instanceof Byte) {
                return number.longValue();
            }
            if (number instanceof java.math.BigInteger bigInteger) {
                try {
                    return bigInteger.longValueExact();
                } catch (ArithmeticException overflow) {
                    return key;
                }
            }
            double value = number.doubleValue();
            if (Double.isFinite(value) && value == Math.rint(value)
                    && value >= -9007199254740992.0 && value <= 9007199254740992.0) {
                return (long) value;
            }
        }
        return key;
    }

    private static int listIndex(Object value, int size) {
        Number number = requireNumber(value, "Index");
        int position = number.intValue();
        if (number.doubleValue() != position || position < 0 || position >= size) {
            throw new FclRuntimeException("Index out of range: " + value);
        }
        return position;
    }

    private static boolean isIntegral(Number number) {
        return number instanceof Byte || number instanceof Short || number instanceof Integer
                || number instanceof Long || number instanceof BigInteger;
    }

    private static BigInteger toBigInteger(Number number) {
        if (number instanceof BigInteger big) return big;
        return BigInteger.valueOf(number.longValue());
    }

    private static java.math.BigDecimal exactDecimal(Number number) {
        if (number instanceof BigInteger bigInteger) {
            return new java.math.BigDecimal(bigInteger);
        }
        return isIntegral(number)
                ? java.math.BigDecimal.valueOf(number.longValue())
                : java.math.BigDecimal.valueOf(number.doubleValue());
    }

    private static int compareNumbers(Number left, Number right) {
        if (isIntegral(left) && isIntegral(right)) {
            if (left instanceof BigInteger || right instanceof BigInteger) {
                return toBigInteger(left).compareTo(toBigInteger(right));
            }
            return Long.compare(left.longValue(), right.longValue());
        }
        double leftValue = left.doubleValue();
        double rightValue = right.doubleValue();
        if (!Double.isFinite(leftValue) || !Double.isFinite(rightValue)) {
            return Double.compare(leftValue, rightValue);
        }
        return exactDecimal(left).compareTo(exactDecimal(right));
    }
}
