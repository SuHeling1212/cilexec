package com.follarce.fcl;

import java.util.Objects;

/** Immutable, runtime-native source location recorded when an FCL exception is created. */
public record FclStackFrame(String function, String source, long line, long column) {
    public FclStackFrame {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(source, "source");
        if (line < 1) throw new IllegalArgumentException("Stack frame line must be positive");
        if (column < 1) throw new IllegalArgumentException("Stack frame column must be positive");
    }
}
