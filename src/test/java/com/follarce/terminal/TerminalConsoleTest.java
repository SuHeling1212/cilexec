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
import java.util.concurrent.atomic.AtomicInteger;

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
        assertEquals(1, control.commands.size(),
                ":exit must not be delegated to the shared Runtime control");
        assertEquals(List.of("func twice(value) {\nreturn value * 2\n}", "twice(21)"),
                control.sources);
        assertEquals(List.of(":pwd", "func twice(value) {\nreturn value * 2\n}",
                "twice(21)"), control.remembered);
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
        assertEquals(List.of(), control.remembered,
                "neither attached process input nor :exit may enter command history");
    }

    @Test
    void joinsCBackslashContinuationsBeforeEvaluatingFcl() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl();
        String input = "process.exec(\\\n\"/next.fcl\")\n:exit\n";

        new TerminalConsole(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)), new PrintWriter(output, true, StandardCharsets.UTF_8),
                control).run();

        assertEquals(List.of("process.exec(\"/next.fcl\")"), control.sources);
        assertEquals(List.of("process.exec(\\\n\"/next.fcl\")"), control.remembered,
                "history keeps the raw typed lines including the continuation backslash");
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
    void allowsTerminalCommandNamesAsFclVariablesWithoutAColon() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl();
        String input = "cd = 1\nls = 2\n:exit\n";

        new TerminalConsole(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)), new PrintWriter(output, true, StandardCharsets.UTF_8),
                control).run();

        assertEquals(List.of("cd = 1", "ls = 2"), control.sources);
        assertEquals(List.of("cd = 1", "ls = 2"), control.remembered);
        assertTrue(control.commands.isEmpty());
    }

    @Test
    void forwardsNormalizedRawKeysWithoutPrintingAReplPrompt() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl();
        control.keyMode = true;
        String input = "\u001b[A:exit\n";

        new TerminalConsole(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)), new PrintWriter(output, true, StandardCharsets.UTF_8),
                control).run();

        assertEquals(List.of("{\"kind\":\"key\",\"key\":\"UP\",\"shift\":false,"
                + "\"ctrl\":false,\"alt\":false,\"text\":\"\"}"), control.attachedInputs);
        assertTrue(control.commands.isEmpty());
    }

    @Test
    void treatsCtrlCAsGlobalCancellationInsteadOfEditorInput() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicInteger modeChecks = new AtomicInteger();
        RecordingControl control = new RecordingControl() {
            @Override public AttachedInputMode attachedInputMode() {
                return modeChecks.getAndIncrement() == 0
                        ? AttachedInputMode.KEY : AttachedInputMode.NONE;
            }
        };
        String input = "\u0003:exit\n";

        new TerminalConsole(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)), new PrintWriter(output, true, StandardCharsets.UTF_8),
                control).run();

        assertTrue(control.attachedInputs.isEmpty());
        assertTrue(control.commands.isEmpty());
    }

    @Test
    void restoresFullScreenTerminalModesAfterAnAttachedFclFailure() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl() {
            @Override public AttachedInputMode attachedInputMode() {
                return AttachedInputMode.KEY;
            }
            @Override public String submitAttachedInput(String input) {
                return "error: Map key does not exist: key";
            }
        };
        String input = "\u001b[<0;5;3M:exit\n";

        new TerminalConsole(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)), new PrintWriter(output, true, StandardCharsets.UTF_8),
                control).run();

        String transcript = output.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("\u001b[?1049l\u001b[?1002l\u001b[?1006l"
                + "\u001b[?2004l\u001b[?1004l\u001b[?25h\u001b[2J\u001b[H"),
                "a crashed full-screen FCL program must leave alternate screen and "
                        + "mouse/paste/focus reporting: " + transcript);
        assertTrue(transcript.contains("error: Map key does not exist: key"), transcript);
    }

    @Test
    void readsShutdownPasswordWithoutPassingItAsACommand() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl();
        String input = ":shutdown\nadministrator-password\n";

        TerminalConsole.Outcome outcome = new TerminalConsole(new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(
                        input.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)),
                new PrintWriter(output, true, StandardCharsets.UTF_8), control).runSession();

        assertEquals(TerminalConsole.Outcome.EXIT, outcome);
        assertTrue(control.shutdownRequested);
        assertEquals("administrator-password", control.shutdownPassword);
        assertEquals(List.of(":shutdown"), control.remembered);
        assertTrue(output.toString(StandardCharsets.UTF_8)
                .contains("administrator password> "));
    }

    @Test
    void shutdownPasswordPromptUsesTheAuthenticatedUsername() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl();
        control.username = "root";
        String input = ":shutdown\npw\n";

        TerminalConsole.Outcome outcome = new TerminalConsole(new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(
                        input.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)),
                new PrintWriter(output, true, StandardCharsets.UTF_8), control).runSession();

        assertEquals(TerminalConsole.Outcome.EXIT, outcome);
        assertTrue(control.shutdownRequested);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("root password> "));
    }

    @Test
    void rejectsShutdownBeforePromptingAnOrdinaryUserForAPassword() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl();
        control.shutdownAllowed = false;

        new TerminalConsole(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(":shutdown\n:exit\n".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)), new PrintWriter(output, true, StandardCharsets.UTF_8),
                control).run();

        String transcript = output.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("Administrator permission is required"), transcript);
        assertTrue(!transcript.contains("administrator password> "), transcript);
        assertTrue(!control.shutdownRequested);
    }

    @Test
    void stopsInsteadOfSpinningWhenTerminalControlKeepsThrowingWithoutAMessage() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingControl control = new RecordingControl() {
            @Override public AttachedInputMode attachedInputMode() {
                throw new NullPointerException();
            }
        };

        TerminalConsole.Outcome outcome = new TerminalConsole(new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8)),
                new PrintWriter(output, true, StandardCharsets.UTF_8), control).runSession();

        String transcript = output.toString(StandardCharsets.UTF_8);
        assertEquals(TerminalConsole.Outcome.END_OF_INPUT, outcome);
        assertTrue(transcript.contains("error: Command failed: NullPointerException"), transcript);
        assertTrue(transcript.contains("terminal stopped after repeated control failures"),
                transcript);
    }

    private static class RecordingControl implements TerminalControl {
        final List<ShellCommand> commands = new ArrayList<>();
        final List<String> sources = new ArrayList<>();
        final List<String> attachedInputs = new ArrayList<>();
        final List<String> remembered = new ArrayList<>();
        boolean waiting;
        boolean keyMode;
        boolean shutdownRequested;
        boolean shutdownAllowed = true;
        String shutdownPassword;
        String username = "administrator";

        @Override public String username() {
            return username;
        }

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
            keyMode = false;
            attachedInputs.add(input);
            return "accepted";
        }

        @Override public boolean awaitingAttachedInput() {
            return waiting;
        }

        @Override public AttachedInputMode attachedInputMode() {
            if (keyMode) return AttachedInputMode.KEY;
            return TerminalControl.super.attachedInputMode();
        }

        @Override public List<String> commandHistory() {
            return List.of("persisted before restart");
        }

        @Override public void rememberCommand(String command) {
            remembered.add(command);
        }

        @Override public void shutdown(char[] password) {
            shutdownRequested = true;
            shutdownPassword = new String(password);
        }

        @Override public boolean canShutdown() {
            return shutdownAllowed;
        }
    }
}
