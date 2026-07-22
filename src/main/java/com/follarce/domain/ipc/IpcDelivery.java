package com.follarce.domain.ipc;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Receiver-specific exactly-once delivery state. */
public record IpcDelivery(
        UUID deliveryId,
        UUID messageId,
        UUID receiverProcessUid,
        Status status,
        Optional<UUID> reservedBy,
        Optional<Instant> reservedAt,
        Optional<Instant> consumedAt,
        Optional<Instant> failedAt,
        Optional<String> failureReason
) {
    public IpcDelivery {
        Invariant.required(deliveryId, "deliveryId");
        Invariant.required(messageId, "messageId");
        Invariant.required(receiverProcessUid, "receiverProcessUid");
        Invariant.required(status, "status");
        reservedBy = Invariant.required(reservedBy, "reservedBy");
        reservedAt = Invariant.required(reservedAt, "reservedAt");
        consumedAt = Invariant.required(consumedAt, "consumedAt");
        failedAt = Invariant.required(failedAt, "failedAt");
        failureReason = Invariant.required(failureReason, "failureReason")
                .map(value -> Invariant.text(value, "failureReason"));
        validateState(status, reservedBy, reservedAt, consumedAt, failedAt, failureReason);
    }

    public static IpcDelivery pending(UUID deliveryId, UUID messageId, UUID receiverProcessUid) {
        return new IpcDelivery(deliveryId, messageId, receiverProcessUid, Status.PENDING,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    public IpcDelivery reserve(UUID workerId, Instant at) {
        requireStatus(Status.PENDING);
        return new IpcDelivery(deliveryId, messageId, receiverProcessUid, Status.RESERVED,
                Optional.of(Invariant.required(workerId, "workerId")),
                Optional.of(Invariant.required(at, "at")), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    public IpcDelivery releaseReservation() {
        requireStatus(Status.RESERVED);
        return pending(deliveryId, messageId, receiverProcessUid);
    }

    public IpcDelivery consume(Instant at) {
        requireStatus(Status.RESERVED);
        Invariant.required(at, "at");
        Invariant.check(!at.isBefore(reservedAt.orElseThrow()),
                "consumption must not precede reservation");
        return new IpcDelivery(deliveryId, messageId, receiverProcessUid, Status.CONSUMED,
                reservedBy, reservedAt, Optional.of(at), Optional.empty(), Optional.empty());
    }

    public IpcDelivery fail(Instant at, String reason) {
        requireStatus(Status.RESERVED);
        Invariant.required(at, "at");
        Invariant.check(!at.isBefore(reservedAt.orElseThrow()),
                "failure must not precede reservation");
        return new IpcDelivery(deliveryId, messageId, receiverProcessUid, Status.FAILED,
                reservedBy, reservedAt, Optional.empty(), Optional.of(at), Optional.of(reason));
    }

    public IpcDelivery dead(Instant at, String reason) {
        if (status != Status.PENDING && status != Status.RESERVED && status != Status.FAILED) {
            throw new IllegalStateException("only unfinished delivery can become DEAD");
        }
        return new IpcDelivery(deliveryId, messageId, receiverProcessUid, Status.DEAD,
                reservedBy, reservedAt, Optional.empty(), Optional.of(Invariant.required(at, "at")),
                Optional.of(reason));
    }

    public boolean isTerminal() {
        return status == Status.CONSUMED || status == Status.DEAD;
    }

    private void requireStatus(Status required) {
        if (status != required) {
            throw new IllegalStateException("delivery is " + status + ", expected " + required);
        }
    }

    private static void validateState(
            Status status,
            Optional<UUID> reservedBy,
            Optional<Instant> reservedAt,
            Optional<Instant> consumedAt,
            Optional<Instant> failedAt,
            Optional<String> failureReason
    ) {
        Invariant.check(reservedBy.isPresent() == reservedAt.isPresent(),
                "reservation owner and time must appear together");
        switch (status) {
            case PENDING -> Invariant.check(reservedBy.isEmpty() && consumedAt.isEmpty()
                            && failedAt.isEmpty() && failureReason.isEmpty(),
                    "pending delivery cannot contain outcome data");
            case RESERVED -> Invariant.check(reservedBy.isPresent() && consumedAt.isEmpty()
                            && failedAt.isEmpty() && failureReason.isEmpty(),
                    "reserved delivery requires only reservation data");
            case CONSUMED -> Invariant.check(reservedBy.isPresent() && consumedAt.isPresent()
                            && failedAt.isEmpty() && failureReason.isEmpty(),
                    "consumed delivery requires reservation and consumption time");
            case FAILED -> Invariant.check(reservedBy.isPresent() && consumedAt.isEmpty()
                            && failedAt.isPresent() && failureReason.isPresent(),
                    "failed delivery requires reservation and failure details");
            case DEAD -> Invariant.check(consumedAt.isEmpty() && failedAt.isPresent()
                            && failureReason.isPresent(),
                    "dead delivery requires failure details");
        }
    }

    public enum Status {
        PENDING,
        RESERVED,
        CONSUMED,
        FAILED,
        DEAD
    }
}
