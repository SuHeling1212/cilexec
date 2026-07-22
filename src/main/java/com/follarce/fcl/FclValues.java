package com.follarce.fcl;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** FCL value semantics kept independent of storage and host services. */
final class FclValues {
    private FclValues() {}

    static Object deepCopy(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(deepCopy(item)));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(deepCopy(key), deepCopy(item)));
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
        if (value instanceof Number number) return number.doubleValue() != 0.0d;
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
            if (!map.containsKey(index)) {
                throw new FclRuntimeException("Map key does not exist: " + display(index));
            }
            return deepCopy(map.get(index));
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
            ((Map<Object, Object>) rawMap).put(finalIndex, deepCopy(value));
            return;
        }
        throw new FclRuntimeException("Value is not assignable by index: " + typeOf(target));
    }

    static Number requireNumber(Object value, String operation) {
        if (value instanceof Number number) return number;
        throw new FclRuntimeException(operation + " requires a number, got " + typeOf(value));
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

    static String display(Object value) {
        if (value == null) return "null";
        return String.valueOf(value);
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
            long a = leftNumber.longValue();
            long b = rightNumber.longValue();
            return switch (operator) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "%" -> a % b;
                default -> throw new FclRuntimeException("Unsupported numeric operator: " + operator);
            };
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
        return isIntegral(number) ? -number.longValue() : -number.doubleValue();
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
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
        return Objects.equals(left, right);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (left == null || right == null) {
            throw new FclRuntimeException("null cannot be ordered");
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
            if (!map.containsKey(index)) {
                throw new FclRuntimeException("Map key does not exist: " + display(index));
            }
            return map.get(index);
        }
        throw new FclRuntimeException("Value is not indexable: " + typeOf(target));
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
                || number instanceof Long;
    }
}
