package com.follarce.persistence.sqlite;

public final class PackageDatabaseException extends RuntimeException {
    public PackageDatabaseException(String message) {
        super(message);
    }

    public PackageDatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
