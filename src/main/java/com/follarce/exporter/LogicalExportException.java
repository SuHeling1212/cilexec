package com.follarce.exporter;

/** Failure to create or validate a CilExec application-level logical export. */
public final class LogicalExportException extends RuntimeException {
    public LogicalExportException(String message) {
        super(message);
    }

    public LogicalExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
