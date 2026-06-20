package com.follarce.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码处理工具 —— 提供注释剔除等静态方法。
 * <p>
 * 从原 {@link com.follarce.process.code.CodeLoader} 迁移而来，
 * 在移除 CodeLoader 后作为替代。
 */
public final class CodeUtil {

    private CodeUtil() {}

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
            if (trimmed.startsWith("//") || trimmed.startsWith("#")) {
                continue;
            }
            int ci = findInlineComment(trimmed);
            String clean = ci >= 0 ? trimmed.substring(0, ci).trim() : trimmed;
            if (!clean.isEmpty()) {
                result.add(clean);
            }
        }
        return result;
    }

    /**
     * 查找行内注释位置（跳过字符串内的 // 和 #）。
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
            if (c == '#') {
                if (i == 0 || Character.isWhitespace(line.charAt(i - 1))
                        || line.charAt(i - 1) == '{' || line.charAt(i - 1) == '(') {
                    return i;
                }
            }
        }
        return -1;
    }
}
