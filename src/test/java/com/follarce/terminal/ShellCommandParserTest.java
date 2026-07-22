package com.follarce.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ShellCommandParserTest {
    private final ShellCommandParser parser = new ShellCommandParser();

    @Test
    void parsesQuotedRunOptionsWithoutHostPathExpansion() {
        ShellCommand.Run command = assertInstanceOf(ShellCommand.Run.class,
                parser.parse("run \"/user/alice/my app.fcl\" --user alice --name \"my app\""));
        assertEquals("/user/alice/my app.fcl", command.vfsPath());
        assertEquals(Optional.of("alice"), command.user());
        assertEquals(Optional.of("my app"), command.name());
    }

    @Test
    void resumeKeepsTheHistoricalContinueSpelling() {
        assertEquals(new ShellCommand.Resume(42), parser.parse("continue 42"));
    }

    @Test
    void rejectsIncompleteQuotesAndNonPositivePid() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("run \"broken"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("kill 0"));
    }
}
