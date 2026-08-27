package com.follarce.application;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Disposable interactive viewport state supplied by host adapters.
 *
 * <p>It is presentation metadata only and never participates in continuation persistence or
 * process recovery. Missing or stale updates fall back to a stable conventional viewport.
 */
public final class InteractionViewport {
    private static final AtomicReference<Size> DEFAULT = new AtomicReference<>(environment());
    private static final ConcurrentHashMap<UUID, Size> BY_OWNER = new ConcurrentHashMap<>();

    private InteractionViewport() { }

    public static Size current() {
        return DEFAULT.get();
    }

    public static Size current(UUID ownerId) {
        return BY_OWNER.getOrDefault(java.util.Objects.requireNonNull(ownerId, "ownerId"), current());
    }

    public static void update(UUID ownerId, Size size) {
        BY_OWNER.put(java.util.Objects.requireNonNull(ownerId, "ownerId"),
                java.util.Objects.requireNonNull(size, "size"));
    }

    public static void updateDefault(Size size) {
        DEFAULT.set(java.util.Objects.requireNonNull(size, "size"));
    }

    private static Size environment() {
        Map<String, String> environment = System.getenv();
        try {
            int width = Integer.parseInt(environment.getOrDefault("COLUMNS", ""));
            int height = Integer.parseInt(environment.getOrDefault("LINES", ""));
            if (width > 0 && height > 0) return new Size(width, height);
        } catch (NumberFormatException ignored) {
            // Detached processes use a stable conventional interactive viewport.
        }
        return new Size(80, 24);
    }

    public record Size(int width, int height) {
        public Size {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("Interactive viewport dimensions must be positive");
            }
        }
    }
}
