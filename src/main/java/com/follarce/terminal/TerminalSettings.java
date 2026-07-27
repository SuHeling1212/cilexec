package com.follarce.terminal;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/** Host-terminal identity is fixed by deployment, never selected by untrusted FCL input. */
public record TerminalSettings(String username, Path bootstrapPasswordFile) {
    public TerminalSettings {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Terminal username is required");
        }
        username = username.trim();
        if (username.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Terminal username contains control characters");
        }
        if (bootstrapPasswordFile == null) {
            throw new IllegalArgumentException("Terminal bootstrap password file is required");
        }
    }

    public static TerminalSettings load() {
        return load(System.getenv());
    }

    static TerminalSettings load(Map<String, String> environment) {
        String username = environment.getOrDefault("CILEXEC_TERMINAL_USERNAME", "local");
        String path = environment.getOrDefault("CILEXEC_TERMINAL_PASSWORD_FILE",
                "/run/secrets/cilexec_terminal_password");
        try {
            return new TerminalSettings(username, Path.of(path));
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("Invalid terminal password file path", invalid);
        }
    }
}
