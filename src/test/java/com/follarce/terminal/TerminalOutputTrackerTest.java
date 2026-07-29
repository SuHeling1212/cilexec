package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalOutputTrackerTest {
    @Test
    void finishesOnlyAnOpenOutputLineBeforeTheNextPrompt() {
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(rendered, true, StandardCharsets.UTF_8);

        TerminalOutputTracker.printed(output, "value", false);
        TerminalOutputTracker.finishLine(output);
        TerminalOutputTracker.finishLine(output);

        assertEquals(System.lineSeparator(), rendered.toString(StandardCharsets.UTF_8));
    }

    @Test
    void printlnDoesNotAddASecondBlankLine() {
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(rendered, true, StandardCharsets.UTF_8);

        TerminalOutputTracker.printed(output, "value", true);
        TerminalOutputTracker.finishLine(output);

        assertEquals("", rendered.toString(StandardCharsets.UTF_8));
    }

    @Test
    void oneTerminalCannotChangeAnotherTerminalsLineState() {
        ByteArrayOutputStream firstBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream secondBytes = new ByteArrayOutputStream();
        PrintWriter first = new PrintWriter(firstBytes, true, StandardCharsets.UTF_8);
        PrintWriter second = new PrintWriter(secondBytes, true, StandardCharsets.UTF_8);

        TerminalOutputTracker.printed(first, "partial", false);
        TerminalOutputTracker.finishLine(second);
        TerminalOutputTracker.finishLine(first);

        assertEquals(System.lineSeparator(), firstBytes.toString(StandardCharsets.UTF_8));
        assertEquals("", secondBytes.toString(StandardCharsets.UTF_8));
    }
}
