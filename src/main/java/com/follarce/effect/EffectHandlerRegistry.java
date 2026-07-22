package com.follarce.effect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable effect dispatch table assembled explicitly at bootstrap. */
public final class EffectHandlerRegistry {
    private final Map<String, EffectHandler> handlers;

    public EffectHandlerRegistry(List<? extends EffectHandler> handlers) {
        Map<String, EffectHandler> indexed = new LinkedHashMap<>();
        for (EffectHandler handler : List.copyOf(handlers)) {
            if (handler.effectType() == null || handler.effectType().isBlank()) {
                throw new IllegalArgumentException("Effect type must not be blank");
            }
            if (indexed.putIfAbsent(handler.effectType(), handler) != null) {
                throw new IllegalArgumentException("Duplicate effect handler: " + handler.effectType());
            }
        }
        this.handlers = Map.copyOf(indexed);
    }

    public EffectHandler require(String effectType) {
        EffectHandler handler = handlers.get(effectType);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported external effect: " + effectType);
        }
        return handler;
    }
}
