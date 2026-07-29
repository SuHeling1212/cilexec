package com.follarce.terminal;

import com.follarce.auth.UsernamePolicy;

import java.util.Map;

/** Host-terminal identity is fixed by deployment, never selected by untrusted FCL input. */
public record TerminalSettings(String username, int port) {
    public static final int DEFAULT_PORT = 8022;

    public TerminalSettings(String username) {
        this(username, DEFAULT_PORT);
    }

    public TerminalSettings {
        username = UsernamePolicy.normalize(username);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Terminal port is outside 1..65535");
        }
    }

    public static TerminalSettings load() {
        return load(System.getenv());
    }

    static TerminalSettings load(Map<String, String> environment) {
        String username = environment.getOrDefault("CILEXEC_TERMINAL_USERNAME", "local");
        String rawPort = environment.getOrDefault("CILEXEC_TERMINAL_PORT",
                Integer.toString(DEFAULT_PORT));
        try {
            return new TerminalSettings(username, Integer.parseInt(rawPort));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("CILEXEC_TERMINAL_PORT must be an integer",
                    invalid);
        }
    }
}
