package com.follarce.domain.effect;

import com.follarce.domain.Invariant;
import com.follarce.domain.process.Continuation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** One durable invocation of an external effect handler. */
public record EffectAttempt(
        UUID attemptId,
        UUID effectId,
        int attemptNumber,
        UUID runnerId,
        Status status,
        Instant startedAt,
        Optional<Instant> finishedAt,
        Optional<String> remoteReference,
        Optional<Continuation.PersistedValue> result,
        Optional<String> errorCode,
        Optional<String> errorMessage
) {
    public EffectAttempt {
        Invariant.required(attemptId, "attemptId");
        Invariant.required(effectId, "effectId");
        Invariant.positive(attemptNumber, "attemptNumber");
        Invariant.required(runnerId, "runnerId");
        Invariant.required(status, "status");
        Invariant.required(startedAt, "startedAt");
        finishedAt = Invariant.required(finishedAt, "finishedAt");
        remoteReference = Invariant.required(remoteReference, "remoteReference")
                .map(value -> Invariant.text(value, "remoteReference"));
        result = Invariant.required(result, "result");
        errorCode = Invariant.required(errorCode, "errorCode")
                .map(value -> Invariant.text(value, "errorCode"));
        errorMessage = Invariant.required(errorMessage, "errorMessage")
                .map(value -> Invariant.text(value, "errorMessage"));
        finishedAt.ifPresent(finished -> Invariant.check(!finished.isBefore(startedAt),
                "attempt finish must not precede its start"));
        validateState(status, finishedAt, result, errorCode, errorMessage);
    }

    public static EffectAttempt claim(UUID effectId, int attemptNumber, UUID runnerId,
                                      Instant at) {
        return new EffectAttempt(UUID.randomUUID(), effectId, attemptNumber, runnerId,
                Status.CLAIMED, at, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    public EffectAttempt start() {
        requireStatus(Status.CLAIMED);
        return copy(Status.EXECUTING, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    public EffectAttempt succeed(Continuation.PersistedValue completedResult, Instant at) {
        requireStatus(Status.EXECUTING);
        return copy(Status.SUCCEEDED, Optional.of(Invariant.required(at, "at")),
                Optional.of(Invariant.required(completedResult, "completedResult")),
                Optional.empty(), Optional.empty());
    }

    public EffectAttempt fail(String code, String message, Instant at) {
        requireStatus(Status.EXECUTING);
        return copy(Status.FAILED, Optional.of(Invariant.required(at, "at")),
                Optional.empty(), Optional.of(Invariant.text(code, "code")),
                Optional.of(Invariant.text(message, "message")));
    }

    public EffectAttempt unknown(String code, String message, Instant at) {
        requireStatus(Status.EXECUTING);
        return copy(Status.UNKNOWN, Optional.of(Invariant.required(at, "at")),
                Optional.empty(), Optional.of(Invariant.text(code, "code")),
                Optional.of(Invariant.text(message, "message")));
    }

    private EffectAttempt copy(Status nextStatus, Optional<Instant> nextFinishedAt,
                               Optional<Continuation.PersistedValue> nextResult,
                               Optional<String> nextErrorCode,
                               Optional<String> nextErrorMessage) {
        return new EffectAttempt(attemptId, effectId, attemptNumber, runnerId, nextStatus,
                startedAt, nextFinishedAt, remoteReference, nextResult, nextErrorCode,
                nextErrorMessage);
    }

    private void requireStatus(Status required) {
        if (status != required) {
            throw new IllegalStateException("effect attempt is " + status
                    + ", expected " + required);
        }
    }

    private static void validateState(
            Status status,
            Optional<Instant> finishedAt,
            Optional<Continuation.PersistedValue> result,
            Optional<String> errorCode,
            Optional<String> errorMessage
    ) {
        Invariant.check(errorCode.isPresent() == errorMessage.isPresent(),
                "attempt error code and message must appear together");
        switch (status) {
            case CLAIMED, EXECUTING -> Invariant.check(finishedAt.isEmpty()
                            && result.isEmpty() && errorCode.isEmpty(),
                    "unfinished attempt cannot contain an outcome");
            case SUCCEEDED -> Invariant.check(finishedAt.isPresent() && result.isPresent()
                            && errorCode.isEmpty(),
                    "successful attempt requires only a result");
            case FAILED, UNKNOWN -> Invariant.check(finishedAt.isPresent() && result.isEmpty()
                            && errorCode.isPresent(),
                    "failed or unknown attempt requires an error");
        }
    }

    public enum Status {
        CLAIMED,
        EXECUTING,
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }
}
