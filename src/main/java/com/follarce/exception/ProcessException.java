package com.follarce.exception;

/**
 * 进程异常基类 —— 所有 FCL 脚本执行异常的父类。
 */
public class ProcessException extends RuntimeException {

    private final ExceptionContext context;

    public ProcessException(String message) {
        super(message);
        this.context = new ExceptionContext();
    }

    public ProcessException(String message, ExceptionContext context) {
        super(message);
        this.context = context;
    }

    public ProcessException(String message, Throwable cause) {
        super(message, cause);
        this.context = new ExceptionContext();
    }

    public ProcessException(String message, ExceptionContext context, Throwable cause) {
        super(message, cause);
        this.context = context;
    }

    public ExceptionContext getContext() {
        return context;
    }

    @Override
    public String toString() {
        String ctxStr = context.toDetailedString();
        if (!ctxStr.equals("ExceptionContext{}")) {
            return super.toString() + " " + ctxStr;
        }
        return super.toString();
    }
}
