package com.follarce.terminal;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One input source that can disable password echo when attached to a real TTY. */
public interface TerminalInput {
    String readLine() throws IOException;

    /**
     * Reads a visible line. Interactive TTY implementations may provide cursor editing and
     * history; non-TTY input (tests, pipes) deliberately remains a normal line reader.
     */
    default String readLine(PrintWriter output, String prompt, boolean remember) throws IOException {
        output.print(prompt);
        output.flush();
        return readLine();
    }

    char[] readPassword() throws IOException;

    static TerminalInput visible(BufferedReader reader) {
        java.util.Objects.requireNonNull(reader, "reader");
        return new TerminalInput() {
            @Override
            public String readLine() throws IOException {
                return reader.readLine();
            }

            @Override
            public char[] readPassword() throws IOException {
                String value = reader.readLine();
                return value == null ? null : value.toCharArray();
            }
        };
    }

    static TerminalInput system(InputStream stream) {
        java.util.Objects.requireNonNull(stream, "stream");
        Console console = stream == System.in ? System.console() : null;
        if (console != null) {
            return new EditableTerminalInput(stream, console);
        }
        return visible(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
    }

    /** A small dependency-free line editor for a real TTY. */
    final class EditableTerminalInput implements TerminalInput {
        private static final int HISTORY_LIMIT = 200;

        private final InputStream stream;
        private final Console console;
        private final List<String> history = new ArrayList<>();

        EditableTerminalInput(InputStream stream, Console console) {
            this.stream = stream;
            this.console = console;
        }

        @Override
        public String readLine() {
            return console.readLine();
        }

        @Override
        public char[] readPassword() {
            return console.readPassword();
        }

        @Override
        public String readLine(PrintWriter output, String prompt, boolean remember)
                throws IOException {
            output.print(prompt);
            output.flush();
            RawMode mode;
            try {
                mode = RawMode.enable();
            } catch (IOException failure) {
                // A non-standard terminal can reject stty. Falling back still leaves input usable.
                return console.readLine();
            }
            try {
                return edit(output, prompt, remember);
            } finally {
                try {
                    mode.close();
                } catch (IOException ignored) {
                    // The line was already read; never ask for a second line merely because
                    // restoration reported an error.
                }
            }
        }

        String edit(PrintWriter output, String prompt, boolean remember) throws IOException {
            StringBuilder value = new StringBuilder();
            int cursor = 0;
            int historyIndex = history.size();
            String draft = "";
            while (true) {
                int character = stream.read();
                if (character < 0) return null;
                if (character == '\r' || character == '\n') {
                    output.println();
                    output.flush();
                    String result = value.toString();
                    if (remember && !result.isBlank()) remember(result);
                    return result;
                }
                if (character == 3) { // Ctrl-C: cancel this editable line.
                    output.println();
                    output.flush();
                    return "";
                }
                if (character == 127 || character == 8) {
                    if (cursor > 0) {
                        value.deleteCharAt(--cursor);
                        redraw(output, prompt, value, cursor);
                    }
                    continue;
                }
                if (character == 27) {
                    int bracket = stream.read();
                    int direction = stream.read();
                    if (bracket != '[' || direction < 0) continue;
                    switch (direction) {
                        case 'A' -> { // Up
                            if (!history.isEmpty() && historyIndex > 0) {
                                if (historyIndex == history.size()) draft = value.toString();
                                replace(value, history.get(--historyIndex));
                                cursor = value.length();
                                redraw(output, prompt, value, cursor);
                            }
                        }
                        case 'B' -> { // Down
                            if (historyIndex < history.size()) {
                                historyIndex++;
                                replace(value, historyIndex == history.size()
                                        ? draft : history.get(historyIndex));
                                cursor = value.length();
                                redraw(output, prompt, value, cursor);
                            }
                        }
                        case 'C' -> { // Right
                            if (cursor < value.length()) {
                                cursor++;
                                output.print("\u001b[C");
                                output.flush();
                            }
                        }
                        case 'D' -> { // Left
                            if (cursor > 0) {
                                cursor--;
                                output.print("\u001b[D");
                                output.flush();
                            }
                        }
                        default -> { }
                    }
                    continue;
                }
                if (character >= 32 && character != 127) {
                    value.insert(cursor++, (char) character);
                    redraw(output, prompt, value, cursor);
                }
            }
        }

        private void remember(String value) {
            if (!history.isEmpty() && history.getLast().equals(value)) return;
            history.add(value);
            if (history.size() > HISTORY_LIMIT) history.removeFirst();
        }

        private static void replace(StringBuilder target, String replacement) {
            target.setLength(0);
            target.append(replacement);
        }

        private static void redraw(PrintWriter output, String prompt, StringBuilder value,
                                   int cursor) {
            output.print("\r");
            output.print(prompt);
            output.print(value);
            output.print("\u001b[K");
            int moveLeft = value.length() - cursor;
            if (moveLeft > 0) output.print("\u001b[" + moveLeft + "D");
            output.flush();
        }
    }

    /** Temporarily disables canonical input and echo, then restores the exact old TTY state. */
    final class RawMode implements AutoCloseable {
        private final String previous;

        private RawMode(String previous) {
            this.previous = previous;
        }

        static RawMode enable() throws IOException {
            String previous = stty("-g").trim();
            if (previous.isEmpty()) throw new IOException("stty did not return terminal state");
            stty("-icanon", "min", "1", "time", "0", "-echo");
            return new RawMode(previous);
        }

        @Override
        public void close() throws IOException {
            stty(previous);
        }

        private static String stty(String... arguments) throws IOException {
            List<String> command = new ArrayList<>();
            command.add("stty");
            java.util.Collections.addAll(command, arguments);
            Process process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start();
            try {
                String output = new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                String errors = new String(process.getErrorStream().readAllBytes(),
                        StandardCharsets.UTF_8).trim();
                if (process.waitFor() != 0) {
                    throw new IOException(errors.isEmpty() ? "stty failed" : errors);
                }
                return output;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while configuring terminal", interrupted);
            }
        }
    }
}
