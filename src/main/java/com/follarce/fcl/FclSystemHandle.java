package com.follarce.fcl;

import java.util.Map;
import java.util.Objects;

/** A durable identity for a host-managed entity, never a host pointer or authority token. */
public record FclSystemHandle(String kind, String id, Map<String, String> attributes) {
    public FclSystemHandle {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        if (kind.isBlank() || id.isBlank()) throw new IllegalArgumentException("Empty system handle");
    }

    public FclSystemHandle(String kind, String id) {
        this(kind, id, Map.of());
    }

    @Override
    public String toString() {
        return kind + "{" + id + "}";
    }
}
