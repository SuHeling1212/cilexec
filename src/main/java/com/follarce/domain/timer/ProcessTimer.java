package com.follarce.domain.timer;

import com.follarce.domain.Invariant;
import com.follarce.domain.process.Continuation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable timer whose database row, rather than an in-memory wait, is authoritative. */
public record ProcessTimer(
        UUID timerId,
        UUID processUid,
        Instant wakeAt,
        Status status,
        Instant createdAt,
        Optional<UUID> claimedBy,
        Optional<Instant> claimedAt,
        Optional<Instant> firedAt,
        Optional<Continuation.PersistedValue> payload
) {
    public ProcessTimer {
        Invariant.required(timerId, "timerId");
        Invariant.required(processUid, "processUid");
        Invariant.required(wakeAt, "wakeAt");
        Invariant.required(status, "status");
        Invariant.required(createdAt, "createdAt");
        claimedBy = Invariant.required(claimedBy, "claimedBy");
        claimedAt = Invariant.required(claimedAt, "claimedAt");
        firedAt = Invariant.required(firedAt, "firedAt");
        payload = Invariant.required(payload, "payload");
        Invariant.check(claimedBy.isPresent() == claimedAt.isPresent(),
                "timer claim owner and time must appear together");
        switch (status) {
            case SCHEDULED -> Invariant.check(claimedBy.isEmpty() && firedAt.isEmpty(),
                    "scheduled timer cannot contain claim or fire data");
            case CLAIMED -> Invariant.check(claimedBy.isPresent() && firedAt.isEmpty(),
                    "claimed timer requires claim data only");
            case FIRED -> Invariant.check(claimedBy.isPresent() && firedAt.isPresent(),
                    "fired timer requires claim and fire data");
            case CANCELLED -> Invariant.check(firedAt.isEmpty(),
                    "cancelled timer cannot have fired");
        }
    }

    public boolean isDueAt(Instant now) {
        Invariant.required(now, "now");
        return status == Status.SCHEDULED && !wakeAt.isAfter(now);
    }

    public ProcessTimer claim(UUID runnerId, Instant at) {
        if (!isDueAt(at)) {
            throw new IllegalStateException("timer is not due and scheduled");
        }
        return new ProcessTimer(timerId, processUid, wakeAt, Status.CLAIMED, createdAt,
                Optional.of(Invariant.required(runnerId, "runnerId")), Optional.of(at),
                Optional.empty(), payload);
    }

    public ProcessTimer fire(Instant at) {
        if (status != Status.CLAIMED) {
            throw new IllegalStateException("only claimed timer can fire");
        }
        Invariant.check(!at.isBefore(claimedAt.orElseThrow()),
                "timer fire must not precede claim");
        return new ProcessTimer(timerId, processUid, wakeAt, Status.FIRED, createdAt,
                claimedBy, claimedAt, Optional.of(at), payload);
    }

    public ProcessTimer cancel() {
        if (status == Status.FIRED || status == Status.CANCELLED) {
            throw new IllegalStateException("completed timer cannot be cancelled");
        }
        return new ProcessTimer(timerId, processUid, wakeAt, Status.CANCELLED, createdAt,
                claimedBy, claimedAt, Optional.empty(), payload);
    }

    public enum Status {
        SCHEDULED,
        CLAIMED,
        FIRED,
        CANCELLED
    }
}
