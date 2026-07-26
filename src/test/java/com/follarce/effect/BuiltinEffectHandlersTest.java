package com.follarce.effect;

import com.follarce.domain.process.Continuation;
import com.follarce.fcl.FclContinuationCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinEffectHandlersTest {
    private final FclContinuationCodec codec = new FclContinuationCodec();

    @Test
    void assemblesEveryExternalNamespaceAndKeepsHostExecDenyByDefault() throws Exception {
        EffectHandlerRegistry handlers = new EffectHandlerRegistry(
                BuiltinEffectHandlers.defaults(Set.of()));
        assertEquals("io.output", handlers.require("io.output").effectType());
        assertEquals("network.http-get", handlers.require("network.http-get").effectType());
        assertEquals("network.http-post", handlers.require("network.http-post").effectType());
        assertEquals("socket.connect", handlers.require("socket.connect").effectType());
        assertEquals("socket.accept", handlers.require("socket.accept").effectType());

        Continuation.PersistedValue command = typed(Map.of("command", List.of("true")));
        assertThrows(SecurityException.class,
                () -> handlers.require("system.exec").execute(command, Optional.empty()));

        Continuation.PersistedValue bound = handlers.require("socket.bind").execute(
                typed(Map.of("arguments", List.of())), Optional.empty());
        Object decoded = codec.valueFromJson(bound.canonicalPayload());
        assertTrue(decoded instanceof Map<?, ?> envelope
                && Boolean.TRUE.equals(envelope.get("ok"))
                && envelope.get("value") instanceof Map<?, ?> endpoint
                && Boolean.TRUE.equals(endpoint.get("oneShot")));
    }

    private Continuation.PersistedValue typed(Object value) {
        return new Continuation.PersistedValue(codec.valueType(value), codec.valueToJson(value));
    }
}
