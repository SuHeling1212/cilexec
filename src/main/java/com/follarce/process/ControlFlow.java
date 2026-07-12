package com.follarce.process;

import com.follarce.log.Logger;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 控制流管理器 —— 基于边界表 + BlockStack 处理 if/while/break/continue/return。
 * <p>
 * 根据 ARCHITECTURE.md，执行流程为：
 * <pre>
 * 预扫描 → 边界表 → 执行时查表 + BlockStack 跟踪嵌套
 * if true → BlockStack push → ip = bodyStart
 * if false → ip = bodyEnd + 1
 * while true → BlockStack push → ip = bodyStart
 * while false → pop BlockStack → ip = bodyEnd + 1
 * } → 查 BlockStack 栈顶：
 *   IF → 弹出，ip = bodyEnd + 1
 *   WHILE → 重判条件，满足则回到 conditionLine，不满足则弹出
 * break → 找到最近 WHILE，弹出到该层 → ip = bodyEnd + 1
 * </pre>
 */
public class ControlFlow {

    private final ExpressionEvaluator evaluator;
    private List<String> codeLines;
    private BoundaryTable boundaryTable;
    private List<Map<String, Object>> blockStack;

    public ControlFlow(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
        this.blockStack = new ArrayList<>();
    }

    /**
     * 设置当前代码行和边界表（每次 step() 前调用）。
     */
    public void setCode(List<String> codeLines, BoundaryTable boundaryTable) {
        this.codeLines = codeLines;
        this.boundaryTable = boundaryTable;
    }

    public void setBlockStack(List<Map<String, Object>> blockStack) {
        this.blockStack = blockStack != null ? blockStack : new ArrayList<>();
    }

    public List<Map<String, Object>> getBlockStack() { return blockStack; }

    // ════════════════════════════════════════════
    // 控制流处理
    // ════════════════════════════════════════════

    /**
     * 处理 if 语句。
     *
     * @param condition 条件表达式
     * @param currentLine 当前执行行号
     * @return 执行后的下一行行号
     */
    @SuppressWarnings("unchecked")
    public int handleIf(String condition, int currentLine) {
        boolean result = evaluator.evaluateToBoolean(condition);
        BoundaryEntry entry = boundaryTable != null ? boundaryTable.getEntryAtLine(currentLine) : null;
        if (result) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "IF");
            block.put("startLine", currentLine);
            block.put("condition", condition);
            // 有 else 时记录最终 }，handleClosingBraces 据此跳过 else body
            if (entry != null && entry.hasElse()) {
                block.put("elseBodyEnd", entry.getBodyEnd());
            }
            blockStack.add(block);
            if (entry != null) {
                return entry.getBodyStart();
            }
            return currentLine + 1;
        } else {
            // 有 else 时跳到 else body，否则跳到 bodyEnd + 1
            if (entry != null) {
                if (entry.hasElse()) {
                    return entry.getElseBodyStart();
                }
                return entry.getBodyEnd() + 1;
            }
            return skipToMatchingBrace(currentLine + 1);
        }
    }

    /**
     * 处理 while 语句。
     *
     * @param condition 条件表达式
     * @param currentLine 当前执行行号
     * @return 执行后的下一行行号
     */
    @SuppressWarnings("unchecked")
    public int handleWhile(String condition, int currentLine) {
        boolean result = evaluator.evaluateToBoolean(condition);

        boolean alreadyInLoop = false;
        for (int i = blockStack.size() - 1; i >= 0; i--) {
            Map<String, Object> block = blockStack.get(i);
            if ("WHILE".equals(block.get("type"))
                    && ((Number) block.get("startLine")).intValue() == currentLine) {
                alreadyInLoop = true;
                break;
            }
        }

        BoundaryEntry entry = boundaryTable != null ? boundaryTable.getEntryAtLine(currentLine) : null;

        if (result) {
            if (!alreadyInLoop) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "WHILE");
                block.put("startLine", currentLine);
                block.put("condition", condition);
                blockStack.add(block);
            }
            return entry != null ? entry.getBodyStart() : currentLine + 1;
        } else {
            if (alreadyInLoop) {
                blockStack.remove(blockStack.size() - 1);
            }
            return entry != null ? entry.getBodyEnd() + 1 : skipToMatchingBrace(currentLine + 1);
        }
    }

    /**
     * 处理闭合花括号行。
     *
     * @param count 连续闭合的 } 数量
     * @param currentLine 当前行号
     * @return 执行后下一行行号
     */
    @SuppressWarnings("unchecked")
    public int handleClosingBraces(int count, int currentLine) {
        int nextLine = currentLine + 1;
        for (int i = 0; i < count; i++) {
            if (blockStack.isEmpty()) {
                return nextLine;
            }
            Map<String, Object> block = blockStack.get(blockStack.size() - 1);
            String type = (String) block.get("type");
            int startLine = ((Number) block.get("startLine")).intValue();
            BoundaryEntry entry = boundaryTable != null ? boundaryTable.getEntryAtLine(startLine) : null;

            if ("WHILE".equals(type)) {
                String condition = (String) block.get("condition");
                if (evaluator.evaluateToBoolean(condition)) {
                    return startLine;
                }
                blockStack.remove(blockStack.size() - 1);
            } else if ("IF".equals(type)) {
                if (block.containsKey("elseBodyEnd")) {
                    int finalEnd = ((Number) block.get("elseBodyEnd")).intValue();
                    blockStack.remove(blockStack.size() - 1);
                    return finalEnd + 1;
                }
                blockStack.remove(blockStack.size() - 1);
            } else if ("SWITCH".equals(type)) {
                int endLine = ((Number) block.get("endLine")).intValue();
                if (currentLine == endLine) {
                    blockStack.remove(blockStack.size() - 1);
                }
            }
        }
        return nextLine;
    }

    /**
     * 处理 break 语句。
     *
     * @param currentLine 当前行号
     * @return break 后的下一行行号
     */
    @SuppressWarnings("unchecked")
    public int handleBreak(int currentLine) {
        int defaultNext = currentLine + 1;

        for (int i = blockStack.size() - 1; i >= 0; i--) {
            Map<String, Object> block = blockStack.get(i);
            if ("WHILE".equals(block.get("type"))) {
                int whileStartLine = ((Number) block.get("startLine")).intValue();
                // 弹出该 WHILE 及以上所有层
                int removeCount = blockStack.size() - i;
                for (int j = 0; j < removeCount; j++) {
                    blockStack.remove(blockStack.size() - 1);
                }
                // 跳到 while 体结束 + 1
                BoundaryEntry entry = boundaryTable != null ? boundaryTable.getEntryAtLine(whileStartLine) : null;
                if (entry != null) {
                    return entry.getBodyEnd() + 1;
                }
                return skipToMatchingBrace(currentLine + 1);
            }
        }

        Logger.warn("break outside while loop");
        return defaultNext;
    }

    /**
     * 处理 continue 语句。
     *
     * @param currentLine 当前行号
     * @return continue 后的下一行行号（回到 while condition 行）
     */
    @SuppressWarnings("unchecked")
    public int handleContinue(int currentLine) {
        int defaultNext = currentLine + 1;

        for (int i = blockStack.size() - 1; i >= 0; i--) {
            Map<String, Object> block = blockStack.get(i);
            if ("WHILE".equals(block.get("type"))) {
                return ((Number) block.get("startLine")).intValue();
            }
        }

        Logger.warn("continue outside while loop");
        return defaultNext;
    }

    // ════════════════════════════════════════════
    // switch/case
    // ════════════════════════════════════════════

    /**
     * 处理 switch 语句。
     * 扫描后续代码行查找 case 值，匹配时进入对应 body，无匹配时进入 default。
     * 每个 case 执行完毕后隐式 break（不会 fall-through）。
     *
     * @param expr       switch 表达式
     * @param currentLine 当前行号
     * @return 执行后下一行行号
     */
    @SuppressWarnings("unchecked")
    public int handleSwitch(String expr, int currentLine) {
        Object switchValue = evaluator.evaluateExpression(expr);

        // 找到 switch 块结束的行号（匹配的 }）
        int endLine = findSwitchEnd(currentLine);

        // 扫描 case 行
        int i = currentLine + 1;
        while (i < codeLines.size() && i <= endLine) {
            String trimmed = codeLines.get(i).trim();

            if (trimmed.startsWith("case ")) {
                String caseExpr = trimmed.substring(5).trim();
                Object caseValue = evaluator.evaluateExpression(caseExpr);

                if (compareSwitchValues(switchValue, caseValue)) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "SWITCH");
                    block.put("startLine", currentLine);
                    block.put("endLine", endLine);
                    block.put("matched", true);
                    blockStack.add(block);
                    return i + 2; // case X   + 1 → {   + 1 → body 首行
                } else {
                    // 不匹配，跳过此 case 的 body
                    i = skipPastCaseBody(i + 1, endLine);
                    continue;
                }
            }

            if (trimmed.startsWith("default")) {
                // 无匹配，进入 default
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "SWITCH");
                block.put("startLine", currentLine);
                block.put("endLine", endLine);
                blockStack.add(block);
                return i + 2; // default + 1 → {   + 1 → body 首行
            }

            i++;
        }

        // 无匹配且无 default → 跳过整个 switch
        return endLine + 1;
    }

    /**
     * 从 start 行开始跳过整个 case body（到下一个 case/default 或 switch end）。
     */
    private int skipPastCaseBody(int start, int endLine) {
        int depth = 0;
        for (int i = start; i <= endLine; i++) {
            String trimmed = codeLines.get(i).trim();
            int[] counts = countBraces(trimmed);
            depth += counts[0] - counts[1];
            if (depth < 0) return i - 1;        // switch 的 }，回退一行等循环 ++
            if (depth == 0 && (trimmed.startsWith("case ") || trimmed.equals("default"))) {
                return i - 1;                    // 下一个 case/default，回退一行等循环 ++
            }
        }
        return endLine;
    }

    /**
     * 查找 switch 块的结束行号（从 switch 行开始数配对的 }）。
     */
    private int findSwitchEnd(int startLine) {
        int depth = 0;
        // switch 行自带 {（如 "switch x {"），depth 从 0 到 1
        // 扫描后续行，switch 的 } 让 depth 回到 0
        for (int i = startLine; i < codeLines.size(); i++) {
            String line = codeLines.get(i).trim();
            int[] counts = countBraces(line);
            depth += counts[0] - counts[1];
            if (depth <= 0) return i;
        }
        return codeLines.size() - 1;
    }

    /**
     * 比较 switch 值和 case 值，兼容 Integer/Long/Float/Double 的混合。
     */
    private static boolean compareSwitchValues(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    /**
     * 处理 return 语句。
     *
     * @param expression return 后面的表达式（可能为空）
     * @param inCallStack 是否在函数调用中
     * @param currentLine 当前行号
     * @return {@link ReturnResult}
     */
    public ReturnResult handleReturn(String expression, boolean inCallStack, int currentLine) {
        Object value = null;
        if (expression != null && !expression.isEmpty()) {
            value = evaluator.evaluateExpression(expression);
        }
        return new ReturnResult(value, inCallStack);
    }

    // ════════════════════════════════════════════
    // 花括号匹配（fallback — 无边界表时使用）
    // ════════════════════════════════════════════

    public int skipToMatchingBrace(int startLine) {
        int depth = 1;
        for (int i = startLine; i < codeLines.size(); i++) {
            int[] counts = countBraces(codeLines.get(i));
            depth += counts[0] - counts[1];
            if (depth <= 0) return i + 1;
        }
        return codeLines.size();
    }

    public static int[] countBraces(String line) {
        int open = 0, close = 0;
        boolean inString = false;
        char stringChar = '"';
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == stringChar) { inString = false; }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            if (c == '{') open++;
            if (c == '}') close++;
        }
        return new int[]{open, close};
    }

    // ── 内部类 ──

    /**
     * return 语句处理结果。
     */
    public static class ReturnResult {
        public final Object value;
        public final boolean hasCaller; // 是否有调用者（在函数调用中）

        ReturnResult(Object value, boolean hasCaller) {
            this.value = value;
            this.hasCaller = hasCaller;
        }
    }
}
