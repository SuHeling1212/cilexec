package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalConsoleTest {
    @Test
    void separatesColonCommandsFromMultilineFcl() {
        String input = ":pwd\nfunc twice(value) {\nreturn value * 2\n}\ntwice(21)\n:exit\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl();

        new TerminalConsole(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)), new PrintWriter(output, true, StandardCharsets.UTF_8),
                control).run();

        assertInstanceOf(ShellCommand.WorkingDirectory.class, control.commands.get(0));
        assertInstanceOf(ShellCommand.Exit.class, control.commands.get(1));
        assertEquals(List.of("func twice(value) {\nreturn value * 2\n}", "twice(21)"),
                control.sources);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("...> "));
    }

    @Test
    void routesTheNextRawLineToAnAttachedInputWait() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl();
        control.waiting = true;
        String input = "hello from terminal\n:exit\n";

        new TerminalConsole(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)), new PrintWriter(output, true, StandardCharsets.UTF_8),
                control).run();

        assertEquals(List.of("hello from terminal"), control.attachedInputs);
    }

    @Test
    void keepsCommandsAvailableWhileAttachedInputWaitsAndEscapesLeadingColon() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl();
        control.waiting = true;
        String input = "::literal\n:logout\n";

        TerminalConsole.Outcome outcome = new TerminalConsole(new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(
                        input.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)),
                new PrintWriter(output, true, StandardCharsets.UTF_8), control).runSession();

        assertEquals(TerminalConsole.Outcome.LOGOUT, outcome);
        assertInstanceOf(ShellCommand.Logout.class, control.commands.getFirst());
        assertEquals(List.of(":literal"), control.attachedInputs);
    }

    @Test
    void explainsThatDirectoryCommandsNeedTheirColonPrefix() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl();
        String input = "ls\ncd /docs\n:exit\n";

        new TerminalConsole(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)), new PrintWriter(output, true, StandardCharsets.UTF_8),
                control).run();

        String transcript = output.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("for example :ls or :cd /path"), transcript);
        assertEquals(List.of(new ShellCommand.Exit()), control.commands);
    }

    private static final class RecordingControl implements TerminalControl {
        final List<ShellCommand> commands = new ArrayList<>();
        final List<String> sources = new ArrayList<>();
        final List<String> attachedInputs = new ArrayList<>();
        boolean waiting;

        @Override public String execute(ShellCommand command) {
            commands.add(command);
            return "command";
        }

        @Override public String evaluate(String source) {
            sources.add(source);
            return "value";
        }

        @Override public String submitAttachedInput(String input) {
            waiting = false;
            attachedInputs.add(input);
            return "accepted";
        }

        @Override public boolean awaitingAttachedInput() {
            return waiting;
        }
    }
}
