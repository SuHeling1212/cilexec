package com.follarce.persistence.sqlite;

public final class PackageDatabaseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PackageDatabaseException(String message) {
        super(message);
    }

    public PackageDatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
