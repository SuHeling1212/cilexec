package com.follarce.domain.effect;

import com.follarce.domain.Invariant;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.vfs.ObjectHash;

import java.util.Optional;

/** Exactly one durable representation of an effect request or result. */
public record EffectPayload(
        Optional<Continuation.PersistedValue> jsonValue,
        Optional<ObjectHash> objectHash
) {
    public static final String OBJECT_REFERENCE_TYPE =
            "application/vnd.cilexec.object-reference+json";

    public EffectPayload {
        jsonValue = Invariant.required(jsonValue, "jsonValue");
        objectHash = Invariant.required(objectHash, "objectHash");
        Invariant.check(jsonValue.isPresent() ^ objectHash.isPresent(),
                "effect payload requires exactly one representation");
    }

    public static EffectPayload json(Continuation.PersistedValue value) {
        return new EffectPayload(Optional.of(Invariant.required(value, "value")),
                Optional.empty());
    }

    public static EffectPayload object(ObjectHash hash) {
        return new EffectPayload(Optional.empty(),
                Optional.of(Invariant.required(hash, "hash")));
    }

    /** Stable continuation value used when an object-backed result is delivered. */
    public Continuation.PersistedValue deliveryValue() {
        return jsonValue.orElseGet(() -> new Continuation.PersistedValue(
                OBJECT_REFERENCE_TYPE,
                "{\"objectHash\":\"" + objectHash.orElseThrow().value() + "\"}"));
    }
}
