package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditableTerminalInputTest {
    @Test
    void ansiFormattingInPromptDoesNotShiftThePhysicalCursor() throws Exception {
        byte[] source = "a\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(rendered, true, StandardCharsets.UTF_8);

        assertEquals("a", input.edit(output, "\u001b[32mlocal\u001b[0m> ", true));

        String terminalOutput = rendered.toString(StandardCharsets.UTF_8);
        assertTrue(terminalOutput.contains("\r\u001b[8C"),
                "seven visible prompt columns plus one input column must position the cursor");
    }

    @Test
    void decodesUtf8InputAndUsesItsTerminalColumnWidth() throws Exception {
        byte[] source = "中文\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(rendered, true, StandardCharsets.UTF_8);

        assertEquals("中文", input.edit(output, "test> ", true));

        String terminalOutput = rendered.toString(StandardCharsets.UTF_8);
        assertTrue(terminalOutput.contains("\r\u001b[10C"),
                "six prompt columns plus two double-width Chinese characters are ten columns");
    }

    @Test
    void arrowAndBackspaceOperateOnWholeUnicodeCodePoints() throws Exception {
        byte[] source = "中😀文\u001b[D\b\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("中文", input.edit(output, "test> ", true));
    }

    @Test
    void restoredHistoryIsSilentAndAvailableAfterInputObjectRestart() throws Exception {
        byte[] source = "\u001b[A\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput restarted = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(rendered, true, StandardCharsets.UTF_8);

        restarted.replaceHistory(List.of("before restart"));

        assertEquals(0, rendered.size(), "loading history must not print it");
        assertEquals("before restart", restarted.edit(output, "test> ", true));
    }

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

    @Test
    void backspaceAtStartOfContinuationLineMergesWithPreviousLine() throws Exception {
        byte[] source = ("ab{\ncd\b\b\b}\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("ab{}", input.editSubmission(output, "test> ", "...> ", true,
                FclInputBuffer::complete));
    }

    @Test
    void upAndDownArrowsMoveTheCursorBetweenSubmissionLines() throws Exception {
        byte[] source = ("ab{\ncd\u001b[AX\u001b[B}\n")
                .getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("abX{\ncd}", input.editSubmission(output, "test> ", "...> ", true,
                FclInputBuffer::complete));
    }

    @Test
    void leftAndRightArrowsCrossTheLineBoundary() throws Exception {
        byte[] source = ("ab{\ncd\u001b[D\u001b[D\u001b[D\u001b[CX}\n")
                .getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("ab{\nX}cd", input.editSubmission(output, "test> ", "...> ", true,
                FclInputBuffer::complete));
    }
}
