package com.follarce.terminal;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;

/** Central hidden-password input and deterministic secret-erasure boundary. */
public final class PasswordPrompt {
    private final TerminalInput input;
    private final PrintWriter output;

    public PasswordPrompt(TerminalInput input, PrintWriter output) {
        this.input = java.util.Objects.requireNonNull(input, "input");
        this.output = java.util.Objects.requireNonNull(output, "output");
    }

    /** Returns {@code null} when input closes before a password is entered. */
    public Secret read(String prompt) throws IOException {
        if (prompt == null || prompt.isEmpty()) {
            throw new IllegalArgumentException("Password prompt is required");
        }
        output.print(prompt);
        output.flush();
        char[] value = input.readPassword();
        if (input.passwordNeedsLineBreak()) output.println();
        return value == null ? null : new Secret(value);
    }

    /** A password buffer that is erased when its lexical scope ends. */
    public static final class Secret implements AutoCloseable {
        private final char[] value;
        private boolean closed;

        private Secret(char[] value) {
            this.value = java.util.Objects.requireNonNull(value, "value");
        }

        public char[] value() {
            if (closed) throw new IllegalStateException("Password has already been erased");
            return value;
        }

        @Override
        public void close() {
            if (closed) return;
            Arrays.fill(value, '\0');
            closed = true;
        }
    }
}
