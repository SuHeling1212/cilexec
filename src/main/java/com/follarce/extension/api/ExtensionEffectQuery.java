package com.follarce.extension.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable identity and request supplied while resolving an unknown remote outcome. */
public record ExtensionEffectQuery(
        UUID effectId,
        UUID processUid,
        Object request,
        Optional<String> idempotencyKey
) {
    public ExtensionEffectQuery {
        Objects.requireNonNull(effectId, "effectId");
        Objects.requireNonNull(processUid, "processUid");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
