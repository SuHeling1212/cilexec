package com.follarce.process.exception;

public class ProcessException extends RuntimeException {
    
    private final ExceptionContext context;
    private final boolean recoverable;
    
    public ProcessException(String message, ExceptionContext context, boolean recoverable) {
        super(message);
        this.context = context;
        this.recoverable = recoverable;
    }
    
    public ProcessException(String message, Throwable cause, ExceptionContext context, boolean recoverable) {
        super(message, cause);
        this.context = context;
        this.recoverable = recoverable;
    }
    
    public ExceptionContext getContext() {
        return context;
    }
    
    public boolean isRecoverable() {
        return recoverable;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.toString());
        if (context != null) {
            sb.append("\n  at ").append(context.toString());
        }
        return sb.toString();
    }
    
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Exception Type: ").append(this.getClass().getSimpleName()).append("\n");
        sb.append("Message: ").append(getMessage()).append("\n");
        sb.append("Recoverable: ").append(recoverable).append("\n");
        if (context != null) {
            sb.append(context.toDetailedString()).append("\n");
        }
        if (getCause() != null) {
            sb.append("Caused by: ").append(getCause().getClass().getName());
            sb.append(": ").append(getCause().getMessage()).append("\n");
        }
        return sb.toString();
    }
}
