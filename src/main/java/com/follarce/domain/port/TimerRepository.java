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

    boolean update(ProcessTimer timer, ProcessTimer.Status expectedStatus);
}
