package com.follarce.fcl;

/** A deterministic FCL evaluation failure. */
public final class FclRuntimeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String type;

    public FclRuntimeException(String message) {
        this("RuntimeError", message, null);
    }

    public FclRuntimeException(String message, Throwable cause) {
        this("RuntimeError", message, cause);
    }

    public FclRuntimeException(String type, String message) {
        this(type, message, null);
    }

    public FclRuntimeException(String type, String message, Throwable cause) {
        super(message, cause);
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Exception type is required");
        this.type = type;
    }

    public String type() {
        return type;
    }
}
