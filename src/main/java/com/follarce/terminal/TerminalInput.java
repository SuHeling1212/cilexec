package com.follarce.terminal;

import com.follarce.application.InteractionSubmissionLimits;

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
    int MAX_SUBMISSION_CHARACTERS = InteractionSubmissionLimits.MAX_SUBMISSION_CHARACTERS;
    /**
     * A bracketed paste is one terminal event and is persisted before its receiver runs.
     * Keep its maximum equal to a normal submitted command, so a damaged/accidental multi-MiB
     * paste cannot monopolize the terminal reader or create an unbounded durable input row.
     */
    int MAX_BRACKETED_PASTE_CHARACTERS = MAX_SUBMISSION_CHARACTERS;

    /** Optional transport hook for out-of-band events that only belong to raw-key mode. */
    interface KeyModeTransport {
        void beginKeyMode();

        void endKeyMode();
    }

    /**
     * Raised when a submission exceeds the character limit. Unlike a transport failure,
     * this must not close the session: the terminal reports the error and keeps running.
     */
    final class SubmissionLimitExceeded extends IOException {
        private static final long serialVersionUID = 1L;

        SubmissionLimitExceeded(String message) {
            super(message);
        }
    }

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
                throw new SubmissionLimitExceeded("Terminal submission exceeds 256 Ki characters");
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

    /**
     * Reads one input event as canonical JSON for an attached full-screen FCL application.
     * Key events carry modifiers, and mouse / paste / focus / unknown escape sequences are
     * preserved instead of collapsing to a single key name.
     */
    default String readKeyEvent(PrintWriter output) throws IOException {
        String key = readKey(output);
        if (key == null) return null;
        return "{\"kind\":\"key\",\"key\":\"" + jsonEscape(key)
                + "\",\"shift\":false,\"ctrl\":false,\"alt\":false,\"text\":\"\"}";
    }

    /** Reads one key event, optionally coalescing printable text within a short bounded window. */
    default String readKeyEvent(PrintWriter output, boolean coalesceText) throws IOException {
        return readKeyEvent(output);
    }

    static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
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

    /**
     * Compatibility facade for the dependency-free line editor historically constructed
     * through {@code TerminalInput.EditableTerminalInput}.
     */
    final class EditableTerminalInput extends EditableTerminalInputEngine {
        EditableTerminalInput(InputStream stream, Console console) {
            super(stream, console);
        }

        EditableTerminalInput(InputStream stream, Console console, IntSupplier terminalWidth) {
            super(stream, console, terminalWidth);
        }
    }

    @FunctionalInterface
    interface KeyReader {
        int read() throws IOException;
    }

    static String decodeKey(int first, KeyReader input) throws IOException {
        if (first < 0) return null;
        while (first == 0) { // NUL is transport framing, never a key token.
            first = input.read();
            if (first < 0) return null;
        }
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
