package com.follarce.extension.api;

import com.follarce.fcl.FclRuntimeException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Per-invocation durable context supplied to an extension function. */
public interface ExtensionFunctionContext {
    String extensionId();

    String qualifiedFunctionName();

    List<Object> arguments();

    long expressionId();

    UUID processUid();

    long pid();

    UUID ownerId();

    long executionEpoch();

    Instant now();

    ExtensionState state();

    ExtensionTransaction transaction();

    /**
     * Journals an effect and suspends this expression until its durable result is delivered.
     * Calling it again after resume returns the result value instead of submitting another request.
     */
    Object awaitEffect(String effectType, Object request, ExtensionEffectPolicy policy);

    default Object argument(int index) {
        return arguments().get(index);
    }

    default void requireArity(int expected) {
        if (arguments().size() != expected) {
            throw new FclRuntimeException(qualifiedFunctionName() + " expects " + expected
                    + " arguments, got " + arguments().size());
        }
    }
}
