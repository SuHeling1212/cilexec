package com.follarce.kernel.api.function;

/** Indicates that an external side effect may have happened but cannot be confirmed. */
public final class UnknownEffectOutcomeException extends RuntimeException {
    public UnknownEffectOutcomeException(String message, Throwable cause) {
        super(message, cause);
    }
}
