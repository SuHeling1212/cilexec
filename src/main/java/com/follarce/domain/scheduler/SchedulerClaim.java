package com.follarce.domain.scheduler;

import com.follarce.domain.Invariant;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Lease proving that one worker owns a specific execution epoch. */
public record SchedulerClaim(
        UUID processUid,
        UUID ownerId,
        UUID runnerId,
        UUID bootId,
        long executionEpoch,
        Instant claimedAt,
        Instant heartbeatAt,
        Instant expiresAt
) {
    public SchedulerClaim {
        Invariant.required(processUid, "processUid");
        Invariant.required(ownerId, "ownerId");
        Invariant.required(runnerId, "runnerId");
        Invariant.required(bootId, "bootId");
        Invariant.positive(executionEpoch, "executionEpoch");
        Invariant.required(claimedAt, "claimedAt");
        Invariant.required(heartbeatAt, "heartbeatAt");
        Invariant.required(expiresAt, "expiresAt");
        Invariant.check(!heartbeatAt.isBefore(claimedAt),
                "heartbeat must not precede claim");
        Invariant.check(expiresAt.isAfter(heartbeatAt),
                "lease expiry must follow heartbeat");
    }

    public boolean isExpiredAt(Instant now) {
        Invariant.required(now, "now");
        return !now.isBefore(expiresAt);
    }

    public boolean authorizes(long epoch, Instant now) {
        return executionEpoch == epoch && !isExpiredAt(now);
    }

    public SchedulerClaim heartbeat(Instant now, Duration extension) {
        Invariant.required(now, "now");
        Invariant.required(extension, "extension");
        Invariant.check(!extension.isNegative() && !extension.isZero(),
                "lease extension must be positive");
        if (isExpiredAt(now)) {
            throw new IllegalStateException("expired lease cannot be renewed");
        }
        Invariant.check(!now.isBefore(heartbeatAt), "heartbeat time must move forward");
        return new SchedulerClaim(processUid, ownerId, runnerId, bootId, executionEpoch,
                claimedAt, now, now.plus(extension));
    }
}
