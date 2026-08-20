package com.follarce.fcl;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, runtime-native FCL exception value. It deliberately has no object id and is not
 * part of the ordinary {@link FclObjectValue} value model.
 */
public record FclExceptionValue(String type, String message, List<FclStackFrame> stack) {
    public FclExceptionValue {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        stack = List.copyOf(stack);
    }

    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder(type).append(": ").append(message);
        for (FclStackFrame frame : stack) {
            rendered.append("\n    at ").append(frame.function()).append(" (")
                    .append(frame.source()).append(':').append(frame.line()).append(':')
                    .append(frame.column()).append(')');
        }
        return rendered.toString();
    }
}
