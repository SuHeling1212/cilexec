package com.follarce.fcl;

/** A deterministic FCL evaluation failure. */
public final class FclRuntimeException extends RuntimeException {
    public FclRuntimeException(String message) {
        super(message);
    }

    public FclRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
