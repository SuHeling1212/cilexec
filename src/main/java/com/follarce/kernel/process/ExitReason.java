package com.follarce.kernel.process;

/** Final outcome of a process. */
public enum ExitReason {
    NONE,
    NORMAL,
    KILLED,
    ERROR,
    SHUTDOWN
}
