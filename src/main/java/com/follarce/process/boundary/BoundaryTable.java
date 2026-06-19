package com.follarce.process.boundary;

import java.util.*;

/**
 * 边界表 —— 预扫描 if/while 的括号匹配范围。
 * <p>
 * 扫描规则（遵循 ARCHITECTURE.md）：
 * <ul>
 *   <li>{@code {} 和 {@code }} 应当独占一行</li>
 *   <li>Map 字面量的 {} 不会进入边界表（只有 if/while 的才记录）</li>
 *   <li>嵌套结构递归处理</li>
 * </ul>
 * <p>
 * 本实现不依赖字符串内括号检测（与 {@code expandInlineBraces} 不同），
 * 仅在语句级别识别 if/while 的起止行号。
 */
public class BoundaryTable {

    private final Map<Integer, BoundaryEntry> entriesByStartLine = new LinkedHashMap<>();

    private BoundaryTable() {}

    /**
     * 扫描代码行列表，生成 if/while 的边界表。
     *
     * @param codeLines 已剔除注释的代码行（每行一条语句）
     * @return 边界表（可能为空）
     */
    public static BoundaryTable scan(List<String> codeLines) {
        BoundaryTable table = new BoundaryTable();
        if (codeLines == null || codeLines.isEmpty()) return table;

        // 使用栈来匹配嵌套控制流
        Deque<ScanFrame> stack = new ArrayDeque<>();

        for (int i = 0; i < codeLines.size(); i++) {
            String raw = codeLines.get(i);
            String line = raw.trim();

            if (line.isEmpty()) continue;

            // 检测 if/while 语句（支持 if(、while(、if 、while 四种格式）
            if (line.startsWith("if ") || line.startsWith("if(")) {
                String condition = extractCondition(line, "if");
                stack.push(new ScanFrame(detectType(line), condition, i));
                continue;
            }
            if (line.startsWith("while ") || line.startsWith("while(")) {
                String condition = extractCondition(line, "while");
                stack.push(new ScanFrame(BoundaryEntry.Type.WHILE, condition, i));
                continue;
            }

            // 检测花括号闭合 —— 只处理行首的 }
            if (line.equals("}") || line.startsWith("}")) {
                if (!stack.isEmpty()) {
                    ScanFrame frame = stack.pop();
                    // bodyStart: conditionLine 之后跳过 { 所在行
                    int bodyStart = findBodyStart(codeLines, frame.conditionLine, i);
                    int bodyEnd = i;
                    table.entriesByStartLine.put(frame.conditionLine,
                            new BoundaryEntry(frame.type, frame.condition, frame.conditionLine, bodyStart, bodyEnd));
                }
                continue;
            }

            // 忽略 func 定义的 {}（函数体由 FunctionManager 管理）
            if (line.startsWith("func ")) {
                i = skipFunctionBody(codeLines, i);
                continue;
            }
        }

        return table;
    }

    /**
     * 获取指定起始行的边界条目。
     */
    public BoundaryEntry getEntryAtLine(int line) {
        return entriesByStartLine.get(line);
    }

    /**
     * 指定行是否是 if/while 的起始行。
     */
    public boolean isControlStart(int line) {
        return entriesByStartLine.containsKey(line);
    }

    /**
     * 获取所有条目（只读）。
     */
    public Collection<BoundaryEntry> getAllEntries() {
        return Collections.unmodifiableCollection(entriesByStartLine.values());
    }

    public boolean isEmpty() {
        return entriesByStartLine.isEmpty();
    }

    @Override
    public String toString() {
        return "BoundaryTable{" + entriesByStartLine.values() + "}";
    }

    // ── 内部辅助 ──

    private static BoundaryEntry.Type detectType(String line) {
        return line.startsWith("while") ? BoundaryEntry.Type.WHILE : BoundaryEntry.Type.IF;
    }

    /**
     * 从 "if(x > 0) {" 或 "while x < 5 {" 中提取条件表达式。
     */
    private static String extractCondition(String line, String keyword) {
        String after = line.substring(keyword.length()).trim();
        // 移除前导 (
        if (after.startsWith("(")) {
            after = after.substring(1);
        }
        // 移除尾部的 ){ 或 { 或 )
        int braceIdx = after.indexOf('{');
        if (braceIdx >= 0) {
            after = after.substring(0, braceIdx).trim();
        }
        int parenIdx = after.lastIndexOf(')');
        if (parenIdx >= 0 && parenIdx == after.length() - 1) {
            after = after.substring(0, parenIdx).trim();
        }
        return after.trim();
    }

    /**
     * 找到 conditionLine 对应的 body 起始行。
     * bodyStart = conditionLine+1 跳过可能的 { 行
     */
    private static int findBodyStart(List<String> codeLines, int conditionLine, int closeLine) {
        int start = conditionLine + 1;
        // 跳过前导 { 行
        while (start < closeLine) {
            String trimmed = codeLines.get(start).trim();
            if (trimmed.equals("{") || trimmed.startsWith("{")) {
                start++;
            } else {
                break;
            }
        }
        return Math.min(start, closeLine);
    }

    /**
     * 跳过函数体定义（{ ... }），返回 } 所在行号。
     */
    private static int skipFunctionBody(List<String> codeLines, int startLine) {
        int depth = 0;
        boolean inBody = false;
        for (int i = startLine; i < codeLines.size(); i++) {
            String line = codeLines.get(i).trim();
            // 统计花括号（简单匹配，函数体内假定无 map 字面量的 {} 干扰）
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') {
                    depth++;
                    inBody = true;
                } else if (c == '}') {
                    depth--;
                    if (inBody && depth == 0) {
                        return i; // 返回 } 所在行
                    }
                }
            }
            // 如果一行内闭合（如 func x() {}）
            if (inBody && depth == 0) {
                return i;
            }
        }
        return codeLines.size() - 1;
    }

    /**
     * 扫描栈帧（内部辅助类）。
     */
    private static class ScanFrame {
        final BoundaryEntry.Type type;
        final String condition;
        final int conditionLine;

        ScanFrame(BoundaryEntry.Type type, String condition, int conditionLine) {
            this.type = type;
            this.condition = condition;
            this.conditionLine = conditionLine;
        }
    }
}
