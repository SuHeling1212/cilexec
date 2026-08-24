package com.follarce.application;

import java.util.UUID;

/** Terminal-only display frame published after the enclosing durable slice commits. */
public record TerminalFrame(UUID routeId, String text) {
    public TerminalFrame {
        java.util.Objects.requireNonNull(routeId, "routeId");
        java.util.Objects.requireNonNull(text, "text");
    }
}
