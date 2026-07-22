package com.follarce.domain.packageinfo;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** User-owned package dependency environment. */
public record PackageEnvironment(
        UUID environmentId,
        UUID ownerId,
        String name,
        Optional<UUID> parentEnvironmentId,
        Status status,
        Instant createdAt
) {
    public PackageEnvironment {
        Invariant.required(environmentId, "environmentId");
        Invariant.required(ownerId, "ownerId");
        name = Invariant.text(name, "name");
        parentEnvironmentId = Invariant.required(parentEnvironmentId, "parentEnvironmentId");
        Invariant.check(parentEnvironmentId.isEmpty()
                        || !parentEnvironmentId.get().equals(environmentId),
                "environment cannot be its own parent");
        Invariant.required(status, "status");
        Invariant.required(createdAt, "createdAt");
    }

    public enum Status {
        ACTIVE,
        ARCHIVED
    }
}
