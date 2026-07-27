package com.follarce.terminal;

/** Application boundary used by host terminal transports. */
@FunctionalInterface
public interface TerminalControl {
    String execute(ShellCommand command);

    /** Evaluates one complete FCL submission in the durable REPL session. */
    default String evaluate(String source) {
        throw new UnsupportedOperationException("FCL evaluation is not available");
    }

    /** Sends raw input to the attached process when it is waiting on io.input(). */
    default String submitAttachedInput(String input) {
        throw new UnsupportedOperationException("Attached process input is not available");
    }

    default boolean awaitingAttachedInput() {
        return false;
    }

    default String prompt() {
        return "cilexec> ";
    }
}
