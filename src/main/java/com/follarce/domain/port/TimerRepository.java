package com.follarce.domain.port;

import com.follarce.domain.timer.ProcessTimer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TimerRepository {
    void save(ProcessTimer timer);

    Optional<ProcessTimer> findById(UUID timerId);

    List<ProcessTimer> claimDue(UUID runnerId, Instant now, int limit);

    default Optional<Instant> nextScheduledWakeAt() {
        return Optional.empty();
    }

    boolean update(ProcessTimer timer, ProcessTimer.Status expectedStatus);

    /** Removes fired timer rows older than {@code before}; returns the number removed. */
    default int deleteFiredExpired(Instant before) {
        return 0;
    }

    /** Removes every timer row owned by {@code processUid}; returns the number removed. */
    default int deleteForProcess(UUID processUid) {
        return 0;
    }
}
