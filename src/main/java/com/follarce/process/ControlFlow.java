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
        if (result) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "IF");
            block.put("startLine", currentLine);
            block.put("condition", condition);
            blockStack.add(block);
            // 用边界表定位 bodyStart
            BoundaryEntry entry = boundaryTable != null ? boundaryTable.getEntryAtLine(currentLine) : null;
            if (entry != null) {
                return entry.getBodyStart();
            }
            return currentLine + 1;
        } else {
            // 跳转到 bodyEnd + 1
            BoundaryEntry entry = boundaryTable != null ? boundaryTable.getEntryAtLine(currentLine) : null;
            if (entry != null) {
                return entry.getBodyEnd() + 1;
            }
            // fallback: 手动匹配括号
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
                    // 回到 conditionLine（不弹出 BlockStack）
                    return startLine;
                }
                blockStack.remove(blockStack.size() - 1);
            } else if ("IF".equals(type)) {
                blockStack.remove(blockStack.size() - 1);
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
