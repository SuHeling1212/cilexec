package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalSettingsTest {
    @Test
    void usesConfiguredAdministratorUsername() {
        assertEquals("operator", TerminalSettings.load(Map.of(
                "CILEXEC_TERMINAL_USERNAME", " operator ")).username());
    }

    @Test
    void defaultsAdministratorUsernameToLocal() {
        assertEquals("local", TerminalSettings.load(Map.of()).username());
    }
}
