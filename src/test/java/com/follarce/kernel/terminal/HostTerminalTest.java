package com.follarce.kernel.terminal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostTerminalTest {

    @AfterEach
    void releaseTerminal() {
        HostTerminal.releaseFromShell();
    }

    @Test
    void preventsFclProcessesFromStealingShellInput() {
        assertDoesNotThrow(HostTerminal::requireProcessInputAvailable);
        HostTerminal.claimForShell();
        assertThrows(IllegalStateException.class, HostTerminal::requireProcessInputAvailable);
        assertThrows(IllegalStateException.class, HostTerminal::claimForShell);
    }
}
