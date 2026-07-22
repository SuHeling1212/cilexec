package com.follarce.domain.port;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.audit.AuditRetentionPolicy;

import java.util.List;
import java.util.Optional;

public interface AuditRepository {
    void append(AuditEvent event);

    List<AuditEvent> findByResource(String resourceType, String resourceId, int limit);

    void saveRetentionPolicy(AuditRetentionPolicy policy);

    Optional<AuditRetentionPolicy> findRetentionPolicy(String eventType);

    int purgeExpired(int limit);
}
