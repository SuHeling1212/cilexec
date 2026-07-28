package com.follarce.extension;

import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.process.Continuation;
import com.follarce.effect.EffectHandler;
import com.follarce.extension.api.ExtensionDescriptor;
import com.follarce.extension.api.ExtensionEffectHandler;
import com.follarce.extension.api.ExtensionEffectQuery;
import com.follarce.fcl.FclContinuationCodec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Converts the small public extension effect contract to the Runtime effect journal contract. */
final class ExtensionEffectAdapter implements EffectHandler {
    private final ExtensionDescriptor extension;
    private final String effectType;
    private final ExtensionEffectHandler handler;
    private final FclContinuationCodec codec = new FclContinuationCodec();

    ExtensionEffectAdapter(ExtensionDescriptor extension, String effectType,
                           ExtensionEffectHandler handler) {
        this.extension = extension;
        this.effectType = effectType;
        this.handler = handler;
    }

    @Override public String effectType() { return effectType; }

    @Override
    public Continuation.PersistedValue execute(Continuation.PersistedValue request,
                                               Optional<String> idempotencyKey) throws Exception {
        Object value = codec.valueFromJson(request.canonicalPayload());
        return envelope(handler.execute(value, idempotencyKey));
    }

    @Override
    public Optional<Continuation.PersistedValue> queryOutcome(EffectRequest request)
            throws Exception {
        Object value = codec.valueFromJson(request.request().canonicalPayload());
        ExtensionEffectQuery query = new ExtensionEffectQuery(request.effectId(),
                request.processUid(), value, request.policy().idempotencyKey());
        return handler.queryOutcome(query).map(this::envelope);
    }

    private Continuation.PersistedValue envelope(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("value", value);
        result.put("extensionId", extension.id());
        return new Continuation.PersistedValue(codec.valueType(result),
                codec.valueToJson(result));
    }
}
