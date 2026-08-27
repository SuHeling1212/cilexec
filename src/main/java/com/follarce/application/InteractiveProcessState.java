package com.follarce.application;

import com.follarce.fcl.FclContinuation;

/**
 * Persisted interactive-process state names.
 *
 * <p>These literal values are continuation-format compatibility data. Their Java ownership may
 * move away from terminal adapters, but the values themselves must not change without a tested
 * continuation migration.
 */
public final class InteractiveProcessState {
    public static final String LIBRARY_SCOPE_KEY = "cilexec.repl.library";
    public static final String PROCESS_SCOPE_KEY = "cilexec.repl.terminalProcess";
    public static final String SESSION_SCOPE_KEY = "cilexec.repl.terminalSession";
    public static final String OUTPUT_ROUTE_SCOPE_KEY = "cilexec.terminal.outputRoute";

    private InteractiveProcessState() { }

    /** Identifies an interactive continuation without coupling process semantics to a host. */
    public static boolean isInteractive(FclContinuation continuation) {
        FclContinuation runtime = java.util.Objects.requireNonNull(continuation, "continuation");
        if (runtime.scope().contains(PROCESS_SCOPE_KEY)) return true;
        return runtime.callStack().stream()
                .anyMatch(frame -> frame.callerScope().contains(PROCESS_SCOPE_KEY));
    }
}
