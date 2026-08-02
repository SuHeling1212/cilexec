package com.follarce.terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

/** Optional host console. It delegates every mutation to the database-backed control service. */
public final class TerminalConsole implements Runnable {
    private static final String RESET_TUI = "\033[?25h\033[2J\033[H";
    enum Outcome { LOGOUT, EXIT, END_OF_INPUT }

    private final TerminalInput input;
    private final PrintWriter output;
    private final TerminalControl control;
    private final ShellCommandParser parser;
    private final PasswordPrompt passwords;

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
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TerminalControl.AttachedInputMode inputMode = control.attachedInputMode();
                    if (inputMode == TerminalControl.AttachedInputMode.KEY) {
                        String key = input.readKey(output);
                        if (key == null) return Outcome.END_OF_INPUT;
                        if (key.equals("CTRL_C")) {
                            input.finishKeyMode();
                            output.print(RESET_TUI);
                            output.flush();
                            continue;
                        }
                        String result = control.submitAttachedInput(key);
                        if (result != null && result.startsWith("error")) {
                            input.finishKeyMode();
                            output.println(result);
                        }
                        continue;
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
                        remember(line);
                        if (command instanceof ShellCommand.Shutdown) {
                            if (!control.canShutdown()) {
                                throw new IllegalArgumentException(
                                        "Administrator permission is required");
                            }
                            PasswordPrompt.Secret password = passwords.read(
                                    "administrator password> ");
                            if (password == null) return Outcome.END_OF_INPUT;
                            try (password) {
                                control.shutdown(password.value());
                            }
                            return Outcome.EXIT;
                        }
                        // :exit is a transport-level disconnect. Never delegate it to a
                        // database-backed control implementation, because disconnecting one
                        // client must not be able to stop the shared Runtime.
                        if (command instanceof ShellCommand.Exit) {
                            return Outcome.EXIT;
                        }
                        result = control.execute(command);
                    } else if (awaitingAttachedInput) {
                        result = control.submitAttachedInput(line);
                    } else {
                        remember(line);
                        if (line.strip().equals("ls")
                                || line.strip().equals("cd") || line.strip().startsWith("cd ")) {
                            throw new IllegalArgumentException(
                                    "Terminal command must start with :, for example :ls or :cd /path");
                        }
                        result = line.isBlank() ? "" : control.evaluate(line);
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
                    output.println("error: " + describe(exception));
                    if (sameFailure(previousFailure, exception)
                            && ++consecutiveControlFailures >= 2) {
                        output.println("terminal stopped after repeated control failures");
                        return Outcome.END_OF_INPUT;
                    }
                    previousFailure = exception;
                    consecutiveControlFailures = 1;
                } catch (IOException exception) {
                    output.println("terminal closed: " + exception.getMessage());
                    return Outcome.END_OF_INPUT;
                }
            }
            return Outcome.END_OF_INPUT;
        } finally {
            try {
                input.finishKeyMode();
            } catch (IOException ignored) {
                // The session is already ending; preserve the original outcome.
            }
        }
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private static boolean sameFailure(RuntimeException previous, RuntimeException current) {
        return previous != null && previous.getClass().equals(current.getClass())
                && java.util.Objects.equals(previous.getMessage(), current.getMessage());
    }

    private void remember(String line) {
        input.rememberHistory(line);
        control.rememberCommand(line);
    }
}
