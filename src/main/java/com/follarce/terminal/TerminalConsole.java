package com.follarce.terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

/** Optional host console. It delegates every mutation to the database-backed control service. */
public final class TerminalConsole implements Runnable {
    private final BufferedReader input;
    private final PrintWriter output;
    private final TerminalControl control;
    private final ShellCommandParser parser;

    public TerminalConsole(BufferedReader input, PrintWriter output, TerminalControl control) {
        this.input = input;
        this.output = output;
        this.control = control;
        this.parser = new ShellCommandParser();
    }

    @Override
    public void run() {
        output.println("CilExec terminal; type help for commands");
        while (!Thread.currentThread().isInterrupted()) {
            output.print("cilexec> ");
            output.flush();
            try {
                String line = input.readLine();
                if (line == null) {
                    return;
                }
                ShellCommand command = parser.parse(line);
                String result = control.execute(command);
                if (result != null && !result.isEmpty()) {
                    output.println(result);
                }
                if (command instanceof ShellCommand.Exit) {
                    return;
                }
            } catch (IllegalArgumentException exception) {
                output.println("error: " + exception.getMessage());
            } catch (IOException exception) {
                output.println("terminal closed: " + exception.getMessage());
                return;
            }
        }
    }
}
