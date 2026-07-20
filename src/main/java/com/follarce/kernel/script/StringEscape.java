package com.follarce.kernel.script;

/**
 * 字符串转义工具 —— 集中管理 FCL 字符串转义逻辑。
 * <p>
 * 支持转义序列：\n, \t, \r, \", \\
 * 由 Lexer.readString() 和 Parser 共享使用。
 */
final class StringEscape {

    private StringEscape() {}

    /**
     * 将字符串中的转义序列解析为实际字符。
     * <p>
     * 例如 {@code "Hello\\nWorld"} → {@code "Hello\nWorld"}（含实际换行符）。
     *
     * @param s 未经转义处理的原始字符串内容（不含外层引号）
     * @return 转义处理后的字符串
     */
    static String unescape(String s) {
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
}
