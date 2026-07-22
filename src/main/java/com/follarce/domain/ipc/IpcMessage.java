package com.follarce.domain.ipc;

import com.follarce.domain.Invariant;
import com.follarce.domain.vfs.ObjectHash;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Immutable IPC message; receiver-specific state lives in deliveries. */
public record IpcMessage(
        UUID messageId,
        Optional<UUID> senderProcessUid,
        Kind kind,
        Optional<UUID> channelId,
        Optional<String> topicName,
        String payloadType,
        Optional<String> payloadJson,
        Optional<ObjectHash> payloadObjectHash,
        Instant createdAt,
        Optional<Instant> expiresAt
) {
    public IpcMessage {
        Invariant.required(messageId, "messageId");
        senderProcessUid = Invariant.required(senderProcessUid, "senderProcessUid");
        Invariant.required(kind, "kind");
        channelId = Invariant.required(channelId, "channelId");
        topicName = Invariant.required(topicName, "topicName");
        topicName = topicName.map(value -> Invariant.text(value, "topicName"));
        payloadType = Invariant.text(payloadType, "payloadType");
        payloadJson = Invariant.required(payloadJson, "payloadJson");
        payloadObjectHash = Invariant.required(payloadObjectHash, "payloadObjectHash");
        Invariant.check(payloadJson.isPresent() ^ payloadObjectHash.isPresent(),
                "message must contain exactly one payload representation");
        Invariant.required(createdAt, "createdAt");
        expiresAt = Invariant.required(expiresAt, "expiresAt");
        expiresAt.ifPresent(expiry -> Invariant.check(expiry.isAfter(createdAt),
                "message expiry must follow creation"));
        switch (kind) {
            case DIRECT -> Invariant.check(channelId.isEmpty() && topicName.isEmpty(),
                    "direct message cannot name a channel or topic");
            case CHANNEL -> Invariant.check(channelId.isPresent() && topicName.isEmpty(),
                    "channel message requires only a channel ID");
            case TOPIC, BROADCAST -> Invariant.check(topicName.isPresent() && channelId.isEmpty(),
                    "topic message requires only a topic name");
        }
    }

    public boolean isExpiredAt(Instant now) {
        Invariant.required(now, "now");
        return expiresAt.map(expiry -> !now.isBefore(expiry)).orElse(false);
    }

    public enum Kind {
        DIRECT,
        CHANNEL,
        TOPIC,
        BROADCAST
    }
}
