package com.follarce.domain.audit;

import com.follarce.domain.Invariant;

import java.time.Instant;

/** Fixed-duration retention rule matched exactly against an audit action. */
public record AuditRetentionPolicy(
        String eventType,
        long retainForSeconds,
        boolean enabled,
        Instant updatedAt
) {
    public AuditRetentionPolicy {
        eventType = Invariant.text(eventType, "eventType");
        Invariant.positive(retainForSeconds, "retainForSeconds");
        Invariant.required(updatedAt, "updatedAt");
    }
}
