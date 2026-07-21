package com.follarce.extension.terminal;

public interface TerminalOutput {
    void write(String text);
    void writeLine(String text);
    void writeResult(Object value);
    void writeError(String error);
    void writeSystemMessage(String message);
    void flush();
}
