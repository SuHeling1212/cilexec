package com.follarce.domain.process;

import com.follarce.domain.Invariant;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable process-control signal, independent of the transport that requested it. */
public record ProcessInterrupt(UUID processUid, Instant requestedAt, Optional<Instant> handledAt) {
    public ProcessInterrupt {
        Invariant.required(processUid, "processUid");
        Invariant.required(requestedAt, "requestedAt");
        handledAt = Invariant.required(handledAt, "handledAt");
        handledAt.ifPresent(at -> Invariant.check(!at.isBefore(requestedAt),
                "interrupt handling must not precede request"));
    }

    public ProcessInterrupt handled(Instant at) {
        if (handledAt.isPresent()) throw new IllegalStateException("interrupt is already handled");
        return new ProcessInterrupt(processUid, requestedAt,
                Optional.of(Invariant.required(at, "at")));
    }
}
