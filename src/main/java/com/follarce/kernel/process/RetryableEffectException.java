package com.follarce.kernel.process;

import com.follarce.kernel.exception.ProcessException;

/** A durable local effect was not confirmed and should retry with the same effect ID. */
public final class RetryableEffectException extends ProcessException {
    public RetryableEffectException(String message, Throwable cause) {
        super(message, cause);
    }
}
