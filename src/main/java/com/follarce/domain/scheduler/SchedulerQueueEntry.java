package com.follarce.domain.scheduler;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.UUID;

/** FIFO scheduler entry ordered by enqueue time and stable process identity. */
public record SchedulerQueueEntry(
        UUID processUid,
        Instant readyAt,
        Instant enqueuedAt,
        Status status
) implements Comparable<SchedulerQueueEntry> {
    public SchedulerQueueEntry {
        Invariant.required(processUid, "processUid");
        Invariant.required(readyAt, "readyAt");
        Invariant.required(enqueuedAt, "enqueuedAt");
        Invariant.required(status, "status");
    }

    public boolean claimableAt(Instant now) {
        Invariant.required(now, "now");
        return status == Status.READY && !readyAt.isAfter(now);
    }

    public SchedulerQueueEntry claimed() {
        if (status != Status.READY) {
            throw new IllegalStateException("only READY entries can be claimed");
        }
        return new SchedulerQueueEntry(processUid, readyAt, enqueuedAt, Status.CLAIMED);
    }

    @Override
    public int compareTo(SchedulerQueueEntry other) {
        int byTime = enqueuedAt.compareTo(other.enqueuedAt);
        return byTime != 0 ? byTime : processUid.compareTo(other.processUid);
    }

    public enum Status {
        READY,
        CLAIMED
    }
}
