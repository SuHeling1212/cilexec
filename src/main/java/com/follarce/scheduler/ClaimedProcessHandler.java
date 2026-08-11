package com.follarce.scheduler;

import com.follarce.domain.scheduler.SchedulerClaim;

@FunctionalInterface
public interface ClaimedProcessHandler {
    /** Executes one scheduling slice: for terminal processes, at most 4096 FCL steps or
     *  20 ms of pure computation, then persists and re-queues; all other processes run
     *  one statement per slice. The whole slice commits in a single transaction. */
    void executeSlice(SchedulerClaim claim);
}
