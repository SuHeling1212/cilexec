package com.follarce.terminal;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/** One input source that can disable password echo when attached to a real TTY. */
public interface TerminalInput {
    int MAX_SUBMISSION_CHARACTERS = 256 * 1024;
    /**
     * A bracketed paste is one terminal event and is persisted before its receiver runs.
     * Keep its maximum equal to a normal submitted command, so a damaged/accidental multi-MiB
     * paste cannot monopolize the terminal reader or create an unbounded durable input row.
     */
    int MAX_BRACKETED_PASTE_CHARACTERS = MAX_SUBMISSION_CHARACTERS;

    /**
     * Raised when a submission exceeds the character limit. Unlike a transport failure,
     * this must not close the session: the terminal reports the error and keeps running.
     */
    final class SubmissionLimitExceeded extends IOException {
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

    /** A small dependency-free line editor for a real TTY. */
    final class EditableTerminalInput implements TerminalInput {
        private static final int HISTORY_LIMIT = TerminalService.COMMAND_HISTORY_LIMIT;
        private static final int MAX_TEXT_BATCH_CODE_POINTS = 64;
        private static final int PUSHBACK_CAPACITY = 4;
        private static final long MAX_TEXT_BATCH_WAIT_NANOS = 8_000_000L;
        private static final long TEXT_BATCH_POLL_NANOS = 1_000_000L;
        private static final long ESCAPE_SEQUENCE_WAIT_NANOS = 30_000_000L;
        /**
         * Kitty keyboard protocol (push): asks the terminal to disambiguate modifier
         * keys, so Shift+Enter arrives as CSI 13;2u instead of a plain CR on
         * terminals that support it (iTerm2, kitty, WezTerm, Windows Terminal, Foot).
         * Terminals without support silently ignore the sequence.
         */
        private static final String KITTY_PROTOCOL_ENABLE = "\u001b[>1u";
        private static final String KITTY_PROTOCOL_DISABLE = "\u001b[<u";

        private final InputStream stream;
        private final Console console;
        private final IntSupplier terminalWidth;
        private final List<String> history = new ArrayList<>();
        private final StringBuilder pasteBuffer = new StringBuilder();
        private boolean inPaste;
        private boolean rejectedPaste;
        private RawMode keyMode;
        private boolean kittyProtocolActive;

        EditableTerminalInput(InputStream stream, Console console) {
            this(stream, console, () -> TerminalDimensions.current().width());
        }

        EditableTerminalInput(InputStream stream, Console console, IntSupplier terminalWidth) {
            // Shift+Enter lookahead can restore four bytes. Wrap even an existing pushback
            // stream so its original capacity cannot make that restoration fail.
            InputStream source = java.util.Objects.requireNonNull(stream, "stream");
            this.stream = new PushbackInputStream(source, PUSHBACK_CAPACITY);
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
        public String readKeyEvent(PrintWriter output) throws IOException {
            return readKeyEvent(output, false);
        }

        @Override
        public String readKeyEvent(PrintWriter output, boolean coalesceText) throws IOException {
            if (console != null && keyMode == null) keyMode = RawMode.enable();
            int first = stream.read();
            String event = coalesceText && !inPaste
                    ? decodeTextBatch(first) : decodeEvent(first);
            if (console != null) TerminalDimensions.refresh();
            return event;
        }

        /** Coalesces printable text for at most 8 ms from the first code point. */
        private String decodeTextBatch(int first) throws IOException {
            if (first == 127 || first == 8) return decodeBufferedBackspaces(first);
            String firstText = decodePrintableText(first);
            if (firstText == null) return decodeEvent(first);
            StringBuilder text = new StringBuilder(firstText);
            int codePoints = firstText.codePointCount(0, firstText.length());
            long deadline = System.nanoTime() + MAX_TEXT_BATCH_WAIT_NANOS;
            while (codePoints < MAX_TEXT_BATCH_CODE_POINTS
                    && awaitTextBatchInput(deadline)) {
                int next = stream.read();
                if (next < 0) break;
                String nextText = decodePrintableTextBeforeDeadline(next, deadline);
                if (nextText == null) {
                    unread(next);
                    break;
                }
                if (nextText.isEmpty()) break;
                text.append(nextText);
                codePoints += nextText.codePointCount(0, nextText.length());
            }
            if (codePoints == 1) return keyEvent(firstText, false, false, false, firstText);
            return "{\"kind\":\"paste\",\"text\":\"" + jsonEscape(text.toString()) + "\"}";
        }

        /** Collapses an already-buffered key-repeat backlog into one durable input event. */
        private String decodeBufferedBackspaces(int first) throws IOException {
            int count = 1;
            while (count < MAX_TEXT_BATCH_CODE_POINTS && stream.available() > 0) {
                int next = stream.read();
                if (next != 127 && next != 8) {
                    unread(next);
                    break;
                }
                count++;
            }
            if (count == 1) return keyEvent("BACKSPACE", false, false, false);
            return "{\"kind\":\"repeat\",\"key\":\"BACKSPACE\",\"count\":"
                    + count + "}";
        }

        private boolean awaitTextBatchInput(long deadline) throws IOException {
            while (true) {
                // Buffered bytes end the batch immediately; the deadline only bounds
                // blocking waits. Checking availability first keeps a slow decode
                // (for example under emulation) from truncating a paste whose bytes
                // are already buffered.
                if (stream.available() > 0) return true;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                LockSupport.parkNanos(Math.min(remaining, TEXT_BATCH_POLL_NANOS));
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Terminal input interrupted");
                }
            }
        }

        /** Decodes one input event, buffering bracketed-paste content across reads. */
        private String decodeEvent(int first) throws IOException {
            while (inPaste) {
                if (first == 27) {
                    int bracket = stream.read();
                    if (bracket == '[') {
                        int code = stream.read();
                        if (code == '2') {
                            int digit = stream.read();
                            if (digit == '0') {
                                int tail = stream.read();
                                if (tail == '1' && stream.read() == '~') {
                                    return finishBracketedPaste();
                                }
                                appendPaste("\u001b[2" + (char) digit + (char) tail);
                            } else {
                                appendPaste("\u001b[2" + (char) digit);
                            }
                        } else {
                            appendPaste("\u001b[" + (char) code);
                        }
                    } else if (bracket >= 0) {
                        appendPaste("\u001b" + (char) bracket);
                    }
                } else if (first >= 0) {
                    String text = decodePrintableText(first);
                    if (text != null) {
                        appendPaste(text);
                    } else {
                        appendPasteCodePoint(first);
                    }
                } else {
                    return null;
                }
                first = stream.read();
            }

            if (first == 27) {
                int prefix = readEscapeContinuation();
                if (prefix < 0) return keyEvent("ESCAPE", false, false, false);
                if (prefix == '[') {
                    int code = stream.read();
                    if (code == 'I') return "{\"kind\":\"focus\",\"focus\":true}";
                    if (code == 'O') return "{\"kind\":\"focus\",\"focus\":false}";
                    if (code == 'M') return decodeMouse(stream::read, false);
                    if (code == 'm') return decodeMouse(stream::read, true);
                    if (code == 'Z') return keyEvent("SHIFT_TAB", false, true, false);
                    if (code == '<') return decodeSgrMouse(stream::read);
                    if (code >= 'A' && code <= 'F') {
                        return keyEvent(arrowName((char) code), false, false, false);
                    }
                    if (code >= '0' && code <= '9') {
                        int number = code - '0';
                        while (number <= 999) {
                            int next = stream.read();
                            if (next == ';') return decodeModified(stream::read);
                            if (next == '~') {
                                if (number == 200) {
                                    inPaste = true;
                                    return decodeEvent(stream.read());
                                }
                                return decodeTilde(number);
                            }
                            if (next < '0' || next > '9') break;
                            number = number * 10 + (next - '0');
                        }
                    }
                    return rawEvent(escapeSequence(first, prefix, code));
                }
                if (prefix == 'O') {
                    int code = stream.read();
                    return switch (code) {
                        case 'P' -> keyEvent("F1", false, false, false);
                        case 'Q' -> keyEvent("F2", false, false, false);
                        case 'R' -> keyEvent("F3", false, false, false);
                        case 'S' -> keyEvent("F4", false, false, false);
                        default -> rawEvent(escapeSequence(first, prefix, code));
                    };
                }
                return rawEvent(escapeSequence(first, prefix, -1));
            }
            return decodePlainEvent(first);
        }

        /**
         * Completes a bracketed paste without ever retaining an over-limit partial result.
         * The complete terminal sequence is still consumed, keeping its trailing bytes from
         * becoming shell commands or raw mouse text after the editor returns.
         */
        private String finishBracketedPaste() {
            inPaste = false;
            if (rejectedPaste) {
                rejectedPaste = false;
                pasteBuffer.setLength(0);
                return "{\"kind\":\"paste_rejected\",\"limit\":"
                        + MAX_BRACKETED_PASTE_CHARACTERS + "}";
            }
            String text = pasteBuffer.toString().replace("\r\n", "\n");
            pasteBuffer.setLength(0);
            return "{\"kind\":\"paste\",\"text\":\"" + jsonEscape(text) + "\"}";
        }

        private void appendPasteCodePoint(int codePoint) {
            if (codePoint >= 0) appendPaste(new String(Character.toChars(codePoint)));
        }

        private void appendPaste(String value) {
            if (rejectedPaste || value.isEmpty()) return;
            if (value.length() > MAX_BRACKETED_PASTE_CHARACTERS - pasteBuffer.length()) {
                // Reject rather than truncate: an incomplete source or document is worse than
                // a clear message, and clearing releases the potentially very large buffer.
                rejectedPaste = true;
                pasteBuffer.setLength(0);
                return;
            }
            pasteBuffer.append(value);
        }

        /** Distinguishes a standalone Escape key from the start of an ANSI sequence. */
        private int readEscapeContinuation() throws IOException {
            long deadline = System.nanoTime() + ESCAPE_SEQUENCE_WAIT_NANOS;
            while (stream.available() == 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return -1;
                java.util.concurrent.locks.LockSupport.parkNanos(
                        Math.min(remaining, 1_000_000L));
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            return stream.read();
        }

        private String decodePlainEvent(int first) throws IOException {
            if (first < 0) return null;
            if (first == '\r' || first == '\n') return keyEvent("ENTER", false, false, false);
            if (first == '\t') return keyEvent("TAB", false, false, false);
            if (first == 127 || first == 8) return keyEvent("BACKSPACE", false, false, false);
            if (first > 0 && first < 27) {
                return "{\"kind\":\"key\",\"key\":\"CTRL_" + (char) ('A' + first - 1)
                        + "\",\"shift\":false,\"ctrl\":true,\"alt\":false,\"text\":\"\"}";
            }
            String text = decodePrintableText(first);
            if (text == null) return null;
            return keyEvent(text, false, false, false, text);
        }

        private String decodePrintableText(int first) throws IOException {
            if (first < 0 || first == 27 || first == '\r' || first == '\n' || first == '\t'
                    || first == 127 || first == 8 || first > 0 && first < 27) {
                return null;
            }
            if (first < 128) return String.valueOf((char) first);
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

        /**
         * Decodes a batch code point without allowing its continuation bytes to cross the
         * batch deadline. A partial code point is restored for the following input event.
         */
        private String decodePrintableTextBeforeDeadline(int first, long deadline)
                throws IOException {
            if (first < 0 || first == 27 || first == '\r' || first == '\n' || first == '\t'
                    || first == 127 || first == 8 || first > 0 && first < 27) {
                return null;
            }
            if (first < 128) return String.valueOf((char) first);
            int length = first >= 0xF0 ? 4 : first >= 0xE0 ? 3 : 2;
            int[] bytes = new int[length];
            bytes[0] = first;
            for (int index = 1; index < length; index++) {
                if (!awaitTextBatchInput(deadline)) {
                    pushBack(bytes, index);
                    return "";
                }
                int next = stream.read();
                if (next < 0) return null;
                bytes[index] = next;
            }
            byte[] utf8 = new byte[length];
            for (int index = 0; index < length; index++) utf8[index] = (byte) bytes[index];
            return new String(utf8, StandardCharsets.UTF_8);
        }

        /** Decodes CSI <param>;<modifier><letter> modified-key sequences. */
        private String decodeModified(KeyReader input) throws IOException {
            int modifier = 0;
            int digit = input.read();
            while (digit >= '0' && digit <= '9') {
                modifier = modifier * 10 + (digit - '0');
                digit = input.read();
            }
            if (digit < 0) return rawEvent("ESC[;");
            return keyEvent(arrowName((char) digit), (modifier & 2) != 0,
                    (modifier & 4) != 0, (modifier & 8) != 0);
        }

        private String decodeTilde(int number) {
            return switch (number) {
                case 2 -> keyEvent("INSERT", false, false, false);
                case 3 -> keyEvent("DELETE", false, false, false);
                case 5 -> keyEvent("PAGE_UP", false, false, false);
                case 6 -> keyEvent("PAGE_DOWN", false, false, false);
                case 11, 12, 13, 14, 15 -> keyEvent("F" + (number - 10), false, false, false);
                case 17, 18, 19, 20, 21, 23, 24 -> keyEvent(
                        "F" + (number == 17 ? 6 : number == 18 ? 7 : number == 19 ? 8
                                : number == 20 ? 9 : number == 21 ? 10 : number == 23 ? 11 : 12),
                        false, false, false);
                case 28 -> keyEvent("F13", false, false, false);
                case 29 -> keyEvent("F14", false, false, false);
                case 31 -> keyEvent("F15", false, false, false);
                case 32 -> keyEvent("F16", false, false, false);
                case 33 -> keyEvent("F17", false, false, false);
                case 34 -> keyEvent("F18", false, false, false);
                default -> "{\"kind\":\"raw\",\"sequence\":\""
                        + jsonEscape("ESC[" + number + "~") + "\"}";
            };
        }

        private String decodeSgrMouse(KeyReader input) throws IOException {
            DigitScan button = readDigits(input);
            if (button.terminator != ';') return rawEvent("ESC[<" + button.digits);
            DigitScan x = readDigits(input);
            if (x.terminator != ';') {
                return rawEvent("ESC[<" + button.digits + ";" + x.digits);
            }
            DigitScan y = readDigits(input);
            int value = button.digits.isEmpty() ? 0 : Integer.parseInt(button.digits);
            boolean release = y.terminator == 'm';
            boolean motion = (value & 32) != 0;
            boolean wheel = (value & 64) != 0;
            int buttonMask = value & 3;
            String buttonName = wheel ? "WHEEL" : switch (buttonMask) {
                case 0 -> "LEFT";
                case 1 -> "MIDDLE";
                case 2 -> "RIGHT";
                default -> "NONE";
            };
            String action = wheel ? "SCROLL" : motion ? "MOVE"
                    : release ? "RELEASE" : "PRESS";
            int scroll = wheel ? ((value & 1) == 0 ? 1 : -1) : 0;
            int xValue = x.digits.isEmpty() ? 0 : Integer.parseInt(x.digits);
            int yValue = y.digits.isEmpty() ? 0 : Integer.parseInt(y.digits);
            return "{\"kind\":\"mouse\",\"button\":\"" + buttonName
                    + "\",\"action\":\"" + action + "\",\"scroll\":" + scroll
                    + ",\"x\":" + xValue + ",\"y\":" + yValue
                    + ",\"shift\":" + ((value & 4) != 0)
                    + ",\"alt\":" + ((value & 8) != 0)
                    + ",\"ctrl\":" + ((value & 16) != 0) + "}";
        }

        private record DigitScan(String digits, int terminator) {
        }

        private static DigitScan readDigits(KeyReader input) throws IOException {
            StringBuilder digits = new StringBuilder();
            int next = input.read();
            while (next >= '0' && next <= '9') {
                digits.append((char) next);
                next = input.read();
            }
            return new DigitScan(digits.toString(), next);
        }

        private String decodeMouse(KeyReader input, boolean release) throws IOException {
            int button = input.read() - 32;
            int x = input.read() - 32;
            int y = input.read() - 32;
            String buttonName = switch (button & 3) {
                case 0 -> "LEFT";
                case 1 -> "MIDDLE";
                case 2 -> "RIGHT";
                default -> "NONE";
            };
            return "{\"kind\":\"mouse\",\"button\":\"" + buttonName
                    + "\",\"action\":\"" + (release ? "RELEASE" : "PRESS")
                    + "\",\"scroll\":0,\"x\":" + x + ",\"y\":" + y
                    + ",\"shift\":" + ((button & 4) != 0)
                    + ",\"alt\":" + ((button & 8) != 0)
                    + ",\"ctrl\":" + ((button & 16) != 0) + "}";
        }

        private static String keyEvent(String key, boolean shift, boolean ctrl, boolean alt) {
            return keyEvent(key, shift, ctrl, alt, "");
        }

        private static String keyEvent(String key, boolean shift, boolean ctrl, boolean alt,
                                       String text) {
            return "{\"kind\":\"key\",\"key\":\"" + jsonEscape(key)
                    + "\",\"shift\":" + shift + ",\"ctrl\":" + ctrl
                    + ",\"alt\":" + alt + ",\"text\":\"" + jsonEscape(text) + "\"}";
        }

        private static String rawEvent(String sequence) {
            return "{\"kind\":\"raw\",\"sequence\":\"" + jsonEscape(sequence) + "\"}";
        }

        private static String escapeSequence(int first, int prefix, int code) {
            StringBuilder sequence = new StringBuilder("ESC");
            if (prefix == '[') sequence.append('[');
            else if (prefix == 'O') sequence.append('O');
            else sequence.append((char) prefix);
            if (code >= 0) sequence.append((char) code);
            return sequence.toString();
        }

        private static String arrowName(char code) {
            return switch (code) {
                case 'A' -> "UP";
                case 'B' -> "DOWN";
                case 'C' -> "RIGHT";
                case 'D' -> "LEFT";
                case 'H' -> "HOME";
                case 'F' -> "END";
                default -> String.valueOf(code);
            };
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
                if (character == 3) return ""; // Ctrl-C cancels the current line.
                if (character == 4) return value.isEmpty() ? null : value.toString(); // Ctrl-D is EOF.
                if (character == 127 || character == 8) {
                    if (!value.isEmpty()) {
                        int previous = value.offsetByCodePoints(value.length(), -1);
                        value.delete(previous, value.length());
                    }
                    continue;
                }
                if (character >= 32) value.append(decodeUtf8(character));
                if (value.length() > MAX_SUBMISSION_CHARACTERS) {
                    throw new SubmissionLimitExceeded("Terminal line exceeds 256 Ki characters");
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
            enableKittyProtocol(output);
            try {
                return editLoop(output, prompt, continuationPrompt, remember, complete, value,
                        cursor, initial, historyIndex, draft);
            } finally {
                disableKittyProtocol(output);
            }
        }

        private String editLoop(PrintWriter output, String prompt, String continuationPrompt,
                                boolean remember, Predicate<String> complete, StringBuilder value,
                                int cursor, RenderState initial, int historyIndex, String draft)
                throws IOException {
            int screenCursorLine = initial.cursorLine();
            int renderedLines = initial.renderedLines();
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
                    if (bracket == '[' || bracket == 'O') {
                        int direction = stream.read();
                        if (direction < 0) continue;
                        // Shift+Enter arrives as CSI 13;2u (kitty, iTerm2, Windows
                        // Terminal) or CSI 13;2~ (xterm modifyOtherKeys). It inserts a
                        // line break without submitting, so a continued line works
                        // even when every delimiter is already balanced.
                        if (direction == '1' && shiftEnter()) {
                            value.insert(cursor++, '\n');
                            requireSubmissionLimit(value);
                            RenderState state = render(output, prompt, continuationPrompt,
                                    value, cursor, screenCursorLine, renderedLines);
                            screenCursorLine = state.cursorLine();
                            renderedLines = state.renderedLines();
                            continue;
                        }
                        // Numeric CSI sequences are modifier-key reports (kitty
                        // protocol: CSI 120;3u for Alt+X), bracketed-paste markers,
                        // or terminal replies. Consume them whole so their bytes never
                        // reach the editor as ordinary text.
                        if (direction >= '0' && direction <= '9') {
                            consumeCsi();
                            continue;
                        }
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
                    // A lone ESC cancels the line like Ctrl-C; a non-[ / non-O prefix byte is
                    // pushed back so it reaches the editor as ordinary input.
                    if (bracket >= 0) unread(bracket);
                    finish(output, screenCursorLine, renderedLines);
                    return "";
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
            if ((first & 0xE0) == 0xC0) {
                if (first < 0xC2) return "\uFFFD"; // 0xC0/0xC1 can only encode overlong forms.
                int second = stream.read();
                if (second < 0) return "\uFFFD";
                if ((second & 0xC0) != 0x80) {
                    unread(second);
                    return "\uFFFD";
                }
                return new String(new byte[]{(byte) first, (byte) second},
                        StandardCharsets.UTF_8);
            }
            if ((first & 0xF0) == 0xE0) {
                int second = stream.read();
                if (second < 0) return "\uFFFD";
                if ((second & 0xC0) != 0x80 || (first == 0xE0 && second < 0xA0)) {
                    unread(second);
                    return "\uFFFD";
                }
                int third = stream.read();
                if (third < 0) return "\uFFFD";
                if ((third & 0xC0) != 0x80) {
                    unread(third);
                    return "\uFFFD";
                }
                return new String(new byte[]{(byte) first, (byte) second, (byte) third},
                        StandardCharsets.UTF_8);
            }
            if ((first & 0xF8) == 0xF0) {
                if (first > 0xF4) return "\uFFFD"; // RFC 3629 stops at U+10FFFF.
                int second = stream.read();
                if (second < 0) return "\uFFFD";
                if ((second & 0xC0) != 0x80 || (first == 0xF0 && second < 0x90)
                        || (first == 0xF4 && second > 0x8F)) {
                    unread(second);
                    return "\uFFFD";
                }
                int third = stream.read();
                if (third < 0) return "\uFFFD";
                if ((third & 0xC0) != 0x80) {
                    unread(third);
                    return "\uFFFD";
                }
                int fourth = stream.read();
                if (fourth < 0) return "\uFFFD";
                if ((fourth & 0xC0) != 0x80) {
                    unread(fourth);
                    return "\uFFFD";
                }
                return new String(new byte[]{(byte) first, (byte) second, (byte) third,
                        (byte) fourth}, StandardCharsets.UTF_8);
            }
            return "\uFFFD";
        }

        private void unread(int value) throws IOException {
            ((PushbackInputStream) stream).unread(value);
        }

        /**
         * Consumes the remainder of the Shift+Enter escape sequence after the
         * leading {@code ESC [ 1 3}. Both the kitty {@code u} and the xterm
         * {@code ~} terminators are accepted. On any mismatch the consumed bytes
         * are pushed back so an unrelated sequence (for example
         * {@code ESC [ 1;5A}) keeps its old behavior.
         */
        private boolean shiftEnter() throws IOException {
            int[] consumed = new int[4];
            consumed[0] = stream.read();
            if (consumed[0] != '3') return pushBack(consumed, 1);
            consumed[1] = stream.read();
            if (consumed[1] != ';') return pushBack(consumed, 2);
            consumed[2] = stream.read();
            if (consumed[2] != '2') return pushBack(consumed, 3);
            consumed[3] = stream.read();
            if (consumed[3] != 'u' && consumed[3] != '~') return pushBack(consumed, 4);
            return true;
        }

        /**
         * Consumes a numeric CSI sequence up to its final byte (0x40-0x7E), for
         * example {@code ESC [ 120;3u} (Alt+X under the kitty protocol) or
         * {@code ESC [ 200~} (bracketed-paste start).
         */
        private void consumeCsi() throws IOException {
            while (true) {
                int value = stream.read();
                if (value < 0) return;
                if (value >= 0x40 && value <= 0x7E) return;
            }
        }

        private void enableKittyProtocol(PrintWriter output) {
            if (kittyProtocolActive) return;
            output.print(KITTY_PROTOCOL_ENABLE);
            output.flush();
            kittyProtocolActive = true;
        }

        private void disableKittyProtocol(PrintWriter output) {
            if (!kittyProtocolActive) return;
            output.print(KITTY_PROTOCOL_DISABLE);
            output.flush();
            kittyProtocolActive = false;
        }

        private boolean pushBack(int[] consumed, int count) throws IOException {
            for (int index = count - 1; index >= 0; index--) {
                if (consumed[index] >= 0) unread(consumed[index]);
            }
            return false;
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
                throw new SubmissionLimitExceeded("Terminal submission exceeds 256 Ki characters");
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
