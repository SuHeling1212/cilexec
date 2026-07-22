package com.follarce.persistence.postgres.error;

/** Stable database error surface used outside the JDBC adapter. */
public class PersistenceFailure extends RuntimeException {
    private final Kind kind;
    private final boolean retryable;

    public PersistenceFailure(Kind kind, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryable = retryable;
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return retryable;
    }

    public enum Kind {
        UNIQUE_CONFLICT,
        REFERENCE_CONFLICT,
        SERIALIZATION_CONFLICT,
        DEADLOCK,
        DATABASE_UNAVAILABLE,
        RUNTIME_FENCED,
        OPTIMISTIC_CONFLICT,
        GENERAL
    }
}
