package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditableTerminalInputTest {
    @Test
    void standaloneEscapeDoesNotConsumeTheFollowingKey() throws Exception {
        try (PipedInputStream source = new PipedInputStream();
             PipedOutputStream sink = new PipedOutputStream(source)) {
            TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                    source, null);
            PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            sink.write(27);
            sink.flush();

            assertEquals("{\"kind\":\"key\",\"key\":\"ESCAPE\",\"shift\":false,"
                    + "\"ctrl\":false,\"alt\":false,\"text\":\"\"}",
                    input.readKeyEvent(output));

            sink.write('x');
            sink.flush();
            assertEquals("{\"kind\":\"key\",\"key\":\"x\",\"shift\":false,"
                    + "\"ctrl\":false,\"alt\":false,\"text\":\"x\"}",
                    input.readKeyEvent(output));
        }
    }

    @Test
    void decodesKeyEventsWithModifiersFunctionKeysAndPrintableText() throws Exception {
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(("\u001b[1;5A\u001b[2~\u001b[11~x"
                        + "\u001b[1;2D").getBytes(StandardCharsets.UTF_8)), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);
        assertEquals("{\"kind\":\"key\",\"key\":\"UP\",\"shift\":false,\"ctrl\":true,"
                + "\"alt\":false,\"text\":\"\"}", input.readKeyEvent(output));
        assertEquals("{\"kind\":\"key\",\"key\":\"INSERT\",\"shift\":false,\"ctrl\":false,"
                + "\"alt\":false,\"text\":\"\"}", input.readKeyEvent(output));
        assertEquals("{\"kind\":\"key\",\"key\":\"F1\",\"shift\":false,\"ctrl\":false,"
                + "\"alt\":false,\"text\":\"\"}", input.readKeyEvent(output));
        assertEquals("{\"kind\":\"key\",\"key\":\"x\",\"shift\":false,\"ctrl\":false,"
                + "\"alt\":false,\"text\":\"x\"}", input.readKeyEvent(output));
        assertEquals("{\"kind\":\"key\",\"key\":\"LEFT\",\"shift\":true,\"ctrl\":false,"
                + "\"alt\":false,\"text\":\"\"}", input.readKeyEvent(output));
    }

    @Test
    void decodesMouseFocusPasteAndUnknownEscapeEvents() throws Exception {
        byte[] source = ("\u001b[<0;5;3M\u001b[<65;7;9m\u001b[I"
                + "\u001b[200~pasted \r\ntext\u001b[201~\u001b[1;9A")
                .getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);
        assertEquals("{\"kind\":\"mouse\",\"button\":\"LEFT\",\"action\":\"PRESS\","
                + "\"scroll\":0,\"x\":5,\"y\":3,\"shift\":false,\"alt\":false,\"ctrl\":false}",
                input.readKeyEvent(output));
        assertEquals("{\"kind\":\"mouse\",\"button\":\"WHEEL\",\"action\":\"SCROLL\","
                + "\"scroll\":-1,\"x\":7,\"y\":9,\"shift\":false,\"alt\":false,\"ctrl\":false}",
                input.readKeyEvent(output));
        assertEquals("{\"kind\":\"focus\",\"focus\":true}", input.readKeyEvent(output));
        assertEquals("{\"kind\":\"paste\",\"text\":\"pasted \\ntext\"}",
                input.readKeyEvent(output));
        assertEquals("{\"kind\":\"key\",\"key\":\"UP\",\"shift\":false,\"ctrl\":false,"
                + "\"alt\":true,\"text\":\"\"}", input.readKeyEvent(output));
    }

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
        assertTrue(rendered.toString(StandardCharsets.UTF_8)
                .endsWith("\u001b[1B\r\n\u001b[<u"));
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
    void enterAfterATrailingBackslashContinuesTheLineInsteadOfSubmitting() throws Exception {
        byte[] source = ("process.exec(\\\n\"/next.fcl\")\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("process.exec(\\\n\"/next.fcl\")",
                input.editSubmission(output, "test> ", "...> ", true, FclInputBuffer::complete));
    }

    @Test
    void shiftEnterInsertsALineBreakEvenWhenDelimitersAreBalanced() throws Exception {
        byte[] source = ("f(1) \u001b[13;2u2)\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("f(1) \n2)", input.editSubmission(output, "test> ", "...> ", true,
                FclInputBuffer::complete));
    }

    @Test
    void shiftEnterSupportsTheXtermModifyOtherKeysSequence() throws Exception {
        byte[] source = ("if (x) \u001b[13;2~{\n}\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("if (x) \n{\n}", input.editSubmission(output, "test> ", "...> ", true,
                FclInputBuffer::complete));
    }

    @Test
    void negotiatesTheKittyKeyboardProtocolAroundEverySubmission() throws Exception {
        byte[] source = ("value = 1\n").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(bytes, true, StandardCharsets.UTF_8);

        input.editSubmission(output, "test> ", "...> ", true, FclInputBuffer::complete);

        String transcript = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("\u001b[>1u"), "kitty protocol push: " + transcript);
        assertTrue(transcript.contains("\u001b[<u"), "kitty protocol pop: " + transcript);
    }

    @Test
    void kittyModifierSequencesAreConsumedWithoutReachingTheText() throws Exception {
        byte[] source = ("ab\u001b[120;3uX\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("abX", input.editSubmission(output, "test> ", "...> ", true,
                FclInputBuffer::complete));
    }

    @Test
    void bracketedPasteMarkerIsConsumedWithoutReachingTheText() throws Exception {
        byte[] source = ("ab\u001b[200~X\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("abX", input.editSubmission(output, "test> ", "...> ", true,
                FclInputBuffer::complete));
    }

    @Test
    void unrelatedEscSequencesKeepTheirPreviousBehaviorAfterShiftEnterLookahead() throws Exception {
        // ESC [ 1 ; 2 D (Shift+Left) is not Shift+Enter; it is a numeric CSI
        // sequence and is consumed whole, so its bytes never reach the editor as
        // ordinary text (previously the trailing ";2D" leaked into the input).
        byte[] source = ("ab\u001b[1;2D X\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("ab X", input.editSubmission(output, "test> ", "...> ", true,
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

    @Test
    void upArrowAtTheFirstLineOfAMultilineBufferRecallsEarlierHistory() throws Exception {
        byte[] source = ("first command\n"
                + "ab{\ncd\u001b[A\u001b[A\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("first command", input.editSubmission(output, "test> ", "...> ", true,
                FclInputBuffer::complete));
        assertEquals("first command", input.editSubmission(output, "test> ", "...> ", true,
                FclInputBuffer::complete));
    }

    @Test
    void downArrowRestoresTheMultilineDraftAfterBrowsingHistory() throws Exception {
        byte[] source = ("saved\n"
                + "ab{\ncd\u001b[A\u001b[A\u001b[B\u001b[B}\n").getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("saved", input.editSubmission(output, "test> ", "...> ", true,
                FclInputBuffer::complete));
        assertEquals("ab{\ncd}", input.editSubmission(output, "test> ", "...> ", true,
                FclInputBuffer::complete));
    }

    @Test
    void loneEscCancelsTheLineWithoutSwallowingTheNextCharacter() throws Exception {
        byte[] source = "ab\u001bx\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("", input.edit(output, "test> ", true));
        assertEquals("x", input.edit(output, "test> ", true));
    }

    @Test
    void ctrlCInRawModeCancelsTheCurrentLine() throws Exception {
        byte[] source = "abc\u0003def\n".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);

        assertEquals("", input.readLine());
        assertEquals("def", input.readLine());
    }

    @Test
    void ctrlDInRawModeIsEndOfInput() throws Exception {
        byte[] source = "abc\u0004".getBytes(StandardCharsets.UTF_8);
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);

        assertEquals("abc", input.readLine());
        assertNull(input.readLine());
    }

    @Test
    void rejectsOverlongUtf8AndReReadsTheOffendingContinuationByte() throws Exception {
        byte[] source = new byte[]{(byte) 0xC0, (byte) 0x80, 'A', '\n'};
        TerminalInput.EditableTerminalInput input = new TerminalInput.EditableTerminalInput(
                new ByteArrayInputStream(source), null);
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("\uFFFD\uFFFDA", input.edit(output, "test> ", true));
    }

    @Test
    void nulBytesAreSkippedByTheKeyReader() throws Exception {
        TerminalInput input = TerminalInput.visible(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(new byte[]{0, 0, 'x'}), StandardCharsets.UTF_8)));
        PrintWriter output = new PrintWriter(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);

        assertEquals("x", input.readKey(output));
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
