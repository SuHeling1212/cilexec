package com.follarce.extension.api;

import com.follarce.fcl.FclRuntimeException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Per-invocation durable context supplied to an extension function. */
public interface ExtensionFunctionContext {
    String extensionId();

    String qualifiedFunctionName();

    /**
     * The positional arguments of this call, in declaration order. FCL null is a first-class
     * value, so elements may be null.
     */
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

    /**
     * Returns the argument at {@code index}, which may be null.
     *
     * @throws IllegalArgumentException when the index is outside the received argument range
     */
    default Object argument(int index) {
        List<Object> arguments = arguments();
        if (index < 0 || index >= arguments.size()) {
            throw new IllegalArgumentException(qualifiedFunctionName() + " argument index "
                    + index + " is out of bounds; the function received " + arguments.size()
                    + " arguments");
        }
        return arguments.get(index);
    }

    default void requireArity(int expected) {
        if (arguments().size() != expected) {
            throw new FclRuntimeException(qualifiedFunctionName() + " expects " + expected
                    + " arguments, got " + arguments().size());
        }
    }
}
