package com.follarce.pack;

/** Base exception for deterministic package validation and package transactions. */
public class PackageException extends RuntimeException {
    public PackageException(String message) {
        super(message);
    }

    public PackageException(String message, Throwable cause) {
        super(message, cause);
    }
}
