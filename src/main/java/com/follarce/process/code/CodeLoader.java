package com.follarce.process.code;

import com.follarce.log.Logger;
import com.follarce.process.boundary.BoundaryTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码加载与预处理器 —— 负责从进程数据加载代码、剔除注释、预扫描边界表。
 * <p>
 * 替代了原 {@code ProcessRunner.expandInlineBraces()} 的功能，
 * 按照 ARCHITECTURE.md 的约定：{@code {} 和 {@code }} 独占一行。
 * <p>
 * 不再做行内花括号展开 —— 边界表通过语句级行号匹配，
 * 不依赖字符级括号计数，因此不会误伤 map 字面量。
 */
public class CodeLoader {

    private List<String> rawCodeLines;
    private List<String> codeLines;
    private BoundaryTable boundaryTable;

    public CodeLoader() {}

    /**
     * 从源代码行列表加载并预处理。
     * <p>
     * 处理流程：注释剔除 → 行内花括号展开（if/while 专用）→ 边界表扫描。
     *
     * @param rawLines 原始代码行（可能包含注释）
     * @return 预处理后的干净代码行
     */
    public List<String> load(List<String> rawLines) {
        this.rawCodeLines = new ArrayList<>(rawLines);
        this.codeLines = stripComments(rawLines);
        this.codeLines = splitControlBraces(codeLines);
        this.boundaryTable = BoundaryTable.scan(codeLines);
        return codeLines;
    }

    /**
     * 从字符串内容加载代码（用于 exec 替换等场景）。
     *
     * @param content 源代码字符串（以换行符分隔）
     * @return 预处理后的代码行
     */
    public List<String> loadFromString(String content) {
        if (content == null || content.trim().isEmpty()) {
            this.codeLines = new ArrayList<>();
            this.boundaryTable = BoundaryTable.scan(new ArrayList<>());
            return codeLines;
        }
        List<String> lines = new ArrayList<>();
        for (String l : content.split("\n")) {
            lines.add(l);
        }
        return load(lines);
    }

    /**
     * 获取预处理后的代码行。
     */
    public List<String> getCodeLines() {
        return codeLines;
    }

    /**
     * 获取当前代码的边界表。
     */
    public BoundaryTable getBoundaryTable() {
        return boundaryTable;
    }

    /**
     * 剔除代码行中的注释。
     * <ul>
     *   <li>{@code //} 和 {@code #} 开头的行为注释行 → 剔除</li>
     *   <li>行内 {@code //} 后的内容 → 剔除（但保留行首部分）</li>
     * </ul>
     */
    public static List<String> stripComments(List<String> lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            // 全行注释
            if (trimmed.startsWith("//") || trimmed.startsWith("#")) {
                continue;
            }
            // 行内注释
            int ci = findInlineComment(trimmed);
            String clean = ci >= 0 ? trimmed.substring(0, ci).trim() : trimmed;
            if (!clean.isEmpty()) {
                result.add(clean);
            }
        }
        return result;
    }

    /**
     * 查找行内注释位置（跳过字符串内的 //）。
     */
    private static int findInlineComment(String line) {
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
                return i;
            }
        }
        return -1;
    }

    /**
     * 对代码行重新扫描边界表（代码行变化后调用）。
     */
    public void rescanBoundaries() {
        if (codeLines != null) {
            this.boundaryTable = BoundaryTable.scan(codeLines);
        }
    }

    // ════════════════════════════════════════════
    // 轻量级行内花括号展开（仅限 if/while，不会误伤 map 字面量）
    // ════════════════════════════════════════════

    /**
     * 将 if/while 语句中的行内花括号拆分为独占一行。
     * <p>
     * 例如 {@code while true { x = 1 }} → [{@code while true}, {@code {}}, {@code x = 1}, {@code }}]
     * <p>
     * 这是 {@code expandInlineBraces} 的安全替代品：仅扫描以 {@code if} / {@code while}
     * 开头的行，不会误伤 map 字面量中的花括号。
     */
    static List<String> splitControlBraces(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size() + 8);
        for (String line : lines) {
            String trimmed = line.trim();

            // 仅处理 if/while 开头的行
            boolean isControl =
                    trimmed.startsWith("if ") || trimmed.startsWith("if(") ||
                    trimmed.startsWith("while ") || trimmed.startsWith("while(");
            if (!isControl || !trimmed.contains("{")) {
                result.add(line);
                continue;
            }

            int openPos = trimmed.indexOf('{');
            String before = trimmed.substring(0, openPos).trim();

            // 先把条件行写入
            if (!before.isEmpty()) {
                result.add(before);
            }
            result.add("{"); // { 独占一行

            // 处理 { 后面的内容
            String after = trimmed.substring(openPos + 1).trim();
            if (!after.isEmpty()) {
                // 检查是否有 }
                int closePos = findMatchingCloseBrace(after);
                if (closePos >= 0) {
                    String body = after.substring(0, closePos).trim();
                    if (!body.isEmpty()) {
                        result.add(body);
                    }
                    result.add("}");
                    String rest = after.substring(closePos + 1).trim();
                    if (!rest.isEmpty()) {
                        result.add(rest);
                    }
                } else {
                    // 没有 }，整段作为 body
                    result.add(after);
                }
            }
        }
        return result;
    }

    /**
     * 在字符串中查找匹配的闭合花括号位置（跳过字符串内的花括号）。
     * 用于 {@link #splitControlBraces} 中的单行 body。 
     */
    private static int findMatchingCloseBrace(String s) {
        int depth = 1;
        boolean inString = false;
        char stringChar = '"';
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        int lineCount = codeLines != null ? codeLines.size() : 0;
        int bCount = boundaryTable != null ? boundaryTable.getAllEntries().size() : 0;
        return "CodeLoader{lines=" + lineCount + ",boundaries=" + bCount + "}";
    }
}
