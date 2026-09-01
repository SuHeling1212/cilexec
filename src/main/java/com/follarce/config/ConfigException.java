package com.follarce.config;

/** Raised before startup when a required setting or secret is invalid. */
public final class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
