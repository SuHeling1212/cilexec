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

    /** Cancels outstanding timers for {@code processUid}; historical timer rows are retained. */
    default int cancelForProcess(UUID processUid) {
        return 0;
    }
}
