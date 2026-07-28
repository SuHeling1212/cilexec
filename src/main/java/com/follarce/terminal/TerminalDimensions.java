package com.follarce.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Process-wide snapshot of the one attached terminal's current character dimensions. */
public final class TerminalDimensions {
    private static final int DEFAULT_WIDTH = 80;
    private static final int DEFAULT_HEIGHT = 24;
    private static final AtomicReference<Size> CURRENT = new AtomicReference<>(environment());
    private static final AtomicLong LAST_REFRESH_NANOS = new AtomicLong(Long.MIN_VALUE);
    private static final long REFRESH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private TerminalDimensions() {
    }

    public static Size current() {
        return CURRENT.get();
    }

    /** Refreshes from the attached TTY; unsupported or detached terminals retain the last size. */
    public static Size refresh() {
        long now = System.nanoTime();
        long previous = LAST_REFRESH_NANOS.get();
        if (previous != Long.MIN_VALUE && now - previous < REFRESH_INTERVAL_NANOS) {
            return current();
        }
        if (!LAST_REFRESH_NANOS.compareAndSet(previous, now)) return current();
        queryTty().ifPresent(CURRENT::set);
        return current();
    }

    static Optional<Size> parse(String value) {
        if (value == null) return Optional.empty();
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 2) return Optional.empty();
        try {
            int height = Integer.parseInt(parts[0]);
            int width = Integer.parseInt(parts[1]);
            return width > 0 && height > 0
                    ? Optional.of(new Size(width, height)) : Optional.empty();
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<Size> queryTty() {
        Process process = null;
        try {
            process = new ProcessBuilder("stty", "size")
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) return Optional.empty();
            return parse(new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.US_ASCII));
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static Size environment() {
        Map<String, String> environment = System.getenv();
        try {
            int width = Integer.parseInt(environment.getOrDefault("COLUMNS", ""));
            int height = Integer.parseInt(environment.getOrDefault("LINES", ""));
            if (width > 0 && height > 0) return new Size(width, height);
        } catch (NumberFormatException ignored) {
            // Detached processes use a stable conventional terminal size.
        }
        return new Size(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    public record Size(int width, int height) {
        public Size {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("Terminal dimensions must be positive");
            }
        }
    }
}
