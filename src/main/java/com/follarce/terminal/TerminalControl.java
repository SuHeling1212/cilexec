package com.follarce.terminal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Application boundary used by host terminal transports. */
@FunctionalInterface
public interface TerminalControl {
    enum AttachedInputMode { NONE, LINE, KEY, KEY_BATCH }

    String execute(ShellCommand command);

    /** Evaluates one complete FCL submission in the durable REPL session. */
    default String evaluate(String source) {
        throw new UnsupportedOperationException("FCL evaluation is not available");
    }

    /** Sends raw input to the attached process while it is waiting for terminal input. */
    default String submitAttachedInput(String input) {
        throw new UnsupportedOperationException("Attached process input is not available");
    }

    default boolean awaitingAttachedInput() {
        return false;
    }

    /** Describes how the attached FCL process expects its next terminal input. */
    default AttachedInputMode attachedInputMode() {
        return AttachedInputMode.NONE;
    }

    /**
     * Nanoseconds until the attached process has been suspended (PAUSED) for the idle
     * threshold, 0 when it is already past the threshold, or {@link Long#MAX_VALUE} when
     * the session is active and must never be closed for idleness.
     */
    default long idleRemainingNanos(long thresholdNanos) {
        return Long.MAX_VALUE;
    }

    default String prompt() {
        return "cilexec> ";
    }

    /** Username of the authenticated terminal session. */
    default String username() {
        return "administrator";
    }

    /** Command history for the authenticated user, oldest first. */
    default List<String> commandHistory() {
        return List.of();
    }

    default void rememberCommand(String command) {
    }

    default boolean canShutdown() {
        return false;
    }

    /** Stops the shared Runtime after administrator capability and credential verification. */
    default void shutdown(char[] password) {
        throw new UnsupportedOperationException("Runtime shutdown is not available");
    }

    /** Cancels the currently executing FCL submission without discarding REPL context. */
    default boolean interruptForeground() {
        return false;
    }

    /** Unique route for output belonging to this authenticated terminal session. */
    default Optional<UUID> outputRouteId() {
        return Optional.empty();
    }

    /** Terminal mode sequence to replay for the process attached to this terminal session. */
    default String terminalRestoreSequence() {
        return "";
    }
}
