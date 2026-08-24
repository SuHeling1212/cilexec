package com.follarce.application;

import java.util.Objects;

/** In-memory result hand-off from a volatile calculation to its durable caller. */
record VolatileProcessCompletion(VolatileProcessRequest request, boolean successful,
                                 Object value, String failure) {
    VolatileProcessCompletion {
        Objects.requireNonNull(request, "request");
        value = VolatileProcessRequest.copyValue(value);
        if (!successful && (failure == null || failure.isBlank())) {
            throw new IllegalArgumentException("failure is required for an unsuccessful task");
        }
    }

    static VolatileProcessCompletion success(VolatileProcessRequest request, Object value) {
        return new VolatileProcessCompletion(request, true, value, null);
    }

    static VolatileProcessCompletion failure(VolatileProcessRequest request, String failure) {
        return new VolatileProcessCompletion(request, false, null, failure);
    }
}
