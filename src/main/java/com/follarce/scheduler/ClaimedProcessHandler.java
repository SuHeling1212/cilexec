package com.follarce.scheduler;

import com.follarce.domain.scheduler.SchedulerClaim;

@FunctionalInterface
public interface ClaimedProcessHandler {
    /** Executes one scheduling slice: terminal processes run at most 4096 FCL interpreter
     *  steps or 20 ms before a slice boundary, while all other processes run one interpreter
     *  step. The whole slice commits in a single transaction; only READY processes re-queue. */
    void executeSlice(SchedulerClaim claim);
}
