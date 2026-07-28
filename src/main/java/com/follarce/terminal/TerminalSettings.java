package com.follarce.terminal;

import java.util.Map;

/** Host-terminal identity is fixed by deployment, never selected by untrusted FCL input. */
public record TerminalSettings(String username) {
    public TerminalSettings {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Terminal username is required");
        }
        username = username.trim();
        if (username.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Terminal username contains control characters");
        }
    }

    public static TerminalSettings load() {
        return load(System.getenv());
    }

    static TerminalSettings load(Map<String, String> environment) {
        String username = environment.getOrDefault("CILEXEC_TERMINAL_USERNAME", "local");
        return new TerminalSettings(username);
    }
}
