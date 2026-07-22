package com.follarce.domain.ipc;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable process subscription to either a channel or a topic. */
public record IpcSubscription(
        UUID subscriptionId,
        UUID ownerId,
        UUID subscriberProcessUid,
        SourceKind sourceKind,
        Optional<UUID> channelId,
        Optional<UUID> topicId,
        Status status,
        Instant createdAt,
        Optional<Instant> cancelledAt
) {
    public IpcSubscription {
        Invariant.required(subscriptionId, "subscriptionId");
        Invariant.required(ownerId, "ownerId");
        Invariant.required(subscriberProcessUid, "subscriberProcessUid");
        Invariant.required(sourceKind, "sourceKind");
        channelId = Invariant.required(channelId, "channelId");
        topicId = Invariant.required(topicId, "topicId");
        Invariant.check((sourceKind == SourceKind.CHANNEL && channelId.isPresent() && topicId.isEmpty())
                        || (sourceKind == SourceKind.TOPIC && topicId.isPresent() && channelId.isEmpty()),
                "subscription must identify exactly one source of its declared kind");
        Invariant.required(status, "status");
        Invariant.required(createdAt, "createdAt");
        cancelledAt = Invariant.required(cancelledAt, "cancelledAt");
        Invariant.check((status == Status.CANCELLED) == cancelledAt.isPresent(),
                "cancelled subscription status and timestamp must agree");
    }

    public enum SourceKind { CHANNEL, TOPIC }

    public enum Status { ACTIVE, PAUSED, CANCELLED }
}
