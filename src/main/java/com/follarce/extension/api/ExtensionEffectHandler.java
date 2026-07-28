package com.follarce.extension.api;

import java.util.Optional;

/** Performs one extension-owned external effect outside the database transaction. */
public interface ExtensionEffectHandler {
    String effectType();

    Object execute(Object request, Optional<String> idempotencyKey) throws Exception;

    /** Returns the completed value when remote state proves the outcome, otherwise empty. */
    default Optional<Object> queryOutcome(ExtensionEffectQuery query) throws Exception {
        return Optional.empty();
    }
}
