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
            // A trailing backslash without a following line break still keeps the
            // submission open: the raw editor tests completion before inserting
            // the newline, and a bare backslash would otherwise be submitted.
            if (value == '\\' && index == source.length() - 1) return false;
            // C-style line continuation: a backslash directly before a line break.
            // A joined continuation inside the buffer counts as already complete;
            // only a trailing backslash at the end of the buffer keeps the
            // submission open even when every delimiter is balanced.
            if (value == '\\' && index + 1 < source.length()
                    && (source.charAt(index + 1) == '\n'
                    || source.charAt(index + 1) == '\r')) {
                int after = source.charAt(index + 1) == '\r' && index + 2 < source.length()
                        && source.charAt(index + 2) == '\n' ? index + 2 : index + 1;
                if (after == source.length() - 1) return false;
                index = after;
                continue;
            }
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

    /**
     * Removes C-style line continuations (a backslash directly before a line break)
     * outside strings and comments, joining the logical lines exactly like the C
     * preprocessor does. The backslash and the following line break disappear.
     */
    static String stripContinuations(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean quoted = false;
        boolean escaped = false;
        boolean comment = false;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (comment) {
                if (value == '\n' || value == '\r') comment = false;
                result.append(value);
                continue;
            }
            if (escaped) {
                escaped = false;
                result.append(value);
                continue;
            }
            if (quoted && value == '\\') {
                escaped = true;
                result.append(value);
                continue;
            }
            if (value == '"') {
                quoted = !quoted;
                result.append(value);
                continue;
            }
            if (quoted) {
                result.append(value);
                continue;
            }
            if (value == '#') {
                comment = true;
                result.append(value);
                continue;
            }
            if (value == '/' && index + 1 < source.length()
                    && source.charAt(index + 1) == '/') {
                comment = true;
                result.append(value).append(source.charAt(index + 1));
                index++;
                continue;
            }
            if (value == '\\' && index + 1 < source.length()
                    && (source.charAt(index + 1) == '\n'
                    || source.charAt(index + 1) == '\r')) {
                if (source.charAt(index + 1) == '\r' && index + 2 < source.length()
                        && source.charAt(index + 2) == '\n') {
                    index++;
                }
                index++;
                continue;
            }
            result.append(value);
        }
        return result.toString();
    }
}
