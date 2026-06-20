package com.follarce.process.exception;

public class RecoverableException extends ProcessException {
    
    public RecoverableException(String message, ExceptionContext context) {
        super(message, context, true);
    }
    
    public RecoverableException(String message, Throwable cause, ExceptionContext context) {
        super(message, cause, context, true);
    }
    
    public static RecoverableException fileLocked(String filePath, int processId, int lineNumber) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, filePath, null, "file_access");
        return new RecoverableException("File is temporarily locked: " + filePath, context);
    }
    
    public static RecoverableException resourceUnavailable(String resource, int processId, int lineNumber) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, null, null, "resource_access");
        context.addInfo("resource", resource);
        return new RecoverableException("Resource temporarily unavailable: " + resource, context);
    }
    
    public static RecoverableException networkTimeout(String operation, int processId, int lineNumber) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, null, null, "network_operation");
        context.addInfo("operation", operation);
        return new RecoverableException("Network operation timed out: " + operation, context);
    }
    
    public static RecoverableException rateLimitExceeded(String operation, int processId, int lineNumber) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, null, null, "rate_limit");
        context.addInfo("operation", operation);
        return new RecoverableException("Rate limit exceeded for operation: " + operation, context);
    }
}
