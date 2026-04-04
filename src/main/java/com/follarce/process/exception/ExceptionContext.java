package com.follarce.process.exception;

import java.util.HashMap;
import java.util.Map;

public class ExceptionContext {
    
    private final int processId;
    private final int lineNumber;
    private final String filePath;
    private final String currentLine;
    private final String operation;
    private final Map<String, Object> additionalInfo;
    
    public ExceptionContext(int processId, int lineNumber, String filePath, 
                           String currentLine, String operation) {
        this.processId = processId;
        this.lineNumber = lineNumber;
        this.filePath = filePath;
        this.currentLine = currentLine;
        this.operation = operation;
        this.additionalInfo = new HashMap<>();
    }
    
    public ExceptionContext addInfo(String key, Object value) {
        this.additionalInfo.put(key, value);
        return this;
    }
    
    public int getProcessId() {
        return processId;
    }
    
    public int getLineNumber() {
        return lineNumber;
    }
    
    public String getFilePath() {
        return filePath;
    }
    
    public String getCurrentLine() {
        return currentLine;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public Map<String, Object> getAdditionalInfo() {
        return additionalInfo;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Process ID: ").append(processId);
        sb.append(", Line: ").append(lineNumber);
        if (filePath != null && !filePath.isEmpty()) {
            sb.append(", File: ").append(filePath);
        }
        if (currentLine != null && !currentLine.isEmpty()) {
            sb.append(", Code: \"").append(currentLine).append("\"");
        }
        if (operation != null && !operation.isEmpty()) {
            sb.append(", Operation: ").append(operation);
        }
        if (!additionalInfo.isEmpty()) {
            sb.append(", Additional: ").append(additionalInfo);
        }
        return sb.toString();
    }
    
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Exception Context ===\n");
        sb.append("Process ID: ").append(processId).append("\n");
        sb.append("Line Number: ").append(lineNumber).append("\n");
        sb.append("File Path: ").append(filePath != null ? filePath : "N/A").append("\n");
        sb.append("Current Line: ").append(currentLine != null ? "\"" + currentLine + "\"" : "N/A").append("\n");
        sb.append("Operation: ").append(operation != null ? operation : "N/A").append("\n");
        if (!additionalInfo.isEmpty()) {
            sb.append("Additional Information:\n");
            for (Map.Entry<String, Object> entry : additionalInfo.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        sb.append("=========================");
        return sb.toString();
    }
}
