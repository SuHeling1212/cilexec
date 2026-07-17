package com.follarce.process;

import com.follarce.function.FunctionContext;
import com.follarce.function.FunctionRegistry;
import com.follarce.exception.ProcessException;
import com.follarce.exception.UnrecoverableException;
import com.follarce.log.Logger;
import com.follarce.script.*;


import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表达式求值器 —— 负责 FCL 表达式的解析与求值。
 * <p>
 * 职责：
 * <ul>
 *   <li>字符串表达式 → AST → 求值（委托给 {@link Lexer} + {@link Parser} + {@link NodeEvaluator}）</li>
 *   <li>布尔表达式求值（用于 if/while 条件）</li>
 *   <li>特殊标记处理（KILL:, WAIT, FORK 等 —— 通过回调通知协调器）</li>
 * </ul>
 * <p>
 * 所有正则模式常量集中在此类。
 */
public class ExpressionEvaluator {

    // ── 语句模式匹配 ──
    public static final Pattern FUNC_PATTERN =
            Pattern.compile("^func\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)\\s*\\{?\\s*$");
    public static final Pattern IMPORT_PATTERN =
            Pattern.compile("^import\\s+\"([^\"]+)\"\\s*$");
    public static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^include\\s+\"([^\"]+)\"\\s*$");
    public static final Pattern IF_PATTERN =
            Pattern.compile("^if\\s*\\(?([^{)]+)\\)?\\s*\\{?.*");
    public static final Pattern WHILE_PATTERN =
            Pattern.compile("^while\\s*\\(?([^{)]+)\\)?\\s*\\{?.*");
    public static final Pattern ASSIGN_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+)$");
    public static final Pattern INDEX_ASSIGN_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\[([^\\]]+)\\]\\s*=\\s*(.+)$");
    public static final Pattern FORK_PATTERN =
            Pattern.compile("^\\s*fork\\s*\\(\\s*\\)\\s*$");
    public static final Pattern RETURN_PATTERN =
            Pattern.compile("^return\\b\\s*(.*)$");
    public static final Pattern BREAK_PATTERN =
            Pattern.compile("^break\\s*$");
    public static final Pattern CONTINUE_PATTERN =
            Pattern.compile("^continue\\s*$");
    public static final Pattern EXEC_PATTERN =
            Pattern.compile("^exec\\s*\\(\\s*(.*)\\s*\\)\\s*$");

    private final int pid;
    private final IntSupplier ppidSupplier;
    private final BiConsumer<String, List<Object>> functionArgCallback;
    private final Supplier<FunctionContext> functionContextSupplier;
    private NodeEvaluator nodeEvaluator;
    private Map<String, Object> data;

    public ExpressionEvaluator(int pid, IntSupplier ppidSupplier,
                               BiConsumer<String, List<Object>> functionArgCallback) {
        this(pid, ppidSupplier, functionArgCallback,
                () -> new FunctionContext(pid, ppidSupplier.getAsInt(), "local"));
    }

    public ExpressionEvaluator(int pid, IntSupplier ppidSupplier,
                               BiConsumer<String, List<Object>> functionArgCallback,
                               Supplier<FunctionContext> functionContextSupplier) {
        this.pid = pid;
        this.ppidSupplier = ppidSupplier;
        this.functionArgCallback = functionArgCallback;
        this.functionContextSupplier = functionContextSupplier;
    }

    /**
     * 设置当前进程变量映射（每次 loadFromFile/step 前更新）。
     */
    public void setData(Map<String, Object> data) {
        this.data = data;
        rebuildNodeEvaluator();
    }

    public Map<String, Object> getData() { return data; }

    private void rebuildNodeEvaluator() {
        this.nodeEvaluator = new NodeEvaluator(data, functionContextSupplier);
        if (functionArgCallback != null) {
            this.nodeEvaluator.setFunctionArgCallback(functionArgCallback);
        }
    }

    // ════════════════════════════════════════════
    // 表达式求值
    // ════════════════════════════════════════════

    /**
     * 求值一个 FCL 表达式字符串。
     *
     * @param expression 原始表达式字符串
     * @return 求值结果（可能为 String marker, Number, Boolean, List, Map 等）
     */
    public Object evaluateExpression(String expression) {
        try {
            // 纯数字
            if (expression.matches("-?\\d+")) {
                return Long.parseLong(expression);
            }
            if (expression.matches("-?\\d+\\.\\d+")) {
                return Double.parseDouble(expression);
            }

            // 纯字符串字面量 —— 仅当整个表达式是一对引号包围的单段字符串
            // 排除多段拼接如 "Hello" + "World"（包含多对引号）
            if (expression.startsWith("\"") && expression.endsWith("\"")
                    && expression.length() >= 2
                    && countUnescapedDoubleQuotes(expression) == 2) {
                String inner = expression.substring(1, expression.length() - 1);
                return unescapeFclString(inner);
            }

            // 布尔字面量
            if ("true".equals(expression)) return true;
            if ("false".equals(expression)) return false;
            if ("null".equals(expression)) return null;

            // 纯标识符（变量名）—— 直接查表
            if (expression.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                if (data.containsKey(expression)) {
                    return data.get(expression);
                }
                // 特殊：FORK / KILL: / WAIT 等标记字面量
                return detectSpecialMarkerValue(expression);
            }

            // 复杂表达式 → 走 Lexer + Parser + NodeEvaluator
            return evaluateComplex(expression);

        } catch (Exception e) {
            Logger.warn("Expression evaluation error in PID " + pid + ": " + e.getMessage()
                    + " | expr=" + expression);
            if (e instanceof ProcessException processException) throw processException;
            // Preserve FCL's existing implicit-null behavior for first-use variables.
            if (e.getMessage() != null && e.getMessage().startsWith("Undefined variable '")) {
                return null;
            }
            if ("Division by zero".equals(e.getMessage())) {
                throw UnrecoverableException.divisionByZero();
            }
            throw new UnrecoverableException("Expression evaluation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 求值布尔表达式。
     */
    public boolean evaluateToBoolean(String expression) {
        Object result = evaluateExpression(expression);
        return isTruthy(result);
    }

    private Object evaluateComplex(String expression) {
        Lexer lexer = new Lexer(expression);
        List<Token> tokens = lexer.tokenize();
        Parser parser = new Parser(tokens);
        AstNode ast = parser.parse();
        return nodeEvaluator.evaluate(ast);
    }

    // ════════════════════════════════════════════
    // 特殊标记处理
    // ════════════════════════════════════════════

    /**
     * 检测标识符是否是特殊标记字面量。
     * 用于赋值语境中：{@code x = fork()} 返回 "FORK" 标记。
     */
    private Object detectSpecialMarkerValue(String name) {
        if (name == null) return null;
        if (name.equals("FORK")) return "FORK";
        if (name.startsWith("KILL:")) return name;
        if (name.equals("WAIT")) return "WAIT";
        if (name.startsWith("WAITPID:")) return name;
        if (name.startsWith("PAUSE:")) return name;
        if (name.startsWith("CONTINUE:")) return name;
        if (name.startsWith("USER:")) return name;
        return null;
    }

    /**
     * 处理特殊标记（赋值语境中求值返回的标记字符串）。
     *
     * @param marker 标记字符串（FORK, KILL:123, WAIT, WAITPID:5 等）
     * @param varName 被赋值的变量名（可能为 null）
     * @return 标记处理的结果值，或原始 marker 如果不需处理
     */
    public Object processMarker(String marker, String varName) {
        if (marker == null) return null;
        // 标记处理在 ProcessRunner 的 handleAssignment 中进行，
        // 此处返回解析后的信息，由协调器决定具体操作
        return marker;
    }

    // ════════════════════════════════════════════
    // 类型转换辅助
    // ════════════════════════════════════════════

    public static boolean isTruthy(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).doubleValue() != 0;
        if (val instanceof String) return !((String) val).isEmpty();
        if (val instanceof List) return !((List<?>) val).isEmpty();
        if (val instanceof Map) return !((Map<?, ?>) val).isEmpty();
        return true;
    }

    /**
     * 执行函数参数回调（由 ProcessRunner 在赋值/表达式上下文中调用）。
     */
    public void notifyFunctionCall(String funcName, List<Object> args) {
        if (functionArgCallback != null) {
            functionArgCallback.accept(funcName, args);
        }
    }

    // ════════════════════════════════════════════
    // 字符串转义（内联版，替代包私有的 StringEscape）
    // ════════════════════════════════════════════

    /**
     * 将字符串中的转义序列解析为实际字符。
     * 支持：\n, \t, \r, \", \\
     */
    private static String unescapeFclString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n':  sb.append('\n'); i++; break;
                    case 't':  sb.append('\t'); i++; break;
                    case 'r':  sb.append('\r'); i++; break;
                    case '"':  sb.append('"');  i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    default:   sb.append(c);    break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 统计字符串中未被转义的双引号数量。
     * 用于区分纯字符串字面量（2 个引号）和多段拼接表达式（≥4 个引号）。
     */
    private static int countUnescapedDoubleQuotes(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && i + 1 < s.length()) {
                i++; // 跳过转义字符
                continue;
            }
            if (s.charAt(i) == '"') count++;
        }
        return count;
    }
}
