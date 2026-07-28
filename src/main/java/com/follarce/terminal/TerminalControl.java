package com.follarce.terminal;

import java.util.List;

/** Application boundary used by host terminal transports. */
@FunctionalInterface
public interface TerminalControl {
    enum AttachedInputMode { NONE, LINE, KEY }

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

    /** Describes how the attached FCL process expects its next terminal input. */
    default AttachedInputMode attachedInputMode() {
        return awaitingAttachedInput() ? AttachedInputMode.LINE : AttachedInputMode.NONE;
    }

    default String prompt() {
        return "cilexec> ";
    }

    /** Persistent command history for the authenticated user, oldest first. */
    default List<String> commandHistory() {
        return List.of();
    }

    default void rememberCommand(String command) {
    }
}
