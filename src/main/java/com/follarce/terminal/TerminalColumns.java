package com.follarce.terminal;

import com.follarce.fcl.DisplayColumns;

/** Unicode-aware terminal column measurement shared by the Shell and FCL TUI packages. */
public final class TerminalColumns {
    private TerminalColumns() { }

    /** Counts visible terminal columns while ignoring ANSI CSI formatting sequences. */
    public static int width(String value) {
        return DisplayColumns.width(value);
    }

    /** Returns the longest prefix whose visible width does not exceed {@code maximumWidth}. */
    public static String truncate(String value, int maximumWidth) {
        return DisplayColumns.truncate(value, maximumWidth);
    }
}
