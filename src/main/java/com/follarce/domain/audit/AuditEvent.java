package com.follarce.domain.audit;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable structured security and externally visible audit event. */
public record AuditEvent(
        UUID eventId,
        ActorType actorType,
        String actorId,
        String action,
        String resourceType,
        String resourceId,
        Result result,
        Map<String, String> details,
        Instant createdAt
) {
    public AuditEvent {
        Invariant.required(eventId, "eventId");
        Invariant.required(actorType, "actorType");
        actorId = Invariant.text(actorId, "actorId");
        action = Invariant.text(action, "action");
        resourceType = Invariant.text(resourceType, "resourceType");
        resourceId = Invariant.text(resourceId, "resourceId");
        Invariant.required(result, "result");
        details = stringDetails(details);
        Invariant.required(createdAt, "createdAt");
    }

    private static Map<String, String> stringDetails(Map<String, String> values) {
        Invariant.required(values, "details");
        Map<String, String> checked = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) values).entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("details keys must be strings");
            }
            if (!(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException("details values must be strings");
            }
            checked.put(key, value);
        }
        return Map.copyOf(checked);
    }

    public enum ActorType {
        USER,
        RUNTIME,
        EFFECT_WORKER,
        ADMINISTRATOR,
        SYSTEM
    }

    public enum Result {
        SUCCEEDED,
        DENIED,
        FAILED
    }
}
