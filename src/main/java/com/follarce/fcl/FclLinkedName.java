package com.follarce.fcl;

import java.util.Objects;

/** Internal marker for an explicit {@code target link source} relationship. */
final class FclLinkedName {
    private final String source;

    FclLinkedName(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Link source cannot be blank");
        this.source = source;
    }

    String source() {
        return source;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FclLinkedName link && source.equals(link.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source);
    }
}
