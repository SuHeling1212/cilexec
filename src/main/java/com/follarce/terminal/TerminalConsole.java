package com.follarce.terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional host console. It delegates every mutation to the database-backed control service. */
public final class TerminalConsole implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(TerminalConsole.class);
    /**
     * Leaves any alternate screen, disables mouse/paste/focus reporting, and restores the
     * cursor so a crashed full-screen FCL program can never leave the host terminal in a
     * broken mode (lost selection, flooded raw mouse bytes, hidden primary screen).
     */
    private static final String EXIT_FULL_SCREEN =
            "\033[?1049l\033[?1002l\033[?1006l\033[?2004l\033[?1004l\033[?25h\033[2J\033[H";
    enum Outcome { LOGOUT, EXIT, END_OF_INPUT }

    private final TerminalInput input;
    private final PrintWriter output;
    private final TerminalControl control;
    private final ShellCommandParser parser;
    private final PasswordPrompt passwords;
    /** Consecutive identical control-surface failures that end the session (anti-spam). */
    private static final int REPEATED_FAILURE_LIMIT = 5;

    public TerminalConsole(BufferedReader input, PrintWriter output, TerminalControl control) {
        this(TerminalInput.visible(input), output, control);
    }

    TerminalConsole(TerminalInput input, PrintWriter output, TerminalControl control) {
        this.input = java.util.Objects.requireNonNull(input, "input");
        this.output = output;
        this.control = control;
        this.parser = new ShellCommandParser();
        this.passwords = new PasswordPrompt(this.input, this.output);
    }

    @Override
    public void run() {
        runSession();
    }

    Outcome runSession() {
        input.replaceHistory(control.commandHistory());
        output.println("CilExec FCL terminal; :help shows terminal commands, other input runs as FCL");
        RuntimeException previousFailure = null;
        int consecutiveControlFailures = 0;
        boolean fullScreenInputActive = false;
        try {
            // EOF (readLine/readKey returning null) is the authoritative disconnect signal;
            // the pump thread's wake-up interrupt targets only the database polling loop.
            while (true) {
                try {
                    TerminalControl.AttachedInputMode inputMode = control.attachedInputMode();
                    if (inputMode == TerminalControl.AttachedInputMode.KEY
                            || inputMode == TerminalControl.AttachedInputMode.KEY_BATCH) {
                        fullScreenInputActive = true;
                        String event = input.readKeyEvent(output,
                                inputMode == TerminalControl.AttachedInputMode.KEY_BATCH);
                        if (event == null) return Outcome.END_OF_INPUT;
                        if (event.contains("\"key\":\"CTRL_C\"")) {
                            leaveFullScreen();
                            fullScreenInputActive = false;
                            control.interruptForeground();
                            continue;
                        }
                        String result;
                        try {
                            result = control.submitAttachedInput(event);
                        } catch (RuntimeException failure) {
                            // A failed key delivery used to fall through to the generic error
                            // handler, leaving the editor's alternate screen and input modes live.
                            leaveFullScreen();
                            control.interruptForeground();
                            throw failure;
                        }
                        if (result != null && result.startsWith("error")) {
                            leaveFullScreen();
                            fullScreenInputActive = false;
                            output.println(result);
                        }
                        continue;
                    }
                    // An attached full-screen process can also be stopped from another
                    // terminal, fail while handling a large paste, or be killed by recovery.
                    // Its FCL finally block is then unreachable.  The owning terminal is the
                    // last reliable place to restore its primary screen and disable reporting.
                    if (fullScreenInputActive) {
                        leaveFullScreen();
                        fullScreenInputActive = false;
                    }
                    if (inputMode == TerminalControl.AttachedInputMode.NONE) {
                        TerminalOutputTracker.finishLine(output);
                    }
                    input.finishKeyMode();
                    boolean awaitingAttachedInput = control.awaitingAttachedInput();
                    String line = input.readSubmission(output, control.prompt(), "...> ",
                            !awaitingAttachedInput, source -> awaitingAttachedInput
                                    || source.stripLeading().startsWith(":")
                                    || FclInputBuffer.complete(source));
                    if (line == null) {
                        return Outcome.END_OF_INPUT;
                    }
                    String stripped = line.stripLeading();
                    String result;
                    ShellCommand command = null;
                    if (line.stripLeading().startsWith("::") && awaitingAttachedInput) {
                        result = control.submitAttachedInput(line.stripLeading().substring(1));
                    } else if (line.stripLeading().startsWith(":")) {
                        String commandText = line.stripLeading().substring(1);
                        command = parser.parse(commandText);
                        if (command instanceof ShellCommand.Shutdown) {
                            remember(line);
                            if (!control.canShutdown()) {
                                throw new IllegalArgumentException(
                                        "Administrator permission is required");
                            }
                            PasswordPrompt.Secret password = passwords.read(
                                    control.username() + " password> ");
                            if (password == null) return Outcome.END_OF_INPUT;
                            try (password) {
                                control.shutdown(password.value());
                            }
                            return Outcome.EXIT;
                        }
                        // :exit is a transport-level disconnect. Never delegate it to a
                        // database-backed control implementation, because disconnecting one
                        // client must not be able to stop the shared Runtime. It is also not
                        // a command worth restoring through arrow-key history.
                        if (command instanceof ShellCommand.Exit) {
                            return Outcome.EXIT;
                        }
                        remember(line);
                        result = control.execute(command);
                    } else if (awaitingAttachedInput) {
                        result = control.submitAttachedInput(line);
                    } else {
                        remember(line);
                        result = line.isBlank() ? ""
                                : control.evaluate(FclInputBuffer.stripContinuations(line));
                    }
                    if (result != null && !result.isEmpty()) {
                        if (command instanceof ShellCommand.Clear) {
                            output.print(result);
                            output.flush();
                        } else {
                            output.println(result);
                        }
                    }
                    if (command instanceof ShellCommand.Logout) {
                        return Outcome.LOGOUT;
                    }
                    previousFailure = null;
                    consecutiveControlFailures = 0;
                } catch (IllegalArgumentException exception) {
                    output.println("error: " + exception.getMessage());
                } catch (RuntimeException exception) {
                    // FCL compile/runtime errors never disconnect the terminal: retrying
                    // the same input after an error is the most common workflow. A broken
                    // control surface that keeps throwing (not a language error) is
                    // bounded instead, so the session cannot spam errors forever.
                    LOG.warn("Terminal control operation failed", exception);
                    output.println("error: " + describe(exception));
                    if (!(exception instanceof com.follarce.fcl.FclCompileException)
                            && !(exception instanceof com.follarce.fcl.FclRuntimeException)) {
                        consecutiveControlFailures = sameFailure(previousFailure, exception)
                                ? consecutiveControlFailures + 1 : 1;
                        if (consecutiveControlFailures >= REPEATED_FAILURE_LIMIT) {
                            output.println("terminal stopped after repeated control failures");
                            return Outcome.END_OF_INPUT;
                        }
                    }
                    previousFailure = exception;
                } catch (IOException exception) {
                    if (exception instanceof TerminalInput.SubmissionLimitExceeded) {
                        output.println("error: " + exception.getMessage());
                        continue;
                    }
                    LOG.warn("Terminal input failed; closing session", exception);
                    output.println("terminal closed");
                    return Outcome.END_OF_INPUT;
                }
            }
        } finally {
            try {
                if (fullScreenInputActive) leaveFullScreen();
                else input.finishKeyMode();
            } catch (IOException ignored) {
                // The session is already ending; preserve the original outcome.
            }
        }
    }

    private static String describe(RuntimeException exception) {
        // Language-level errors carry an actionable message for the FCL author; internal
        // failures stay opaque so implementation details never leak to the terminal.
        if (exception instanceof com.follarce.fcl.FclRuntimeException
                || exception instanceof com.follarce.fcl.FclCompileException) {
            String message = exception.getMessage();
            return message == null || message.isBlank()
                    ? exception.getClass().getSimpleName() : message;
        }
        return "Command failed: " + exception.getClass().getSimpleName();
    }

    private static boolean sameFailure(RuntimeException previous, RuntimeException current) {
        return previous != null && previous.getClass().equals(current.getClass())
                && java.util.Objects.equals(previous.getMessage(), current.getMessage());
    }

    private void leaveFullScreen() throws IOException {
        input.finishKeyMode();
        output.print(EXIT_FULL_SCREEN);
        output.flush();
    }

    private void remember(String line) {
        input.rememberHistory(line);
        control.rememberCommand(line);
    }
}
