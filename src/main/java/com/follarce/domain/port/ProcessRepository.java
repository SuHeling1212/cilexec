package com.follarce.domain.port;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.scheduler.SchedulerClaim;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ProcessRepository {
    /** Allocates a positive instance-wide PID that can never be returned again. */
    long allocatePid();

    Optional<CilProcess> findByUid(UUID processUid);

    Optional<CilProcess> findByPid(long pid);

    /** Lists every process visible to the current principal's PostgreSQL RLS policy. */
    default List<CilProcess> findAll() {
        throw new UnsupportedOperationException("Process listing is not implemented");
    }

    default List<CilProcess> findChildren(UUID parentProcessUid) {
        throw new UnsupportedOperationException("Process child listing is not implemented");
    }

    void insert(CilProcess process);

    UpdateResult update(
            CilProcess process,
            long expectedStateVersion,
            long expectedExecutionEpoch
    );

    /**
     * Commits a scheduler-owned semantic statement only while the database still
     * recognizes the matching control boot and unexpired lease.
     */
    UpdateResult updateClaimed(
            CilProcess process,
            long expectedStateVersion,
            SchedulerClaim claim
    );

    /**
     * Removes every terminal process (TERMINATED, FAILED, FAILED_RECOVERY) and, by
     * cascade, its persisted continuation, variables, timers, events, and package pins.
     * Returns the number of processes removed.
     */
    default int deleteTerminated() {
        throw new UnsupportedOperationException("Terminal process deletion is not implemented");
    }

    /**
     * Removes one terminal process by PID. Returns {@code false} when the process is
     * unknown or is not in a terminal state.
     */
    default boolean deleteTerminatedByPid(long pid) {
        throw new UnsupportedOperationException("Terminal process deletion is not implemented");
    }

    enum UpdateResult {
        UPDATED,
        VERSION_CONFLICT,
        EPOCH_FENCED
    }
}
