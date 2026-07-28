package com.follarce.terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

/** Optional host console. It delegates every mutation to the database-backed control service. */
public final class TerminalConsole implements Runnable {
    enum Outcome { LOGOUT, EXIT, END_OF_INPUT }

    private final TerminalInput input;
    private final PrintWriter output;
    private final TerminalControl control;
    private final ShellCommandParser parser;

    public TerminalConsole(BufferedReader input, PrintWriter output, TerminalControl control) {
        this(TerminalInput.visible(input), output, control);
    }

    TerminalConsole(TerminalInput input, PrintWriter output, TerminalControl control) {
        this.input = java.util.Objects.requireNonNull(input, "input");
        this.output = output;
        this.control = control;
        this.parser = new ShellCommandParser();
    }

    @Override
    public void run() {
        runSession();
    }

    Outcome runSession() {
        output.println("CilExec FCL terminal; :help shows terminal commands, other input runs as FCL");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                boolean awaitingAttachedInput = control.awaitingAttachedInput();
                String line = input.readSubmission(output, control.prompt(), "...> ",
                        !awaitingAttachedInput, source -> awaitingAttachedInput
                                || source.stripLeading().startsWith(":")
                                || FclInputBuffer.complete(source));
                if (line == null) {
                    return Outcome.END_OF_INPUT;
                }
                String result;
                ShellCommand command = null;
                if (line.stripLeading().startsWith("::") && awaitingAttachedInput) {
                    result = control.submitAttachedInput(line.stripLeading().substring(1));
                } else if (line.stripLeading().startsWith(":")) {
                    String commandText = line.stripLeading().substring(1);
                    command = parser.parse(commandText);
                    result = control.execute(command);
                } else if (awaitingAttachedInput) {
                    result = control.submitAttachedInput(line);
                } else {
                    if (line.strip().equals("ls")
                            || line.strip().equals("cd") || line.strip().startsWith("cd ")) {
                        throw new IllegalArgumentException(
                                "Terminal command must start with :, for example :ls or :cd /path");
                    }
                    result = line.isBlank() ? "" : control.evaluate(line);
                }
                if (result != null && !result.isEmpty()) {
                    output.println(result);
                }
                if (command instanceof ShellCommand.Logout) {
                    return Outcome.LOGOUT;
                }
                if (command instanceof ShellCommand.Exit) {
                    return Outcome.EXIT;
                }
            } catch (IllegalArgumentException exception) {
                output.println("error: " + exception.getMessage());
            } catch (RuntimeException exception) {
                output.println("error: " + exception.getMessage());
            } catch (IOException exception) {
                output.println("terminal closed: " + exception.getMessage());
                return Outcome.END_OF_INPUT;
            }
        }
        return Outcome.END_OF_INPUT;
    }
}
