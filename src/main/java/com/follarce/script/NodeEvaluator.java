package com.follarce.script;

import com.follarce.function.FunctionContext;
import com.follarce.function.FunctionRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Evaluates an AST (produced by Parser) against a variable map.
 */
public class NodeEvaluator {

    private final Map<String, Object> data;
    private final int pid;
    private final int ppid;
    private final String currentUser;
    private final Supplier<FunctionContext> functionContextSupplier;

    // 函数调用参数回调（由 ProcessRunner 设置，用于传递用户函数参数）
    private BiConsumer<String, List<Object>> functionArgCallback;

    public NodeEvaluator(Map<String, Object> data, int pid, String currentUser) {
        this(data, pid, 0, currentUser);
    }

    public NodeEvaluator(Map<String, Object> data, int pid, int ppid, String currentUser) {
        this.data = data;
        this.pid = pid;
        this.ppid = ppid;
        this.currentUser = currentUser != null ? currentUser : "local";
        this.functionContextSupplier = () -> new FunctionContext(pid, ppid, this.currentUser);
    }

    public NodeEvaluator(Map<String, Object> data, Supplier<FunctionContext> functionContextSupplier) {
        this.data = data;
        this.functionContextSupplier = functionContextSupplier;
        FunctionContext initial = functionContextSupplier.get();
        this.pid = initial.getPid();
        this.ppid = initial.getPpid();
        this.currentUser = initial.getCurrentUser();
    }

    public void setFunctionArgCallback(BiConsumer<String, List<Object>> callback) {
        this.functionArgCallback = callback;
    }

    /**
     * Evaluate an AST node and return the result.
     */
    public Object evaluate(AstNode node) {
        if (node == null) {
            return null;
        }

        // ---- Literals ----
        if (node.type == NodeType.NUMBER_LITERAL) {
            return node.value;
        }
        if (node.type == NodeType.STRING_LITERAL) {
            return node.value;
        }
        if (node.type == NodeType.BOOLEAN_LITERAL) {
            return node.value;
        }

        // ---- Identifier (including length operator #name) ----
        if (node.type == NodeType.IDENTIFIER) {
            String name = node.name;
            if (name.startsWith("#")) {
                // Length operator: #varName → size of the value
                String varName = name.substring(1);
                Object val = data.get(varName);
                if (val == null) {
                    return 0L;
                }
                if (val instanceof List) {
                    return (long) ((List<?>) val).size();
                }
                if (val instanceof Map) {
                    return (long) ((Map<?, ?>) val).size();
                }
                if (val instanceof String) {
                    return (long) ((String) val).length();
                }
                if (val instanceof Object[]) {
                    return (long) ((Object[]) val).length;
                }
                // Treat as single-element container
                return 1L;
            }
            // Regular identifier lookup
            Object result = data.get(name);
            if (result == null) {
                // Check if variable exists but is null
                if (data.containsKey(name)) {
                    return null;
                }
                throw new RuntimeException("Undefined variable '" + name + "'");
            }
            return result;
        }

        // ---- Index access ----
        if (node.type == NodeType.INDEX_ACCESS) {
            AstNode targetNode = node.left;  // the collection expression
            AstNode indexNode = node.index;  // the index expression
            Object target = evaluate(targetNode);
            Object index = evaluate(indexNode);
            if (target instanceof List) {
                int i = toIntIndex(index);
                return ((List<?>) target).get(i);
            }
            if (target instanceof Map) {
                return ((Map<?, ?>) target).get(index);
            }
            if (target instanceof Object[]) {
                int i = toIntIndex(index);
                return ((Object[]) target)[i];
            }
            // treat as map-like on object fields? just return null for simplicity
            return null;
        }

        // ---- Array literal ----
        if (node.type == NodeType.ARRAY_LITERAL) {
            List<Object> result = new ArrayList<>();
            if (node.args != null) {
                for (AstNode elem : node.args) {
                    result.add(evaluate(elem));
                }
            }
            return result;
        }

        // ---- Map literal ----
        if (node.type == NodeType.MAP_LITERAL) {
            Map<Object, Object> result = new LinkedHashMap<>();
            if (node.keys != null && node.values != null) {
                int size = Math.min(node.keys.size(), node.values.size());
                for (int i = 0; i < size; i++) {
                    Object key = evaluate(node.keys.get(i));
                    Object val = evaluate(node.values.get(i));
                    result.put(key, val);
                }
            }
            return result;
        }

        // ---- Unary operator ----
        if (node.type == NodeType.UNARY_OP) {
            return evaluateUnaryOp(node);
        }

        // ---- Binary operator ----
        if (node.type == NodeType.BINARY_OP) {
            return evaluateBinaryOp(node);
        }

        // ---- Function call ----
        if (node.type == NodeType.FUNCTION_CALL) {
            return evaluateFunctionCall(node);
        }

        throw new RuntimeException("Unknown node type: " + node.type);
    }

    // ---------------------------------------------------------------
    // Binary operators
    // ---------------------------------------------------------------

    private Object evaluateBinaryOp(AstNode node) {
        Object left = evaluate(node.left);
        Object right = evaluate(node.right);
        String op = node.operator;

        switch (op) {
            case "or":
                return truthy(left) || truthy(right);
            case "and":
                return truthy(left) && truthy(right);
            case "==":
                return eq(left, right);
            case "!=":
                return !eq(left, right);
            case "<":
                return compare(left, right) < 0;
            case ">":
                return compare(left, right) > 0;
            case "<=":
                return compare(left, right) <= 0;
            case ">=":
                return compare(left, right) >= 0;
            case "+":
                return plus(left, right);
            case "-":
                if (isIntegerType(left) && isIntegerType(right)) {
                    return ((Number) left).longValue() - ((Number) right).longValue();
                }
                return asNumber(left) - asNumber(right);
            case "*":
                if (isIntegerType(left) && isIntegerType(right)) {
                    return ((Number) left).longValue() * ((Number) right).longValue();
                }
                return asNumber(left) * asNumber(right);
            case "/":
                double divisor = asNumber(right);
                if (divisor == 0.0) {
                    throw new RuntimeException("Division by zero");
                }
                return asNumber(left) / divisor;
            case "%":
                long divisorLong = asLong(right);
                if (divisorLong == 0) {
                    throw new RuntimeException("Modulo by zero");
                }
                return asLong(left) % divisorLong;
            default:
                throw new RuntimeException("Unknown binary operator '" + op + "'");
        }
    }

    // ---------------------------------------------------------------
    // Unary operators
    // ---------------------------------------------------------------

    private Object evaluateUnaryOp(AstNode node) {
        Object operand = evaluate(node.operand);
        String op = node.operator;

        if ("!".equals(op)) {
            return !truthy(operand);
        }
        if ("-".equals(op)) {
            return -asNumber(operand);
        }
        throw new RuntimeException("Unknown unary operator '" + op + "'");
    }

    // ---------------------------------------------------------------
    // Function call
    // ---------------------------------------------------------------

    private Object evaluateFunctionCall(AstNode node) {
        String functionName = node.name;
        List<Object> argValues = new ArrayList<>();
        if (node.args != null) {
            for (AstNode arg : node.args) {
                argValues.add(evaluate(arg));
            }
        }
        // 通知回调：正在调用函数（参数已评估）
        if (functionArgCallback != null) {
            functionArgCallback.accept(functionName, argValues);
        }
        FunctionContext context = functionContextSupplier.get();
        return FunctionRegistry.call(functionName, argValues, context);
    }

    // ---------------------------------------------------------------
    // Type coercion helpers
    // ---------------------------------------------------------------

    private static boolean truthy(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).doubleValue() != 0;
        if (val instanceof String) return !((String) val).isEmpty();
        if (val instanceof List) return !((List<?>) val).isEmpty();
        if (val instanceof Map) return !((Map<?, ?>) val).isEmpty();
        return true; // other objects are truthy
    }

    private static boolean eq(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        // Numeric comparison: promote both to double if either is a number
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Comparable && b instanceof Comparable) {
            return ((Comparable) a).compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }

    private static double asNumber(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof Boolean) return (Boolean) val ? 1.0 : 0.0;
        if (val instanceof String) {
            try {
                return Double.parseDouble((String) val);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private static long asLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof Boolean) return (Boolean) val ? 1L : 0L;
        if (val instanceof String) {
            try {
                return Long.parseLong((String) val);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    private static boolean isIntegerType(Object val) {
        return val instanceof Long || val instanceof Integer || val instanceof Short || val instanceof Byte;
    }

    private static int toIntIndex(Object val) {
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static Object plus(Object left, Object right) {
        // If either operand is a String, do string concatenation.
        if (left instanceof String || right instanceof String) {
            return stringValue(left) + stringValue(right);
        }
        // If either is a List, do list concatenation.
        if (left instanceof List || right instanceof List) {
            List<Object> result = new ArrayList<>();
            if (left instanceof List) {
                result.addAll((List<?>) left);
            } else {
                result.add(left);
            }
            if (right instanceof List) {
                result.addAll((List<?>) right);
            } else {
                result.add(right);
            }
            return result;
        }
        // Numeric addition
        if (isIntegerType(left) && isIntegerType(right)) {
            return ((Number) left).longValue() + ((Number) right).longValue();
        }
        return asNumber(left) + asNumber(right);
    }

    private static String stringValue(Object val) {
        if (val == null) return "null";
        if (val instanceof Double || val instanceof Float) {
            double d = ((Number) val).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return val.toString();
    }
}
