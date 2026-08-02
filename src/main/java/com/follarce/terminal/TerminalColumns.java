package com.follarce.terminal;

/** Unicode-aware terminal column measurement shared by the Shell and FCL TUI packages. */
public final class TerminalColumns {
    private TerminalColumns() { }

    /** Counts visible terminal columns while ignoring ANSI CSI formatting sequences. */
    public static int width(String value) {
        if (value == null || value.isEmpty()) return 0;
        int width = 0;
        for (int index = 0; index < value.length();) {
            int ansiEnd = ansiEnd(value, index);
            if (ansiEnd > index) {
                index = ansiEnd;
                continue;
            }
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            width += codePointWidth(codePoint);
        }
        return width;
    }

    /** Returns the longest prefix whose visible width does not exceed {@code maximumWidth}. */
    public static String truncate(String value, int maximumWidth) {
        if (value == null || value.isEmpty() || maximumWidth <= 0) return "";
        StringBuilder result = new StringBuilder(Math.min(value.length(), maximumWidth));
        int width = 0;
        for (int index = 0; index < value.length();) {
            int ansiEnd = ansiEnd(value, index);
            if (ansiEnd > index) {
                result.append(value, index, ansiEnd);
                index = ansiEnd;
                continue;
            }
            int codePoint = value.codePointAt(index);
            int columns = codePointWidth(codePoint);
            if (width + columns > maximumWidth) break;
            result.appendCodePoint(codePoint);
            width += columns;
            index += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static int ansiEnd(String value, int index) {
        if (value.charAt(index) != '\u001b' || index + 1 >= value.length()
                || value.charAt(index + 1) != '[') return index;
        int cursor = index + 2;
        while (cursor < value.length()) {
            char ansi = value.charAt(cursor++);
            if (ansi >= '@' && ansi <= '~') return cursor;
        }
        return value.length();
    }

    static int codePointWidth(int codePoint) {
        if (Character.isISOControl(codePoint)) return 0;
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK) return 0;
        if (codePoint >= 0x1100 && (codePoint <= 0x115F
                || codePoint == 0x2329 || codePoint == 0x232A
                || codePoint >= 0x2E80 && codePoint <= 0xA4CF
                || codePoint >= 0xAC00 && codePoint <= 0xD7A3
                || codePoint >= 0xF900 && codePoint <= 0xFAFF
                || codePoint >= 0xFE10 && codePoint <= 0xFE6F
                || codePoint >= 0xFF00 && codePoint <= 0xFF60
                || codePoint >= 0xFFE0 && codePoint <= 0xFFE6
                || codePoint >= 0x1F300 && codePoint <= 0x1FAFF
                || codePoint >= 0x20000 && codePoint <= 0x3FFFD)) return 2;
        return 1;
    }
}
