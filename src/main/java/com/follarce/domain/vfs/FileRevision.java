package com.follarce.domain.vfs;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.UUID;

/** One durable pointer in the optional history of a versioned file node. */
public record FileRevision(
        UUID revisionId,
        UUID nodeId,
        UUID ownerId,
        long revisionNumber,
        ObjectHash objectHash,
        UUID createdBy,
        Instant createdAt
) {
    public FileRevision {
        Invariant.required(revisionId, "revisionId");
        Invariant.required(nodeId, "nodeId");
        Invariant.required(ownerId, "ownerId");
        Invariant.positive(revisionNumber, "revisionNumber");
        Invariant.required(objectHash, "objectHash");
        Invariant.required(createdBy, "createdBy");
        Invariant.required(createdAt, "createdAt");
    }
}
