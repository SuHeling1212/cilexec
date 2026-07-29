package com.follarce.terminal;

import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks unfinished FCL output independently for every connected terminal writer. */
public final class TerminalOutputTracker {
    private static final Set<PrintWriter> OPEN_LINES = ConcurrentHashMap.newKeySet();

    private TerminalOutputTracker() {}

    public static void printed(PrintWriter output, String text, boolean newline) {
        java.util.Objects.requireNonNull(output, "output");
        if (newline) {
            OPEN_LINES.remove(output);
            return;
        }
        if (text == null || text.isEmpty()) return;
        char last = text.charAt(text.length() - 1);
        if (last == '\n' || last == '\r') OPEN_LINES.remove(output);
        else OPEN_LINES.add(output);
    }

    public static void finishLine(PrintWriter output) {
        java.util.Objects.requireNonNull(output, "output");
        if (!OPEN_LINES.remove(output)) return;
        output.println();
        output.flush();
    }

    public static void discard(PrintWriter output) {
        if (output != null) OPEN_LINES.remove(output);
    }
}
