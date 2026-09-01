package com.follarce.effect;

/** The external call may have happened, but its durable outcome cannot be proven. */
public final class EffectOutcomeUnknownException extends Exception {
    private static final long serialVersionUID = 1L;

    public EffectOutcomeUnknownException(String message) {
        super(message);
    }

    public EffectOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
