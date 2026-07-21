package com.follarce.extension.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellCommandParserTest {

    @Test
    void parsesQuotedAndEscapedArgumentsWithoutCallingAHostShell() {
        ShellCommand command = ShellCommandParser.parse(
                "RUN \"/user/local/app/my worker.fcl\" --name 'worker one' escaped\\ value \"\"")
                .orElseThrow();

        assertEquals("run", command.name());
        assertEquals(List.of(
                "/user/local/app/my worker.fcl", "--name", "worker one", "escaped value", ""),
                command.arguments());
    }

    @Test
    void ignoresBlankLinesAndRejectsIncompleteSyntax() {
        assertTrue(ShellCommandParser.parse("   ").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> ShellCommandParser.parse("run \"/system/app/test.fcl"));
        assertThrows(IllegalArgumentException.class,
                () -> ShellCommandParser.parse("run /system/app/test.fcl\\"));
    }
}
