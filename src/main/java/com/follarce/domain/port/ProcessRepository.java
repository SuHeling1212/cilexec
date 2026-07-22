package com.follarce.domain.port;

import com.follarce.domain.process.CilProcess;

import java.util.Optional;
import java.util.UUID;

public interface ProcessRepository {
    /** Allocates a positive instance-wide PID that can never be returned again. */
    long allocatePid();

    Optional<CilProcess> findByUid(UUID processUid);

    Optional<CilProcess> findByPid(long pid);

    void insert(CilProcess process);

    UpdateResult update(
            CilProcess process,
            long expectedStateVersion,
            long expectedExecutionEpoch
    );

    enum UpdateResult {
        UPDATED,
        VERSION_CONFLICT,
        EPOCH_FENCED
    }
}
