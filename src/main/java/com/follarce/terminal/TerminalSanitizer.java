package com.follarce.terminal;

import com.follarce.application.ConsoleTextSanitizer;

/** Converts untrusted text to terminal-visible text without active control sequences. */
public final class TerminalSanitizer {
    private TerminalSanitizer() { }

    public static String sanitize(String value) {
        return ConsoleTextSanitizer.sanitize(value);
    }
}
