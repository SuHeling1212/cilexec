package com.follarce.kernel.exception;

/**
 * 不可恢复异常 —— 终止当前进程运行。
 */
public class UnrecoverableException extends ProcessException {

    public UnrecoverableException(String message) {
        super(message);
    }

    public UnrecoverableException(String message, ExceptionContext context) {
        super(message, context);
    }

    public UnrecoverableException(String message, Throwable cause) {
        super(message, cause);
    }

    // ── 工厂方法 ──

    public static UnrecoverableException syntaxError(String detail) {
        return new UnrecoverableException("Syntax error: " + detail);
    }

    public static UnrecoverableException undefinedVariable(String name) {
        return new UnrecoverableException("Undefined variable: " + name);
    }

    public static UnrecoverableException divisionByZero() {
        return new UnrecoverableException("Division by zero");
    }

    public static UnrecoverableException arrayIndexOutOfBounds(int index, int size) {
        return new UnrecoverableException("Array index out of bounds: index=" + index + ", size=" + size);
    }

    public static UnrecoverableException typeError(String expected, String actual) {
        return new UnrecoverableException("Type error: expected " + expected + ", got " + actual);
    }

    public static UnrecoverableException fileNotFound(String path) {
        return new UnrecoverableException("File not found: " + path);
    }

    public static UnrecoverableException unknownFunction(String name) {
        return new UnrecoverableException("Unknown function: " + name);
    }
}
