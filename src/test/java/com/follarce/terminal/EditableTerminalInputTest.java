package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditableTerminalInputTest {
    @Test
    void recallsCommandHistoryAndEditsWithinTheLineWithArrowKeys() throws Exception {
        byte[] source = ("first command\n"
                + "\u001b[A\n"
                + "ac\u001b[Db\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("first command", input.edit(output, "test> ", true));
        assertEquals("first command", input.edit(output, "test> ", true));
        assertEquals("abc", input.edit(output, "test> ", true));
    }

    @Test
    void downArrowRestoresTheDraftAfterBrowsingHistory() throws Exception {
        byte[] source = ("saved\n"
                + "draft\u001b[A\u001b[B\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("saved", input.edit(output, "test> ", true));
        assertEquals("draft", input.edit(output, "test> ", true));
    }
}
