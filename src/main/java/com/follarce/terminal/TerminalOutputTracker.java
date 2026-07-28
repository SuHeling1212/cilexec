package com.follarce.terminal;

import java.io.PrintWriter;

/** Tracks whether FCL output left the shared terminal cursor in the middle of a line. */
public final class TerminalOutputTracker {
    private static boolean lineOpen;

    private TerminalOutputTracker() {}

    public static synchronized void printed(String text, boolean newline) {
        if (newline) {
            lineOpen = false;
            return;
        }
        if (text == null || text.isEmpty()) return;
        char last = text.charAt(text.length() - 1);
        lineOpen = last != '\n' && last != '\r';
    }

    public static synchronized void finishLine(PrintWriter output) {
        if (!lineOpen) return;
        output.println();
        output.flush();
        lineOpen = false;
    }
}
