package com.follarce.fcl;

/** Raised when FCL source cannot be compiled into a program. */
public final class FclCompileException extends RuntimeException {
    private final int line;
    private final int column;

    public FclCompileException(String message, int line, int column) {
        super(message + " at " + line + ":" + column);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
