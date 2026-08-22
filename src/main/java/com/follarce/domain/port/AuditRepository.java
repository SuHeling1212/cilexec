package com.follarce.domain.port;

import com.follarce.domain.audit.AuditEvent;

import java.util.List;
import java.util.UUID;

public interface AuditRepository {
    void append(AuditEvent event);

    List<AuditEvent> findByResource(String resourceType, String resourceId, int limit);

    /**
     * Explicit administrator purge of every audit event created before {@code before};
     * {@code limit} (nullable) bounds rows per invocation. Returns the number removed.
     */
    default int purgeBeforeByAdministrator(UUID administratorId, java.time.Instant before,
                                           Integer limit) {
        throw new UnsupportedOperationException("Audit purge is not implemented");
    }
}
