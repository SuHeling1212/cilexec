package com.follarce.effect;

import com.follarce.domain.effect.EffectPayload;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.process.Continuation;

import java.util.Optional;

/** Executes one declared external effect outside any PostgreSQL transaction. */
public interface EffectHandler {
    String effectType();

    Continuation.PersistedValue execute(
            Continuation.PersistedValue request,
            Optional<String> idempotencyKey
    ) throws Exception;

    /**
     * Payload-aware execution hook. Existing JSON handlers keep using execute; handlers that
     * accept object-store references override this method.
     */
    default EffectPayload executePayload(
            EffectPayload request,
            Optional<String> idempotencyKey
    ) throws Exception {
        if (request.jsonValue().isEmpty()) {
            throw new IllegalArgumentException(
                    "Effect handler does not accept object-backed requests: " + effectType());
        }
        return EffectPayload.json(execute(request.jsonValue().orElseThrow(), idempotencyKey));
    }

    /** Queries remote state when the declared policy permits it. */
    default Optional<Continuation.PersistedValue> queryOutcome(EffectRequest request)
            throws Exception {
        return Optional.empty();
    }

    /** Payload-aware remote query hook with compatibility for JSON handlers. */
    default Optional<EffectPayload> queryOutcomePayload(EffectRequest request) throws Exception {
        return queryOutcome(request).map(EffectPayload::json);
    }
}
