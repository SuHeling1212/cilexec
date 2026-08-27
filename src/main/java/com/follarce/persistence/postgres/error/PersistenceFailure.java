package com.follarce.persistence.postgres.error;

import com.follarce.domain.port.DurableStorageFailure;

/** Stable database error surface used outside the JDBC adapter. */
public class PersistenceFailure extends RuntimeException implements DurableStorageFailure {
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

    @Override
    public boolean isUniqueConflict() {
        return kind == Kind.UNIQUE_CONFLICT;
    }

    @Override
    public boolean stopsRuntime() {
        return kind == Kind.DATABASE_UNAVAILABLE || kind == Kind.RUNTIME_FENCED;
    }

    public enum Kind {
        UNIQUE_CONFLICT,
        REFERENCE_CONFLICT,
        SERIALIZATION_CONFLICT,
        DEADLOCK,
        DATABASE_UNAVAILABLE,
        RETRYABLE_TRANSIENT,
        RUNTIME_FENCED,
        OPTIMISTIC_CONFLICT,
        GENERAL
    }
}
