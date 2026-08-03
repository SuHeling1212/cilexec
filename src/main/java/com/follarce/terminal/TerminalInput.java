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
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/** One input source that can disable password echo when attached to a real TTY. */
public interface TerminalInput {
    int MAX_SUBMISSION_CHARACTERS = 256 * 1024;

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
            if (value.length() > MAX_SUBMISSION_CHARACTERS) {
                throw new IOException("Terminal submission exceeds 256 Ki characters");
            }
            if (complete.test(value.toString())) return value.toString();
        }
    }

    char[] readPassword() throws IOException;

    /** Raw remote clients need the server to render the newline after hidden input. */
    default boolean passwordNeedsLineBreak() {
        return false;
    }

    /** Reads one normalized key token for an attached full-screen FCL application. */
    default String readKey(PrintWriter output) throws IOException {
        String line = readLine();
        if (line == null) return null;
        return line.isEmpty() ? "ENTER" : String.valueOf(line.charAt(0));
    }

    /** Leaves persistent raw-key mode before returning to normal line input. */
    default void finishKeyMode() throws IOException {
    }

    /** Replaces the editable history without rendering it. */
    default void replaceHistory(List<String> commands) {
    }

    /** Makes a completed command immediately available to arrow-key navigation. */
    default void rememberHistory(String command) {
    }

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

            @Override
            public String readKey(PrintWriter output) throws IOException {
                return decodeKey(reader.read(), reader::read);
            }
        };
    }

    static TerminalInput system(InputStream stream) {
        java.util.Objects.requireNonNull(stream, "stream");
        Console console = stream == System.in ? System.console() : null;
        if (console != null) {
            TerminalDimensions.refresh();
            return new EditableTerminalInput(stream, console,
                    () -> TerminalDimensions.refresh().width());
        }
        return visible(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
    }

    /** Input transported from a raw-mode terminal client to the shared Runtime JVM. */
    static TerminalInput remoteRaw(InputStream stream) {
        return new EditableTerminalInput(
                java.util.Objects.requireNonNull(stream, "stream"), null);
    }

    static TerminalInput remoteRaw(InputStream stream, IntSupplier terminalWidth) {
        return new EditableTerminalInput(
                java.util.Objects.requireNonNull(stream, "stream"), null,
                java.util.Objects.requireNonNull(terminalWidth, "terminalWidth"));
    }

    /** A small dependency-free line editor for a real TTY. */
    final class EditableTerminalInput implements TerminalInput {
        private static final int HISTORY_LIMIT = TerminalService.COMMAND_HISTORY_LIMIT;

        private final InputStream stream;
        private final Console console;
        private final IntSupplier terminalWidth;
        private final List<String> history = new ArrayList<>();
        private RawMode keyMode;

        EditableTerminalInput(InputStream stream, Console console) {
            this(stream, console, () -> TerminalDimensions.current().width());
        }

        EditableTerminalInput(InputStream stream, Console console, IntSupplier terminalWidth) {
            this.stream = stream;
            this.console = console;
            this.terminalWidth = java.util.Objects.requireNonNull(terminalWidth,
                    "terminalWidth");
        }

        @Override
        public String readLine() throws IOException {
            if (console == null) return readRawLine();
            String line = console.readLine();
            TerminalDimensions.refresh();
            return line;
        }

        @Override
        public char[] readPassword() throws IOException {
            if (console == null) {
                String value = readRawLine();
                return value == null ? null : value.toCharArray();
            }
            return console.readPassword();
        }

        @Override
        public boolean passwordNeedsLineBreak() {
            return console == null;
        }

        @Override
        public String readLine(PrintWriter output, String prompt, boolean remember)
                throws IOException {
            finishKeyMode();
            output.print(prompt);
            output.flush();
            if (console == null) return edit(output, prompt, remember);
            RawMode mode;
            try {
                mode = RawMode.enable();
            } catch (IOException failure) {
                // A non-standard terminal can reject stty. Falling back still leaves input usable.
                return console.readLine();
            }
            try {
                String line = edit(output, prompt, remember);
                TerminalDimensions.refresh();
                return line;
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
            finishKeyMode();
            if (console == null) {
                output.print(prompt);
                output.flush();
                return editSubmission(output, prompt, continuationPrompt, remember, complete);
            }
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
                String submission = editSubmission(output, prompt, continuationPrompt,
                        remember, complete);
                TerminalDimensions.refresh();
                return submission;
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

        @Override
        public String readKey(PrintWriter output) throws IOException {
            if (console != null && keyMode == null) keyMode = RawMode.enable();
            String key = decodeByteKey(stream.read());
            if (console != null) TerminalDimensions.refresh();
            return key;
        }

        @Override
        public void finishKeyMode() throws IOException {
            if (console == null) return;
            if (keyMode == null) return;
            RawMode current = keyMode;
            keyMode = null;
            current.close();
        }

        @Override
        public void replaceHistory(List<String> commands) {
            history.clear();
            if (commands == null || commands.isEmpty()) return;
            int start = Math.max(0, commands.size() - HISTORY_LIMIT);
            for (int index = start; index < commands.size(); index++) {
                remember(commands.get(index));
            }
        }

        @Override
        public void rememberHistory(String command) {
            if (command != null && !command.isBlank()) remember(command);
        }

        private String decodeByteKey(int first) throws IOException {
            if (first < 0 || first < 128) return decodeKey(first, stream::read);
            int length = first >= 0xF0 ? 4 : first >= 0xE0 ? 3 : 2;
            byte[] bytes = new byte[length];
            bytes[0] = (byte) first;
            for (int index = 1; index < length; index++) {
                int next = stream.read();
                if (next < 0) return null;
                bytes[index] = (byte) next;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private String readRawLine() throws IOException {
            StringBuilder value = new StringBuilder();
            while (true) {
                int character = stream.read();
                if (character < 0) return value.isEmpty() ? null : value.toString();
                if (character == '\r' || character == '\n') return value.toString();
                if (character == 127 || character == 8) {
                    if (!value.isEmpty()) {
                        int previous = value.offsetByCodePoints(value.length(), -1);
                        value.delete(previous, value.length());
                    }
                    continue;
                }
                if (character >= 32) value.append(decodeUtf8(character));
                if (value.length() > MAX_SUBMISSION_CHARACTERS) {
                    throw new IOException("Terminal line exceeds 256 Ki characters");
                }
            }
        }

        String editSubmission(PrintWriter output, String prompt, String continuationPrompt,
                              boolean remember, Predicate<String> complete) throws IOException {
            StringBuilder value = new StringBuilder();
            int cursor = 0;
            RenderState initial = layout(prompt, continuationPrompt, value, cursor,
                    width());
            int screenCursorLine = initial.cursorLine();
            int renderedLines = initial.renderedLines();
            int historyIndex = history.size();
            String draft = "";
            while (true) {
                int character = stream.read();
                if (character < 0) return null;
                if (character == '\r' || character == '\n') {
                    String candidate = value.toString();
                    if (complete.test(candidate)) {
                        finish(output, screenCursorLine, renderedLines);
                        if (remember && !candidate.isBlank()) remember(candidate);
                        return candidate;
                    }
                    value.insert(cursor++, '\n');
                    requireSubmissionLimit(value);
                    RenderState state = render(output, prompt, continuationPrompt, value, cursor,
                            screenCursorLine, renderedLines);
                    screenCursorLine = state.cursorLine();
                    renderedLines = state.renderedLines();
                    continue;
                }
                if (character == 3) { // Ctrl-C: cancel this editable line.
                    finish(output, screenCursorLine, renderedLines);
                    return "";
                }
                if (character == '\t') {
                    // Command completion is intentionally unavailable. Tabs are ignored rather
                    // than inserted so an accidental key press cannot change submitted FCL.
                    continue;
                }
                if (character == 127 || character == 8) {
                    if (cursor > 0) {
                        int previous = value.offsetByCodePoints(cursor, -1);
                        value.delete(previous, cursor);
                        cursor = previous;
                        RenderState state = render(output, prompt, continuationPrompt, value,
                                cursor, screenCursorLine, renderedLines);
                        screenCursorLine = state.cursorLine();
                        renderedLines = state.renderedLines();
                    }
                    continue;
                }
                if (character == 27) {
                    int bracket = stream.read();
                    int direction = stream.read();
                    if ((bracket != '[' && bracket != 'O') || direction < 0) continue;
                    switch (direction) {
                        case 'A' -> { // Up
                            int moved = moveVertical(value, cursor, -1);
                            if (moved != cursor) {
                                cursor = moved;
                                RenderState state = render(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                                renderedLines = state.renderedLines();
                            } else if (remember && position(value, cursor).line() == 0
                                    && !history.isEmpty() && historyIndex > 0) {
                                if (historyIndex == history.size()) draft = value.toString();
                                replace(value, history.get(--historyIndex));
                                requireSubmissionLimit(value);
                                cursor = value.length();
                                RenderState state = render(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                                renderedLines = state.renderedLines();
                            }
                        }
                        case 'B' -> { // Down
                            int moved = moveVertical(value, cursor, 1);
                            if (moved != cursor) {
                                cursor = moved;
                                RenderState state = render(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                                renderedLines = state.renderedLines();
                            } else if (remember && position(value, cursor).line()
                                    == lineCount(value) - 1
                                    && historyIndex < history.size()) {
                                historyIndex++;
                                replace(value, historyIndex == history.size()
                                        ? draft : history.get(historyIndex));
                                requireSubmissionLimit(value);
                                cursor = value.length();
                                RenderState state = render(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                                renderedLines = state.renderedLines();
                            }
                        }
                        case 'C' -> { // Right
                            if (cursor < value.length()) {
                                cursor = value.offsetByCodePoints(cursor, 1);
                                RenderState state = render(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                            }
                        }
                        case 'D' -> { // Left
                            if (cursor > 0) {
                                cursor = value.offsetByCodePoints(cursor, -1);
                                RenderState state = render(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                            }
                        }
                        case 'H' -> { // Home
                            int target = cursor;
                            while (target > 0 && value.charAt(target - 1) != '\n') target--;
                            if (target != cursor) {
                                cursor = target;
                                RenderState state = render(output, prompt, continuationPrompt,
                                        value, cursor, screenCursorLine, renderedLines);
                                screenCursorLine = state.cursorLine();
                            }
                        }
                        case 'F' -> { // End
                            while (cursor < value.length() && value.charAt(cursor) != '\n') {
                                cursor++;
                            }
                            RenderState state = render(output, prompt, continuationPrompt,
                                    value, cursor, screenCursorLine, renderedLines);
                            screenCursorLine = state.cursorLine();
                            renderedLines = state.renderedLines();
                        }
                        default -> { }
                    }
                    continue;
                }
                if (character >= 32 && character != 127) {
                    String typed = decodeUtf8(character);
                    boolean appending = cursor == value.length();
                    value.insert(cursor, typed);
                    requireSubmissionLimit(value);
                    cursor += typed.length();
                    RenderState state;
                    if (appending) {
                        output.print(typed);
                        output.flush();
                        state = layout(prompt, continuationPrompt, value, cursor, width());
                    } else {
                        state = render(output, prompt, continuationPrompt, value, cursor,
                                screenCursorLine, renderedLines);
                    }
                    screenCursorLine = state.cursorLine();
                    renderedLines = state.renderedLines();
                }
            }
        }

        private RenderState render(PrintWriter output, String prompt, String continuationPrompt,
                                   StringBuilder value, int cursor, int previousCursorLine,
                                   int previousRenderedLines) {
            return redraw(output, prompt, continuationPrompt, value, cursor,
                    previousCursorLine, previousRenderedLines, width());
        }

        private int width() {
            try {
                return Math.max(1, terminalWidth.getAsInt());
            } catch (RuntimeException ignored) {
                return TerminalDimensions.current().width();
            }
        }

        private String decodeUtf8(int first) throws IOException {
            if (first < 0x80) return String.valueOf((char) first);
            int length;
            if ((first & 0xE0) == 0xC0) length = 2;
            else if ((first & 0xF0) == 0xE0) length = 3;
            else if ((first & 0xF8) == 0xF0) length = 4;
            else return "\uFFFD";
            byte[] encoded = new byte[length];
            encoded[0] = (byte) first;
            for (int index = 1; index < length; index++) {
                int next = stream.read();
                if (next < 0) return "\uFFFD";
                if ((next & 0xC0) != 0x80) return "\uFFFD";
                encoded[index] = (byte) next;
            }
            return new String(encoded, StandardCharsets.UTF_8);
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

        private static void requireSubmissionLimit(StringBuilder value) throws IOException {
            if (value.length() > MAX_SUBMISSION_CHARACTERS) {
                throw new IOException("Terminal submission exceeds 256 Ki characters");
            }
        }

        private static RenderState redraw(PrintWriter output, String prompt,
                                          String continuationPrompt, StringBuilder value,
                                          int cursor, int previousCursorLine,
                                          int previousRenderedLines, int terminalWidth) {
            String[] lines = value.toString().split("\\n", -1);
            RenderState next = layout(prompt, continuationPrompt, value, cursor, terminalWidth);
            StringBuilder frame = new StringBuilder();
            frame.append('\r');
            if (previousCursorLine > 0) {
                frame.append("\u001b[").append(previousCursorLine).append('A');
            }
            frame.append('\r');

            // Only the old rendering exists on screen. Clearing rows from the new, larger
            // layout would try to move below the current terminal bottom before those rows have
            // been painted, which can clamp the cursor and corrupt the subsequent move back up.
            int linesToClear = previousRenderedLines;
            for (int line = 0; line < linesToClear; line++) {
                frame.append("\u001b[2K");
                if (line + 1 < linesToClear) frame.append("\u001b[1B\r");
            }
            if (linesToClear > 1) {
                frame.append("\u001b[").append(linesToClear - 1).append("A\r");
            }

            for (int line = 0; line < lines.length; line++) {
                frame.append(line == 0 ? prompt : continuationPrompt).append(lines[line]);
                if (line + 1 < lines.length) frame.append("\r\n");
            }
            int moveUp = next.renderedLines() - 1 - next.cursorLine();
            if (moveUp > 0) frame.append("\u001b[").append(moveUp).append('A');
            frame.append('\r');
            if (next.cursorColumn() > 0) {
                frame.append("\u001b[").append(next.cursorColumn()).append('C');
            }
            output.print(frame);
            output.flush();
            return next;
        }

        private static RenderState layout(String prompt, String continuationPrompt,
                                          StringBuilder value, int cursor, int terminalWidth) {
            int width = Math.max(1, terminalWidth);
            String[] lines = value.toString().split("\\n", -1);
            Position position = position(value, cursor);
            int renderedLines = 0;
            int cursorLine = 0;
            int cursorColumn = 0;
            int offset = 0;
            for (int line = 0; line < lines.length; line++) {
                String linePrompt = line == 0 ? prompt : continuationPrompt;
                int promptWidth = visibleWidth(linePrompt);
                int columns = promptWidth + visibleWidth(lines[line]);
                int physicalLines = physicalLines(columns, width);
                if (line == position.line()) {
                    int prefixLength = Math.max(0, Math.min(lines[line].length(),
                            cursor - offset));
                    int cursorColumns = promptWidth
                            + visibleWidth(lines[line].substring(0, prefixLength));
                    cursorLine = renderedLines + physicalLine(cursorColumns, width);
                    cursorColumn = physicalColumn(cursorColumns, width);
                }
                renderedLines += physicalLines;
                offset += lines[line].length() + 1;
            }
            return new RenderState(cursorLine, renderedLines, cursorColumn);
        }

        private static int physicalLines(int columns, int width) {
            return columns == 0 ? 1 : (columns - 1) / width + 1;
        }

        private static int physicalLine(int columns, int width) {
            return columns == 0 ? 0 : (columns - 1) / width;
        }

        private static int physicalColumn(int columns, int width) {
            if (columns == 0) return 0;
            int remainder = columns % width;
            return remainder == 0 ? width - 1 : remainder;
        }

        /** Returns terminal columns while ignoring ANSI CSI formatting sequences. */
        private static int visibleWidth(String value) {
            return TerminalColumns.width(value);
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
            int targetColumns = Math.min(current.column(),
                    lines[targetLine].codePointCount(0, lines[targetLine].length()));
            return target + lines[targetLine].offsetByCodePoints(0, targetColumns);
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
            return new Position(line, value.codePointCount(lineStart, cursor));
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

        private record RenderState(int cursorLine, int renderedLines, int cursorColumn) { }
    }

    @FunctionalInterface
    interface KeyReader {
        int read() throws IOException;
    }

    private static String decodeKey(int first, KeyReader input) throws IOException {
        if (first < 0) return null;
        if (first == '\r' || first == '\n') return "ENTER";
        if (first == '\t') return "TAB";
        if (first == 127 || first == 8) return "BACKSPACE";
        if (first > 0 && first < 27) {
            return "CTRL_" + (char) ('A' + first - 1);
        }
        if (first != 27) return String.valueOf((char) first);

        int prefix = input.read();
        if (prefix != '[' && prefix != 'O') return "ESCAPE";
        int code = input.read();
        return switch (code) {
            case 'A' -> "UP";
            case 'B' -> "DOWN";
            case 'C' -> "RIGHT";
            case 'D' -> "LEFT";
            case 'H' -> "HOME";
            case 'F' -> "END";
            case '3', '5', '6' -> {
                int suffix = input.read();
                if (suffix != '~') yield "ESCAPE";
                yield switch (code) {
                    case '3' -> "DELETE";
                    case '5' -> "PAGE_UP";
                    default -> "PAGE_DOWN";
                };
            }
            default -> "ESCAPE";
        };
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
            stty("-icanon", "min", "1", "time", "0", "-echo", "-isig", "-ixon");
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
