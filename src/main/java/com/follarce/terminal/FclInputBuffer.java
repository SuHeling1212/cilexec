package com.follarce.terminal;

/** Detects complete multiline FCL input without attempting to compile partial source. */
final class FclInputBuffer {
    private FclInputBuffer() {
    }

    static boolean complete(String source) {
        int braces = 0;
        int brackets = 0;
        int parentheses = 0;
        boolean quoted = false;
        boolean escaped = false;
        boolean comment = false;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (comment) {
                if (value == '\n' || value == '\r') comment = false;
                continue;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if (quoted && value == '\\') {
                escaped = true;
                continue;
            }
            if (value == '"') {
                quoted = !quoted;
                continue;
            }
            if (quoted) continue;
            if (value == '#') {
                comment = true;
                continue;
            }
            if (value == '/' && index + 1 < source.length()
                    && source.charAt(index + 1) == '/') {
                comment = true;
                index++;
                continue;
            }
            switch (value) {
                case '{' -> braces++;
                case '}' -> braces--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                case '(' -> parentheses++;
                case ')' -> parentheses--;
                default -> { }
            }
            if (braces < 0 || brackets < 0 || parentheses < 0) return true;
        }
        return !quoted && !escaped && braces == 0 && brackets == 0 && parentheses == 0;
    }
}
