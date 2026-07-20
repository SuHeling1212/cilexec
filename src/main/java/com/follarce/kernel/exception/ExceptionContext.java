package com.follarce.kernel.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异常发生的完整上下文信息。
 */
public class ExceptionContext {

    private int processId;
    private int lineNumber;
    private String filePath;
    private String currentLine;
    private String operation;
    private Map<String, Object> additionalInfo;

    public ExceptionContext() {
        this.additionalInfo = new LinkedHashMap<>();
    }

    public ExceptionContext(int processId, int lineNumber, String filePath,
                            String currentLine, String operation) {
        this();
        this.processId = processId;
        this.lineNumber = lineNumber;
        this.filePath = filePath;
        this.currentLine = currentLine;
        this.operation = operation;
    }

    public int getProcessId() { return processId; }
    public void setProcessId(int processId) { this.processId = processId; }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getCurrentLine() { return currentLine; }
    public void setCurrentLine(String currentLine) { this.currentLine = currentLine; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public Map<String, Object> getAdditionalInfo() { return additionalInfo; }
    public void setAdditionalInfo(Map<String, Object> additionalInfo) {
        this.additionalInfo = additionalInfo;
    }
    public void addInfo(String key, Object value) {
        this.additionalInfo.put(key, value);
    }

    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ExceptionContext{");
        if (processId > 0) sb.append("pid=").append(processId).append(", ");
        if (lineNumber > 0) sb.append("line=").append(lineNumber).append(", ");
        if (filePath != null) sb.append("path='").append(filePath).append("', ");
        if (currentLine != null) sb.append("code='").append(currentLine).append("', ");
        if (operation != null) sb.append("op='").append(operation).append("', ");
        if (!additionalInfo.isEmpty()) sb.append("info=").append(additionalInfo).append(", ");
        if (sb.charAt(sb.length() - 2) == ',') sb.setLength(sb.length() - 2);
        sb.append("}");
        return sb.toString();
    }
}
