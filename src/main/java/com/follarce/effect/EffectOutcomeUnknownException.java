package com.follarce.effect;

/** The external call may have happened, but its durable outcome cannot be proven. */
public final class EffectOutcomeUnknownException extends Exception {
    public EffectOutcomeUnknownException(String message) {
        super(message);
    }

    public EffectOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
