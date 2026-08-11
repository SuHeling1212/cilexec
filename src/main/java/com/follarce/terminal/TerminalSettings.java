package com.follarce.terminal;

import com.follarce.auth.UsernamePolicy;

import java.time.Duration;
import java.util.Map;

/** Host-terminal identity is fixed by deployment, never selected by untrusted FCL input. */
public record TerminalSettings(String username, int port, Duration idleDisconnect) {
    public static final int DEFAULT_PORT = 8022;
    /**
     * A session whose attached process has been PAUSED (the REPL is idle, no task is
     * running) for this long is closed. Active processes, full-screen programs waiting on
     * input, and any session that recently accepted input are never closed for idleness.
     */
    public static final Duration DEFAULT_IDLE_DISCONNECT = Duration.ofMinutes(60);

    public TerminalSettings(String username) {
        this(username, DEFAULT_PORT, DEFAULT_IDLE_DISCONNECT);
    }

    public TerminalSettings(String username, int port) {
        this(username, port, DEFAULT_IDLE_DISCONNECT);
    }

    public TerminalSettings {
        username = UsernamePolicy.normalize(username);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Terminal port is outside 1..65535");
        }
        if (idleDisconnect == null || idleDisconnect.isNegative() || idleDisconnect.isZero()) {
            throw new IllegalArgumentException("Idle disconnect must be positive");
        }
    }

    public static TerminalSettings load() {
        return load(System.getenv());
    }

    static TerminalSettings load(Map<String, String> environment) {
        String username = environment.getOrDefault("CILEXEC_TERMINAL_USERNAME", "local");
        String rawPort = environment.getOrDefault("CILEXEC_TERMINAL_PORT",
                Integer.toString(DEFAULT_PORT));
        String rawIdle = environment.getOrDefault("CILEXEC_TERMINAL_IDLE_MINUTES", "60");
        try {
            return new TerminalSettings(username, Integer.parseInt(rawPort),
                    Duration.ofMinutes(Long.parseLong(rawIdle)));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "CILEXEC_TERMINAL_PORT and CILEXEC_TERMINAL_IDLE_MINUTES must be integers",
                    invalid);
        }
    }
}
