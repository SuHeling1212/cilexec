package com.follarce.terminal;

import com.follarce.application.InteractionViewport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

/** Per-user terminal sizes supplied by lightweight clients, with a local-console fallback. */
public final class TerminalDimensions {
    private static final AtomicLong LAST_REFRESH_NANOS = new AtomicLong(Long.MIN_VALUE);
    private static final long REFRESH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private TerminalDimensions() {
    }

    public static Size current() {
        return from(InteractionViewport.current());
    }

    public static Size current(UUID ownerId) {
        return from(InteractionViewport.current(ownerId));
    }

    public static void update(UUID ownerId, Size size) {
        InteractionViewport.update(ownerId, toViewport(size));
    }

    /** Refreshes from the attached TTY; unsupported or detached terminals retain the last size. */
    public static Size refresh() {
        long now = System.nanoTime();
        long previous = LAST_REFRESH_NANOS.get();
        if (previous != Long.MIN_VALUE && now - previous < REFRESH_INTERVAL_NANOS) {
            return current();
        }
        if (!LAST_REFRESH_NANOS.compareAndSet(previous, now)) return current();
        queryTty().ifPresent(size -> InteractionViewport.updateDefault(toViewport(size)));
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

    private static Size from(InteractionViewport.Size size) {
        return new Size(size.width(), size.height());
    }

    private static InteractionViewport.Size toViewport(Size size) {
        return new InteractionViewport.Size(size.width(), size.height());
    }

    public record Size(int width, int height) {
        public Size {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("Terminal dimensions must be positive");
            }
        }
    }
}
