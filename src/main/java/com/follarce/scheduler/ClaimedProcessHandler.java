package com.follarce.scheduler;

import com.follarce.domain.scheduler.SchedulerClaim;

@FunctionalInterface
public interface ClaimedProcessHandler {
    /** Executes and commits at most one FCL semantic statement. */
    void executeOne(SchedulerClaim claim);
}
