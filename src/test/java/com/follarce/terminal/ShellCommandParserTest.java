package com.follarce.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ShellCommandParserTest {
    private final ShellCommandParser parser = new ShellCommandParser();

    @Test
    void parsesQuotedWorkingDirectoryCommands() {
        ShellCommand.ChangeDirectory command = assertInstanceOf(
                ShellCommand.ChangeDirectory.class, parser.parse("cd \"/user/alice/my files\""));
        assertEquals("/user/alice/my files", command.path());
        assertEquals(new ShellCommand.ListDirectory(java.util.Optional.empty()),
                parser.parse("ls"));
        assertEquals(new ShellCommand.WorkingDirectory(), parser.parse("pwd"));
        assertEquals(new ShellCommand.StartExport(java.util.Optional.empty()),
                parser.parse("exp-start"));
        assertEquals(new ShellCommand.StartExport(java.util.Optional.of("my script.fcl")),
                parser.parse("exp-start \"my script.fcl\""));
        assertEquals(new ShellCommand.EndExport(), parser.parse("exp-end"));
    }

    @Test
    void keepsOnlyTheMinimalSessionCommands() {
        assertEquals(new ShellCommand.Logout(), parser.parse("logout"));
        assertEquals(new ShellCommand.Shutdown(), parser.parse("shutdown"));
        assertEquals(new ShellCommand.Clear(), parser.parse("clear"));
        assertEquals(new ShellCommand.Clear(), parser.parse("cls"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("ps"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("kill 42"));
    }

    @Test
    void rejectsIncompleteQuotesAndExtraArguments() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("cd \"broken"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("ls / extra"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("exp-start one two"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("exp-end extra"));
    }
}
