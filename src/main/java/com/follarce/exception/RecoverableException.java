package com.follarce.exception;

/**
 * 可恢复异常 —— 不会终止进程，仅记录到 data._warning。
 */
public class RecoverableException extends ProcessException {

    public RecoverableException(String message) {
        super(message);
    }

    public RecoverableException(String message, ExceptionContext context) {
        super(message, context);
    }

    // ── 工厂方法 ──

    public static RecoverableException fileLocked(String path) {
        return new RecoverableException("File is locked: " + path);
    }

    public static RecoverableException resourceUnavailable(String resource) {
        return new RecoverableException("Resource unavailable: " + resource);
    }

    public static RecoverableException networkTimeout(String host) {
        return new RecoverableException("Network timeout: " + host);
    }

    public static RecoverableException rateLimitExceeded(String operation) {
        return new RecoverableException("Rate limit exceeded: " + operation);
    }
}
