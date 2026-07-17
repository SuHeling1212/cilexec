package com.follarce.process;

import com.follarce.exception.ProcessException;

/** A durable local effect was not confirmed and should retry with the same effect ID. */
public final class RetryableEffectException extends ProcessException {
    public RetryableEffectException(String message, Throwable cause) {
        super(message, cause);
    }
}
