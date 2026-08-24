package com.follarce.domain.process;

import java.util.Set;

/** Stable durable hand-off keys written by asynchronous process services. */
public final class ProcessInbox {
    public static final String TIMER_RESULT = "timer.result";
    public static final String EFFECT_RESULT = "effect.result";
    public static final String TERMINAL_INPUT = "terminal.input";
    public static final String IPC_RESULT = "ipc.result";
    public static final String VOLATILE_RESULT = "volatile.result";

    private static final Set<String> KEYS = Set.of(
            TIMER_RESULT, EFFECT_RESULT, TERMINAL_INPUT, IPC_RESULT, VOLATILE_RESULT);

    private ProcessInbox() {
    }

    public static Set<String> keys() {
        return KEYS;
    }
}
