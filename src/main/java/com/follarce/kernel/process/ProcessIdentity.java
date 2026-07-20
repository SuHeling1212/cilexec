package com.follarce.kernel.process;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.follarce.kernel.Constants;

/** Persistent identity and execution defaults for process snapshots. */
public final class ProcessIdentity {
    private ProcessIdentity() {}

    @SuppressWarnings("unchecked")
    public static boolean ensureDefaults(Map<String, Object> process) {
        boolean changed = false;
        Object generation = process.get("ProcessGeneration");
        if (!(generation instanceof String) || ((String) generation).isBlank()) {
            process.put("ProcessGeneration", UUID.randomUUID().toString());
            changed = true;
        }
        Object owner = process.get("Owner");
        String ownerName = owner instanceof String ? owner.toString() : Constants.DEFAULT_USER_LOCAL;
        if (!(process.get("EffectiveUser") instanceof String)) {
            process.put("EffectiveUser", ownerName);
            changed = true;
        }
        if (!(process.get("PathAliases") instanceof Map)) {
            process.put("PathAliases", new LinkedHashMap<String, String>());
            changed = true;
        }
        Object executionObject = process.get("Execution");
        Map<String, Object> execution;
        if (executionObject instanceof Map) {
            execution = (Map<String, Object>) executionObject;
        } else {
            execution = new LinkedHashMap<>();
            process.put("Execution", execution);
            changed = true;
        }
        if (!(execution.get("NextAttemptOrdinal") instanceof Number)) {
            execution.put("NextAttemptOrdinal", 0L);
            changed = true;
        }
        return changed;
    }

    public static String generation(Map<String, Object> process) {
        Object value = process.get("ProcessGeneration");
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw new IllegalStateException("ProcessGeneration is missing");
        }
        return value.toString();
    }

    public static String newGeneration() {
        return UUID.randomUUID().toString();
    }
}
