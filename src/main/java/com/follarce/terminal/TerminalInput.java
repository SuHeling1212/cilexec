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
import java.util.function.Predicate;

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

    /** Reads one complete, possibly multiline, submission. */
    default String readSubmission(PrintWriter output, String prompt, String continuationPrompt,
                                  boolean remember, Predicate<String> complete)
            throws IOException {
        StringBuilder value = new StringBuilder();
        while (true) {
            String line = readLine(output, value.isEmpty() ? prompt : continuationPrompt,
                    remember);
            if (line == null) return null;
            if (!value.isEmpty()) value.append('\n');
            value.append(line);
            if (complete.test(value.toString())) return value.toString();
        }
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

        @Override
        public String readSubmission(PrintWriter output, String prompt,
                                     String continuationPrompt, boolean remember,
                                     Predicate<String> complete) throws IOException {
            RawMode mode;
            try {
                mode = RawMode.enable();
            } catch (IOException failure) {
                return TerminalInput.super.readSubmission(output, prompt, continuationPrompt,
                        remember, complete);
            }
            try {
                output.print(prompt);
                output.flush();
                return editSubmission(output, prompt, continuationPrompt, remember, complete);
            } finally {
                try {
                    mode.close();
                } catch (IOException ignored) {
                    // Input has already been accepted; restoration failure must not read it twice.
                }
            }
        }

        String edit(PrintWriter output, String prompt, boolean remember) throws IOException {
            return editSubmission(output, prompt, prompt, remember, _ -> true);
        }

        String editSubmission(PrintWriter output, String prompt, String continuationPrompt,
                              boolean remember, Predicate<String> complete) throws IOException {
            StringBuilder value = new StringBuilder();
            int cursor = 0;
            int screenCursorLine = 0;
            int renderedLines = 1;
            int historyIndex = history.size();
            String draft = "";
            while (true) {
                int character = stream.read();
                if (character < 0) return null;
                if (character == '\r' || character == '\n') {
                    String candidate = value.toString();
                    if (complete.test(candidate)) {
                        finish(output, screenCursorLine, lineCount(value));
                        if (remember && !candidate.isBlank()) remember(candidate);
                        return candidate;
                    }
                    value.insert(cursor++, '\n');
                    RenderState state = redraw(output, prompt, continuationPrompt, value, cursor,
                            screenCursorLine, renderedLines);
                    screenCursorLine = state.cursorLine();
                    renderedLines = state.renderedLines();
                    continue;
                }
                if (character == 3) { // Ctrl-C: cancel this editable line.
                    finish(output, screenCursorLine, renderedLines);
                    return "";
                }
                if (character == 127 || character == 8) {
                    if (cursor > 0) {
                        value.deleteCharAt(--cursor);
                        RenderState state = redraw(output, prompt, continuationPrompt, value,
                                cursor, screenCursorLine, renderedLines);
                        screenCursorLine = state.cursorLine();
                        renderedLines = state.renderedLines();
                    }
                    continue;
                }
                if (character == 27) {
                    int bracket = stream.read();
                    int direction = stream.read();
                    if (bracket != '[' || direction < 0) continue;
                    switch (direction) {
                        case 'A' -> { // Up
                            int moved = moveVertical(value, cursor, -1);
                            if (moved != cursor) {
                                cursor = moved;
                                RenderState state = redraw(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                                renderedLines = state.renderedLines();
                            } else if (lineCount(value) == 1 && !history.isEmpty()
                                    && historyIndex > 0) {
                                if (historyIndex == history.size()) draft = value.toString();
                                replace(value, history.get(--historyIndex));
                                cursor = value.length();
                                RenderState state = redraw(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                                renderedLines = state.renderedLines();
                            }
                        }
                        case 'B' -> { // Down
                            int moved = moveVertical(value, cursor, 1);
                            if (moved != cursor) {
                                cursor = moved;
                                RenderState state = redraw(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                                renderedLines = state.renderedLines();
                            } else if (lineCount(value) == 1 && historyIndex < history.size()) {
                                historyIndex++;
                                replace(value, historyIndex == history.size()
                                        ? draft : history.get(historyIndex));
                                cursor = value.length();
                                RenderState state = redraw(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                                renderedLines = state.renderedLines();
                            }
                        }
                        case 'C' -> { // Right
                            if (cursor < value.length()) {
                                cursor++;
                                RenderState state = redraw(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                            }
                        }
                        case 'D' -> { // Left
                            if (cursor > 0) {
                                cursor--;
                                RenderState state = redraw(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                            }
                        }
                        default -> { }
                    }
                    continue;
                }
                if (character >= 32 && character != 127) {
                    value.insert(cursor++, (char) character);
                    RenderState state = redraw(output, prompt, continuationPrompt, value, cursor,
                            screenCursorLine, renderedLines);
                    screenCursorLine = state.cursorLine();
                    renderedLines = state.renderedLines();
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

        private static RenderState redraw(PrintWriter output, String prompt,
                                          String continuationPrompt, StringBuilder value,
                                          int cursor, int previousCursorLine,
                                          int previousRenderedLines) {
            String[] lines = value.toString().split("\\n", -1);
            Position position = position(value, cursor);
            output.print("\r");
            if (previousCursorLine > 0) output.print("\u001b[" + previousCursorLine + "A");
            output.print("\r");
            int paintedLines = Math.max(previousRenderedLines, lines.length);
            for (int line = 0; line < paintedLines; line++) {
                if (line < lines.length) {
                    output.print(line == 0 ? prompt : continuationPrompt);
                    output.print(lines[line]);
                }
                output.print("\u001b[K");
                if (line + 1 < paintedLines) output.print("\r\n");
            }
            int moveUp = paintedLines - 1 - position.line();
            if (moveUp > 0) output.print("\u001b[" + moveUp + "A");
            output.print("\r");
            int promptWidth = position.line() == 0 ? prompt.length()
                    : continuationPrompt.length();
            int moveRight = promptWidth + position.column();
            if (moveRight > 0) output.print("\u001b[" + moveRight + "C");
            output.flush();
            return new RenderState(position.line(), lines.length);
        }

        private static int moveVertical(StringBuilder value, int cursor, int direction) {
            Position current = position(value, cursor);
            int targetLine = current.line() + direction;
            String[] lines = value.toString().split("\\n", -1);
            if (targetLine < 0 || targetLine >= lines.length) return cursor;
            int target = 0;
            for (int line = 0; line < targetLine; line++) {
                target += lines[line].length() + 1;
            }
            return target + Math.min(current.column(), lines[targetLine].length());
        }

        private static Position position(StringBuilder value, int cursor) {
            int line = 0;
            int lineStart = 0;
            for (int index = 0; index < cursor; index++) {
                if (value.charAt(index) == '\n') {
                    line++;
                    lineStart = index + 1;
                }
            }
            return new Position(line, cursor - lineStart);
        }

        private static int lineCount(StringBuilder value) {
            int lines = 1;
            for (int index = 0; index < value.length(); index++) {
                if (value.charAt(index) == '\n') lines++;
            }
            return lines;
        }

        private static void finish(PrintWriter output, int cursorLine, int renderedLines) {
            int moveDown = renderedLines - 1 - cursorLine;
            if (moveDown > 0) output.print("\u001b[" + moveDown + "B");
            output.print("\r");
            output.println();
            output.flush();
        }

        private record Position(int line, int column) { }

        private record RenderState(int cursorLine, int renderedLines) { }
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
