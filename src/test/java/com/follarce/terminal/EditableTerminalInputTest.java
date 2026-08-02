package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditableTerminalInputTest {
    @Test
    void ansiFormattingInPromptDoesNotShiftThePhysicalCursor() throws Exception {
        byte[] source = "ab\u001b[DX\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(rendered, true, StandardCharsets.UTF_8);

        assertEquals("aXb", input.edit(output, "\u001b[32mlocal\u001b[0m> ", true));

        String terminalOutput = rendered.toString(StandardCharsets.UTF_8);
        assertTrue(terminalOutput.contains("\r\u001b[9C"),
                "seven visible prompt columns plus two input columns must position the cursor");
    }

    @Test
    void decodesUtf8InputAndUsesItsTerminalColumnWidth() throws Exception {
        byte[] source = "中文\u001b[D好\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(rendered, true, StandardCharsets.UTF_8);

        assertEquals("中好文", input.edit(output, "test> ", true));

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
    void nonRememberedAccessInputCannotBrowseAuthenticatedCommandHistory() throws Exception {
        byte[] source = "\u001b[A\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        input.replaceHistory(List.of("file.read(\"/private.txt\")"));
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("", input.edit(output, "access> ", false));
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
    void doesNotRenderOrAcceptInlineHistorySuggestions() throws Exception {
        byte[] source = "io.\u001b[C\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        input.replaceHistory(List.of("io.print(1)", "io.println(1)"));
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(rendered, true, StandardCharsets.UTF_8);

        assertEquals("io.", input.edit(output, "test> ", true));
        assertFalse(rendered.toString(StandardCharsets.UTF_8).contains("\u001b[2;37m"));
    }

    @Test
    void tabDoesNotCompleteOrAlterTheSubmission() throws Exception {
        byte[] source = ":pw\t\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals(":pw", input.editSubmission(output, "test> ", "...> ", true,
                _ -> true));
    }

    @Test
    void redrawMovesToTheTopOfAWidthWrappedLine() throws Exception {
        byte[] source = "abcdefgh\u001b[DX\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null, () -> 12);
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(rendered, true, StandardCharsets.UTF_8);

        assertEquals("abcdefgXh", input.edit(output, "test> ", true));

        String terminalOutput = rendered.toString(StandardCharsets.UTF_8);
        assertTrue(terminalOutput.contains("\r\u001b[1A\r"), terminalOutput);
        assertEquals(2, occurrences(terminalOutput, "test> "),
                "the cursor move and insertion each repaint one prompt, not one per soft row");
    }

    @Test
    void finishingFromTheFirstPhysicalRowMovesBelowTheWholeWrappedSubmission() throws Exception {
        byte[] source = "abcdefgh\u001b[D\u001b[D\u001b[D\u001b[D\u001b[D\u001b[D\u001b[D\u001b[D\n"
                .getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null, () -> 12);
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(rendered, true, StandardCharsets.UTF_8);

        assertEquals("abcdefgh", input.edit(output, "test> ", true));
        assertTrue(rendered.toString(StandardCharsets.UTF_8).endsWith("\u001b[1B\r\n"));
    }

    @Test
    void longPasteProducesLinearOutputInsteadOfRepaintingTheWholePrefix() throws Exception {
        String command = "x".repeat(5_000);
        byte[] source = (command + "\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null, () -> 20);
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(rendered, true, StandardCharsets.UTF_8);

        assertEquals(command, input.edit(output, "test> ", true));
        assertTrue(rendered.size() < command.length() * 2,
                "pasting at the end must not produce quadratic terminal output");
    }

    @Test
    void widthIsScopedToEachEditableTerminalConnection() throws Exception {
        byte[] source = "abcdefgh\u001b[DX\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream narrowOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream wideOutput = new ByteArrayOutputStream();
        TerminalInput.EditableTerminalInput narrow = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null, () -> 12);
        TerminalInput.EditableTerminalInput wide = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null, () -> 40);

        assertEquals("abcdefgXh", narrow.edit(new PrintWriter(narrowOutput, true,
                StandardCharsets.UTF_8), "test> ", true));
        assertEquals("abcdefgXh", wide.edit(new PrintWriter(wideOutput, true,
                StandardCharsets.UTF_8), "test> ", true));

        assertTrue(narrowOutput.toString(StandardCharsets.UTF_8).contains("\u001b[1A"));
        assertFalse(wideOutput.toString(StandardCharsets.UTF_8).contains("\u001b[1A"));
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

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
