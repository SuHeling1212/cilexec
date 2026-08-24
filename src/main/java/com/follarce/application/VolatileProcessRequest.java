package com.follarce.application;

import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclContinuationCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One not-yet-started in-memory process request, retained only until its parent commits. */
record VolatileProcessRequest(UUID ownerId, UUID parentProcessUid, UUID taskId,
                              FclProgram program, List<Object> arguments, String sourcePath) {
    private static final FclContinuationCodec VALUES = new FclContinuationCodec();

    VolatileProcessRequest {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(parentProcessUid, "parentProcessUid");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(arguments, "arguments");
        List<Object> copied = new ArrayList<>(arguments.size());
        arguments.forEach(value -> copied.add(copyValue(value)));
        arguments = java.util.Collections.unmodifiableList(copied);
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("sourcePath is required");
        }
    }

    static Object copyValue(Object value) {
        return VALUES.valueFromJson(VALUES.valueToJson(value));
    }
}
