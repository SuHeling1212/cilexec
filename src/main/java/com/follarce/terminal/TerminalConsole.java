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
        StringBuilder submission = new StringBuilder();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String line = input.readLine(output,
                        submission.isEmpty() ? control.prompt() : "...> ",
                        !control.awaitingAttachedInput());
                if (line == null) {
                    return Outcome.END_OF_INPUT;
                }
                String result;
                ShellCommand command = null;
                if (submission.isEmpty() && line.stripLeading().startsWith("::")
                        && control.awaitingAttachedInput()) {
                    result = control.submitAttachedInput(line.stripLeading().substring(1));
                } else if (submission.isEmpty() && line.stripLeading().startsWith(":")) {
                    String commandText = line.stripLeading().substring(1);
                    command = parser.parse(commandText);
                    result = control.execute(command);
                } else if (submission.isEmpty() && control.awaitingAttachedInput()) {
                    result = control.submitAttachedInput(line);
                } else {
                    if (submission.isEmpty() && (line.strip().equals("ls")
                            || line.strip().equals("cd") || line.strip().startsWith("cd "))) {
                        throw new IllegalArgumentException(
                                "Terminal command must start with :, for example :ls or :cd /path");
                    }
                    if (!submission.isEmpty()) submission.append('\n');
                    submission.append(line);
                    if (!FclInputBuffer.complete(submission.toString())) {
                        continue;
                    }
                    String source = submission.toString();
                    submission.setLength(0);
                    result = source.isBlank() ? "" : control.evaluate(source);
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
                submission.setLength(0);
                output.println("error: " + exception.getMessage());
            } catch (RuntimeException exception) {
                submission.setLength(0);
                output.println("error: " + exception.getMessage());
            } catch (IOException exception) {
                output.println("terminal closed: " + exception.getMessage());
                return Outcome.END_OF_INPUT;
            }
        }
        return Outcome.END_OF_INPUT;
    }
}
