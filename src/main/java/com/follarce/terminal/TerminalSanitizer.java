package com.follarce.terminal;

/** Converts untrusted text to terminal-visible text without active control sequences. */
public final class TerminalSanitizer {
    private TerminalSanitizer() { }

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (codePoint == '\t') {
                safe.append("    ");
            } else if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || codePoint == 0x202A || codePoint == 0x202B || codePoint == 0x202D
                    || codePoint == 0x202E || codePoint == 0x202C
                    || codePoint == 0x2066 || codePoint == 0x2067
                    || codePoint == 0x2068 || codePoint == 0x2069) {
                safe.append('?');
            } else {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }
}
