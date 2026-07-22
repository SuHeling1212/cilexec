package com.follarce.domain.ipc;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Named competing-consumer channel. */
public record IpcChannel(
        UUID channelId,
        UUID ownerId,
        String name,
        Status status,
        Instant createdAt,
        Optional<Instant> closedAt
) {
    public IpcChannel {
        Invariant.required(channelId, "channelId");
        Invariant.required(ownerId, "ownerId");
        name = Invariant.text(name, "name");
        Invariant.required(status, "status");
        Invariant.required(createdAt, "createdAt");
        closedAt = Invariant.required(closedAt, "closedAt");
        Invariant.check((status == Status.CLOSED) == closedAt.isPresent(),
                "closed channel status and timestamp must agree");
    }

    public enum Status { ACTIVE, CLOSED }
}
