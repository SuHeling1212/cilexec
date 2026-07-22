package com.follarce.domain.terminal;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable terminal session and its committed inputs, attachments, and interrupts. */
public record TerminalSession(
        UUID sessionId,
        UUID ownerId,
        Status status,
        long nextInputSequence,
        Instant createdAt,
        Instant lastActivityAt,
        Optional<Instant> closedAt
) {
    public TerminalSession {
        Invariant.required(sessionId, "sessionId");
        Invariant.required(ownerId, "ownerId");
        Invariant.required(status, "status");
        Invariant.positive(nextInputSequence, "nextInputSequence");
        Invariant.required(createdAt, "createdAt");
        Invariant.required(lastActivityAt, "lastActivityAt");
        Invariant.check(!lastActivityAt.isBefore(createdAt),
                "lastActivityAt must not precede creation");
        closedAt = Invariant.required(closedAt, "closedAt");
        Invariant.check((status == Status.CLOSED) == closedAt.isPresent(),
                "closed status and timestamp must agree");
        closedAt.ifPresent(at -> Invariant.check(!at.isBefore(createdAt),
                "closedAt must not precede creation"));
        closedAt.ifPresent(at -> Invariant.check(!at.isBefore(lastActivityAt),
                "closedAt must not precede last activity"));
    }

    public Input commitInput(String text, Instant submittedAt) {
        if (status != Status.OPEN) {
            throw new IllegalStateException("closed terminal cannot accept input");
        }
        return new Input(UUID.randomUUID(), sessionId, nextInputSequence, text, submittedAt,
                Optional.empty(), Optional.empty());
    }

    public TerminalSession advanceAfter(Input input) {
        Invariant.required(input, "input");
        Invariant.check(input.sessionId.equals(sessionId), "input belongs to another session");
        Invariant.check(input.sequence == nextInputSequence,
                "input sequence does not match session cursor");
        return new TerminalSession(sessionId, ownerId, status, nextInputSequence + 1,
                createdAt, later(lastActivityAt, input.submittedAt()), closedAt);
    }

    public TerminalSession close(Instant at) {
        if (status != Status.OPEN) {
            throw new IllegalStateException("terminal is already closed");
        }
        return new TerminalSession(sessionId, ownerId, Status.CLOSED, nextInputSequence,
                createdAt, Invariant.required(at, "at"), Optional.of(at));
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    public record Input(
            UUID inputId,
            UUID sessionId,
            long sequence,
            String committedText,
            Instant submittedAt,
            Optional<UUID> targetProcessUid,
            Optional<Instant> acceptedAt
    ) {
        public Input {
            Invariant.required(inputId, "inputId");
            Invariant.required(sessionId, "sessionId");
            Invariant.positive(sequence, "sequence");
            committedText = Invariant.required(committedText, "committedText");
            Invariant.required(submittedAt, "submittedAt");
            targetProcessUid = Invariant.required(targetProcessUid, "targetProcessUid");
            acceptedAt = Invariant.required(acceptedAt, "acceptedAt");
            Invariant.check(targetProcessUid.isPresent() == acceptedAt.isPresent(),
                    "accepted input requires both target process and acceptance time");
            acceptedAt.ifPresent(at -> Invariant.check(!at.isBefore(submittedAt),
                    "input acceptance must not precede submission"));
        }

        public Input accept(UUID processUid, Instant at) {
            if (acceptedAt.isPresent()) {
                throw new IllegalStateException("input is already accepted");
            }
            return new Input(inputId, sessionId, sequence, committedText, submittedAt,
                    Optional.of(Invariant.required(processUid, "processUid")),
                    Optional.of(Invariant.required(at, "at")));
        }
    }

    public record Attachment(
            UUID attachmentId,
            UUID sessionId,
            UUID processUid,
            Instant attachedAt,
            Optional<Instant> detachedAt
    ) {
        public Attachment {
            Invariant.required(attachmentId, "attachmentId");
            Invariant.required(sessionId, "sessionId");
            Invariant.required(processUid, "processUid");
            Invariant.required(attachedAt, "attachedAt");
            detachedAt = Invariant.required(detachedAt, "detachedAt");
            detachedAt.ifPresent(at -> Invariant.check(!at.isBefore(attachedAt),
                    "detachment must not precede attachment"));
        }
    }

    public record Interrupt(
            UUID processUid,
            Instant requestedAt,
            Optional<Instant> handledAt
    ) {
        public Interrupt {
            Invariant.required(processUid, "processUid");
            Invariant.required(requestedAt, "requestedAt");
            handledAt = Invariant.required(handledAt, "handledAt");
            handledAt.ifPresent(at -> Invariant.check(!at.isBefore(requestedAt),
                    "interrupt handling must not precede request"));
        }

        public Interrupt handled(Instant at) {
            if (handledAt.isPresent()) {
                throw new IllegalStateException("interrupt is already handled");
            }
            return new Interrupt(processUid, requestedAt,
                    Optional.of(Invariant.required(at, "at")));
        }
    }

    public enum Status {
        OPEN,
        CLOSED
    }
}
