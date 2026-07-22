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

    boolean heartbeat(SchedulerClaim claim);

    void release(UUID processUid, long executionEpoch);

    int releaseExpired(Instant now);
}
