package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordPromptTest {
    @Test
    void rendersThePromptAndErasesTheOwnedBuffer() throws IOException {
        char[] buffer = "private-value".toCharArray();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalInput input = new TerminalInput() {
            @Override public String readLine() { return null; }
            @Override public char[] readPassword() { return buffer; }
            @Override public boolean passwordNeedsLineBreak() { return true; }
        };
        PasswordPrompt.Secret secret;

        try (PasswordPrompt.Secret value = new PasswordPrompt(input,
                new PrintWriter(bytes, true, StandardCharsets.UTF_8)).read("password> ")) {
            secret = value;
            assertEquals("private-value", new String(value.value()));
        }

        assertEquals("password> \n", bytes.toString(StandardCharsets.UTF_8));
        for (char character : buffer) assertEquals('\0', character);
        assertThrows(IllegalStateException.class, secret::value);
        secret.close();
    }
}
