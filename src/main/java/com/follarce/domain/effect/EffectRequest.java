package com.follarce.domain.effect;

import com.follarce.domain.Invariant;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.vfs.ObjectHash;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Journaled request for an operation outside the authoritative database. */
public record EffectRequest(
        UUID effectId,
        UUID processUid,
        String effectType,
        EffectPayload requestPayload,
        Policy policy,
        Status status,
        Optional<UUID> claimedBy,
        Optional<Instant> claimedAt,
        Optional<Instant> executionStartedAt,
        Optional<EffectPayload> resultPayload,
        Optional<String> failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    private static final int MAX_EFFECT_TYPE_LENGTH = 128;
    private static final String EFFECT_TYPE_PATTERN =
            "[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9_-]*)+";

    public EffectRequest {
        Invariant.required(effectId, "effectId");
        Invariant.required(processUid, "processUid");
        effectType = validatedEffectType(effectType);
        Invariant.required(requestPayload, "requestPayload");
        Invariant.required(policy, "policy");
        Invariant.required(status, "status");
        claimedBy = Invariant.required(claimedBy, "claimedBy");
        claimedAt = Invariant.required(claimedAt, "claimedAt");
        executionStartedAt = Invariant.required(executionStartedAt, "executionStartedAt");
        resultPayload = Invariant.required(resultPayload, "resultPayload");
        failureReason = Invariant.required(failureReason, "failureReason")
                .map(value -> boundedText(value, "failureReason", 4096));
        Invariant.required(createdAt, "createdAt");
        Invariant.required(updatedAt, "updatedAt");
        Invariant.check(!updatedAt.isBefore(createdAt), "updatedAt must not precede createdAt");
        validateState(status, claimedBy, claimedAt, executionStartedAt, resultPayload,
                failureReason);
    }

    /** Compatibility constructor for the original JSON-backed request/result API. */
    public EffectRequest(
            UUID effectId,
            UUID processUid,
            String effectType,
            Continuation.PersistedValue request,
            Policy policy,
            Status status,
            Optional<UUID> claimedBy,
            Optional<Instant> claimedAt,
            Optional<Instant> executionStartedAt,
            Optional<Continuation.PersistedValue> result,
            Optional<String> failureReason,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(effectId, processUid, effectType, EffectPayload.json(request), policy, status,
                claimedBy, claimedAt, executionStartedAt,
                Invariant.required(result, "result").map(EffectPayload::json), failureReason,
                createdAt, updatedAt);
    }

    public static EffectRequest prepare(
            UUID effectId,
            UUID processUid,
            String effectType,
            Continuation.PersistedValue request,
            Policy policy,
            Instant at
    ) {
        return prepare(effectId, processUid, effectType, EffectPayload.json(request), policy, at);
    }

    public static EffectRequest prepare(
            UUID effectId,
            UUID processUid,
            String effectType,
            EffectPayload request,
            Policy policy,
            Instant at
    ) {
        return new EffectRequest(effectId, processUid, effectType, request, policy,
                Status.PREPARED, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), at, at);
    }

    public static EffectRequest prepareObject(
            UUID effectId,
            UUID processUid,
            String effectType,
            ObjectHash requestObjectHash,
            Policy policy,
            Instant at
    ) {
        return prepare(effectId, processUid, effectType, EffectPayload.object(requestObjectHash),
                policy, at);
    }

    /** JSON-backed compatibility accessor; object-backed requests use requestPayload(). */
    public Continuation.PersistedValue request() {
        return requestPayload.jsonValue().orElseThrow(() ->
                new IllegalStateException("Effect request is backed by an object hash"));
    }

    public Optional<Continuation.PersistedValue> requestJson() {
        return requestPayload.jsonValue();
    }

    public Optional<ObjectHash> requestObjectHash() {
        return requestPayload.objectHash();
    }

    /** JSON-backed compatibility accessor; inspect resultPayload() for either representation. */
    public Optional<Continuation.PersistedValue> result() {
        return resultPayload.flatMap(EffectPayload::jsonValue);
    }

    public Optional<ObjectHash> resultObjectHash() {
        return resultPayload.flatMap(EffectPayload::objectHash);
    }

    public EffectRequest claim(UUID workerId, Instant at) {
        requireStatus(Status.PREPARED);
        return copy(Status.CLAIMED, Optional.of(Invariant.required(workerId, "workerId")),
                Optional.of(at), Optional.empty(), Optional.empty(), Optional.empty(), at);
    }

    public EffectRequest start(Instant at) {
        requireStatus(Status.CLAIMED);
        Invariant.check(!at.isBefore(claimedAt.orElseThrow()),
                "effect execution must not precede claim");
        return copy(Status.EXECUTING, claimedBy, claimedAt, Optional.of(at),
                Optional.empty(), Optional.empty(), at);
    }

    public EffectRequest complete(Continuation.PersistedValue completedResult, Instant at) {
        return complete(EffectPayload.json(completedResult), at);
    }

    public EffectRequest complete(EffectPayload completedResult, Instant at) {
        requireStatus(Status.EXECUTING);
        return copy(Status.COMPLETED, claimedBy, claimedAt, executionStartedAt,
                Optional.of(Invariant.required(completedResult, "completedResult")),
                Optional.empty(), at);
    }

    public EffectRequest completeObject(ObjectHash completedResult, Instant at) {
        return complete(EffectPayload.object(completedResult), at);
    }

    public EffectRequest fail(String reason, Instant at) {
        requireStatus(Status.EXECUTING);
        return copy(Status.FAILED, claimedBy, claimedAt, executionStartedAt,
                Optional.empty(), Optional.of(reason), at);
    }

    public EffectRequest unknown(String reason, Instant at) {
        requireStatus(Status.EXECUTING);
        return copy(Status.UNKNOWN, claimedBy, claimedAt, executionStartedAt,
                Optional.empty(), Optional.of(reason), at);
    }

    public EffectRequest resolveUnknownSuccess(Continuation.PersistedValue result, Instant at) {
        return resolveUnknownSuccess(EffectPayload.json(result), at);
    }

    public EffectRequest resolveUnknownSuccess(EffectPayload result, Instant at) {
        requireManualResolution();
        return copy(Status.COMPLETED, claimedBy, claimedAt, executionStartedAt,
                Optional.of(Invariant.required(result, "result")), Optional.empty(), at);
    }

    public EffectRequest resolveUnknownFailure(String reason, Instant at) {
        requireManualResolution();
        return copy(Status.FAILED, claimedBy, claimedAt, executionStartedAt,
                Optional.empty(), Optional.of(reason), at);
    }

    public EffectRequest resolveRecoveredSuccess(EffectPayload result, Instant at) {
        requireUnknownPolicy(UnknownAction.QUERY_REMOTE);
        return copy(Status.COMPLETED, claimedBy, claimedAt, executionStartedAt,
                Optional.of(Invariant.required(result, "result")), Optional.empty(), at);
    }

    public EffectRequest beginRecoveredRetry(Instant at) {
        requireUnknownPolicy(UnknownAction.RETRY_IDEMPOTENT);
        return copy(Status.EXECUTING, claimedBy, claimedAt, Optional.of(at),
                Optional.empty(), Optional.empty(), at);
    }

    public EffectRequest retainRecoveredUnknown(String reason, Instant at) {
        if (status != Status.UNKNOWN || policy.unknownAction == UnknownAction.MANUAL) {
            throw new IllegalStateException("effect is not an automatically recoverable UNKNOWN");
        }
        return copy(Status.UNKNOWN, claimedBy, claimedAt, executionStartedAt,
                Optional.empty(), Optional.of(reason), at);
    }

    public EffectRequest claimRecoveredUnknown(UUID workerId, Instant at) {
        if (status != Status.UNKNOWN || policy.unknownAction == UnknownAction.MANUAL) {
            throw new IllegalStateException("effect is not an automatically recoverable UNKNOWN");
        }
        return copy(Status.UNKNOWN, Optional.of(Invariant.required(workerId, "workerId")),
                Optional.of(at), executionStartedAt, Optional.empty(), failureReason, at);
    }

    public boolean requiresManualResolution() {
        return status == Status.UNKNOWN && policy.unknownAction == UnknownAction.MANUAL;
    }

    private EffectRequest copy(
            Status nextStatus,
            Optional<UUID> nextClaimedBy,
            Optional<Instant> nextClaimedAt,
            Optional<Instant> nextExecutionStartedAt,
            Optional<EffectPayload> nextResult,
            Optional<String> nextFailure,
            Instant at
    ) {
        Invariant.check(!at.isBefore(updatedAt), "effect update time must not move backwards");
        return new EffectRequest(effectId, processUid, effectType, requestPayload, policy,
                nextStatus, nextClaimedBy, nextClaimedAt, nextExecutionStartedAt, nextResult,
                nextFailure, createdAt, at);
    }

    private void requireStatus(Status required) {
        if (status != required) {
            throw new IllegalStateException("effect is " + status + ", expected " + required);
        }
    }

    private void requireManualResolution() {
        if (!requiresManualResolution()) {
            throw new IllegalStateException("effect is not awaiting manual resolution");
        }
    }

    private void requireUnknownPolicy(UnknownAction requiredPolicy) {
        if (status != Status.UNKNOWN || policy.unknownAction != requiredPolicy) {
            throw new IllegalStateException("effect UNKNOWN policy is " + policy.unknownAction
                    + ", expected " + requiredPolicy);
        }
    }

    private static String validatedEffectType(String effectType) {
        effectType = Invariant.text(effectType, "effectType");
        Invariant.check(effectType.length() <= MAX_EFFECT_TYPE_LENGTH,
                "effectType must contain at most " + MAX_EFFECT_TYPE_LENGTH + " characters");
        Invariant.check(effectType.matches(EFFECT_TYPE_PATTERN),
                "effectType must be a lower-case namespaced identifier");
        return effectType;
    }

    private static void validateState(
            Status status,
            Optional<UUID> claimedBy,
            Optional<Instant> claimedAt,
            Optional<Instant> executionStartedAt,
            Optional<EffectPayload> result,
            Optional<String> failureReason
    ) {
        Invariant.check(claimedBy.isPresent() == claimedAt.isPresent(),
                "effect claim owner and time must appear together");
        switch (status) {
            case PREPARED -> Invariant.check(claimedBy.isEmpty() && executionStartedAt.isEmpty()
                            && result.isEmpty() && failureReason.isEmpty(),
                    "prepared effect cannot contain execution data");
            case CLAIMED -> Invariant.check(claimedBy.isPresent() && executionStartedAt.isEmpty()
                            && result.isEmpty() && failureReason.isEmpty(),
                    "claimed effect requires only claim data");
            case EXECUTING -> Invariant.check(claimedBy.isPresent()
                            && executionStartedAt.isPresent() && result.isEmpty()
                            && failureReason.isEmpty(),
                    "executing effect requires claim and execution time");
            case COMPLETED -> Invariant.check(executionStartedAt.isPresent()
                            && result.isPresent() && failureReason.isEmpty(),
                    "completed effect requires a result");
            case FAILED, UNKNOWN -> Invariant.check(executionStartedAt.isPresent()
                            && result.isEmpty() && failureReason.isPresent(),
                    "failed or unknown effect requires a reason");
        }
    }

    public record Policy(
            boolean idempotent,
            Optional<String> idempotencyKey,
            boolean remotelyQueryable,
            boolean retryable,
            UnknownAction unknownAction
    ) {
        public Policy {
            idempotencyKey = Invariant.required(idempotencyKey, "idempotencyKey")
                    .map(value -> boundedText(value, "idempotencyKey", 512));
            Invariant.required(unknownAction, "unknownAction");
            Invariant.check(idempotent == idempotencyKey.isPresent(),
                    "idempotent policy requires exactly one idempotency key");
            Invariant.check(!retryable || idempotent,
                    "only idempotent effects can be retried automatically");
            if (unknownAction == UnknownAction.QUERY_REMOTE) {
                Invariant.check(remotelyQueryable,
                        "remote query handling requires a queryable effect");
            }
            if (unknownAction == UnknownAction.RETRY_IDEMPOTENT) {
                Invariant.check(idempotent && retryable,
                        "retry handling requires a retryable idempotent effect");
            }
        }
    }

    private static String boundedText(String value, String name, int maximum) {
        value = Invariant.text(value, name);
        Invariant.check(value.length() <= maximum, name + " is too long");
        Invariant.check(value.chars().noneMatch(Character::isISOControl),
                name + " contains control characters");
        return value;
    }

    public enum UnknownAction {
        QUERY_REMOTE,
        RETRY_IDEMPOTENT,
        MANUAL
    }

    public enum Status {
        PREPARED,
        CLAIMED,
        EXECUTING,
        COMPLETED,
        FAILED,
        UNKNOWN
    }
}
