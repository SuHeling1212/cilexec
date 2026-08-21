package com.follarce.scheduler;

import com.follarce.domain.scheduler.SchedulerClaim;

@FunctionalInterface
public interface ClaimedProcessHandler {
    /** Executes one scheduling slice: every process runs at most 4096 FCL interpreter steps
     *  or 20 ms before a slice boundary. The whole slice commits in a single transaction;
     *  only READY processes re-queue. */
    void executeSlice(SchedulerClaim claim);
}
