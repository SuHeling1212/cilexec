package com.follarce.process.exception;

public class UnrecoverableException extends ProcessException {
    
    public UnrecoverableException(String message, ExceptionContext context) {
        super(message, context, false);
    }
    
    public UnrecoverableException(String message, Throwable cause, ExceptionContext context) {
        super(message, cause, context, false);
    }
    
    public static UnrecoverableException syntaxError(String code, int processId, int lineNumber, String details) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, null, code, "syntax_check");
        context.addInfo("details", details);
        return new UnrecoverableException("Syntax error: " + details, context);
    }
    
    public static UnrecoverableException undefinedVariable(String varName, int processId, int lineNumber) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, null, null, "variable_access");
        context.addInfo("variable", varName);
        return new UnrecoverableException("Undefined variable: " + varName, context);
    }
    
    public static UnrecoverableException invalidOperation(String operation, int processId, int lineNumber, String reason) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, null, null, operation);
        context.addInfo("reason", reason);
        return new UnrecoverableException("Invalid operation '" + operation + "': " + reason, context);
    }
    
    public static UnrecoverableException divisionByZero(int processId, int lineNumber, String expression) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, null, expression, "division");
        return new UnrecoverableException("Division by zero", context);
    }
    
    public static UnrecoverableException arrayIndexOutOfBounds(int index, int size, int processId, int lineNumber) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, null, null, "array_access");
        context.addInfo("index", index);
        context.addInfo("array_size", size);
        return new UnrecoverableException("Array index out of bounds: index " + index + ", size " + size, context);
    }
    
    public static UnrecoverableException typeError(String expected, String actual, int processId, int lineNumber) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, null, null, "type_check");
        context.addInfo("expected_type", expected);
        context.addInfo("actual_type", actual);
        return new UnrecoverableException("Type error: expected " + expected + ", got " + actual, context);
    }
    
    public static UnrecoverableException fileNotFound(String filePath, int processId, int lineNumber) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, filePath, null, "file_access");
        return new UnrecoverableException("File not found: " + filePath, context);
    }
    
    public static UnrecoverableException unknownFunction(String funcName, int processId, int lineNumber) {
        ExceptionContext context = new ExceptionContext(processId, lineNumber, null, null, "function_call");
        context.addInfo("function", funcName);
        return new UnrecoverableException("Unknown function: " + funcName, context);
    }
}
