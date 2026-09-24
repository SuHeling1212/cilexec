package com.follarce.domain.port;

import com.follarce.domain.scheduler.SchedulerClaim;
import com.follarce.domain.scheduler.SchedulerQueueEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SchedulerRepository {
    void enqueue(SchedulerQueueEntry entry);

    Optional<SchedulerClaim> claimNext(
            UUID runnerId,
            UUID bootId,
            Instant now,
            Duration leaseDuration
    );

    /** Claims only a process fenced from normal workers by a durable Ctrl+C request. */
    default Optional<SchedulerClaim> claimInterrupted(
            UUID runnerId,
            UUID bootId,
            Instant now,
            Duration leaseDuration
    ) {
        return Optional.empty();
    }

    boolean heartbeat(SchedulerClaim claim);

    void release(UUID processUid, long executionEpoch);

    /**
     * Gives back a claim whose handler failed before committing a slice. Implementations must
     * match the complete lease identity and execution epoch before changing RUNNING to READY;
     * a stale worker therefore cannot revive or requeue a process owned by a newer claim.
     */
    default boolean abandon(SchedulerClaim claim, Instant now, Instant readyAt) {
        return false;
    }

    int releaseExpired(Instant now);

    default Optional<Instant> nextLeaseExpiry() {
        return Optional.empty();
    }

    default Optional<Instant> nextReadyAt() {
        return Optional.empty();
    }

    /**
     * Re-announces READY queue rows that have been unclaimed for longer than {@code
     * staleAgeMillis} (lost notification safety net); returns the number announced.
     */
    default int requeueStale(Instant now, long staleAgeMillis) {
        return 0;
    }
}
