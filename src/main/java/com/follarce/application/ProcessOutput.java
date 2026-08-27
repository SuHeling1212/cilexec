package com.follarce.application;

import java.util.UUID;

/**
 * Disposable presentation hint emitted only after its enclosing durable slice commits.
 *
 * <p>This is deliberately not an outbox and provides no reliable-delivery guarantee. Any
 * output that requires replay or exactly-once delivery must first be represented by durable
 * process state, an effect, or another durable outbox entry.
 */
public record ProcessOutput(UUID routeId, Kind kind, String text) {
    public enum Kind { INTERACTION_FRAME }

    public ProcessOutput {
        java.util.Objects.requireNonNull(routeId, "routeId");
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(text, "text");
    }

    public static ProcessOutput interactionFrame(UUID routeId, String text) {
        return new ProcessOutput(routeId, Kind.INTERACTION_FRAME, text);
    }
}
