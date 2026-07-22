package com.follarce.domain.ipc;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Named fan-out topic. */
public record IpcTopic(
        UUID topicId,
        UUID ownerId,
        String name,
        Status status,
        Instant createdAt,
        Optional<Instant> closedAt
) {
    public IpcTopic {
        Invariant.required(topicId, "topicId");
        Invariant.required(ownerId, "ownerId");
        name = Invariant.text(name, "name");
        Invariant.required(status, "status");
        Invariant.required(createdAt, "createdAt");
        closedAt = Invariant.required(closedAt, "closedAt");
        Invariant.check((status == Status.CLOSED) == closedAt.isPresent(),
                "closed topic status and timestamp must agree");
    }

    public enum Status { ACTIVE, CLOSED }
}
