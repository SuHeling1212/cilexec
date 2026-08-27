package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclSuspension;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * The single durable request/resume protocol for FCL and Java-extension effects.
 *
 * <p>This service creates an effect inside the caller's existing statement transaction. It does
 * not commit, publish post-commit hints, or persist an effect outcome; those remain the durable
 * worker and slice responsibilities.
 */
public final class EffectInvocationService {
    private final TransactionContext transaction;
    private final UUID ownerId;
    private final UUID processUid;
    private final Instant now;
    private final FclContinuationCodec codec;

    public EffectInvocationService(TransactionContext transaction, UUID ownerId, UUID processUid,
                                   Instant now, FclContinuationCodec codec) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.processUid = Objects.requireNonNull(processUid, "processUid");
        this.now = Objects.requireNonNull(now, "now");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Object await(FclContinuation continuation, Call call,
                        Function<Object, String> failureDescription) {
        Objects.requireNonNull(continuation, "continuation");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(failureDescription, "failureDescription");
        if (continuation.scope().contains(ProcessInbox.EFFECT_RESULT)) {
            Object delivered = continuation.scope().remove(ProcessInbox.EFFECT_RESULT);
            if (!(delivered instanceof Map<?, ?> result) || !Boolean.TRUE.equals(result.get("ok"))) {
                throw new FclRuntimeException("External effect failed: "
                        + failureDescription.apply(delivered));
            }
            return call.returnValue() ? result.get("value") : null;
        }
        Authorization.require(transaction, ownerId, Capability.EFFECT_REQUEST);
        UUID effectId = UUID.randomUUID();
        Continuation.PersistedValue payload = new Continuation.PersistedValue(
                codec.valueType(call.payload()), codec.valueToJson(call.payload()));
        transaction.effects().save(EffectRequest.prepare(effectId, processUid, call.effectType(),
                payload, call.policy(), now));
        continuation.waitFor("effect:" + effectId, call.waitPayload());
        transaction.audit().append(new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                ownerId.toString(), call.auditAction(), "effect.effect", effectId.toString(),
                AuditEvent.Result.SUCCEEDED, call.auditDetails(), now));
        throw FclSuspension.suspend();
    }

    public record Call(String effectType, Object payload, EffectRequest.Policy policy,
                       boolean returnValue, Map<String, Object> waitPayload,
                       String auditAction, Map<String, String> auditDetails) {
        public Call {
            Objects.requireNonNull(effectType, "effectType");
            Objects.requireNonNull(policy, "policy");
            waitPayload = Map.copyOf(waitPayload);
            Objects.requireNonNull(auditAction, "auditAction");
            auditDetails = Map.copyOf(auditDetails);
        }
    }
}
