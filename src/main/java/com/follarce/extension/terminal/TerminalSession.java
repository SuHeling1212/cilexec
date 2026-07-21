package com.follarce.extension.terminal;

import com.follarce.kernel.Constants;
import com.follarce.kernel.process.ProcessIdentity;
import com.follarce.kernel.process.ProcessRunner;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.io.PrintWriter;
import java.util.*;

public class TerminalSession {
    private int boundPid;
    private String boundProcessGeneration;
    private final StringBuilder inputBuffer = new StringBuilder();
    private int braceDepth;
    private volatile boolean executing;
    private volatile boolean interruptRequested;
    private int submissionStartLine = -1;
    private int submissionEndLine = -1;
    private String workingDirectory = "/";
    private volatile boolean active = true;
    private final TerminalOutput output;
    private final Object sessionLock = new Object();
    private volatile Thread boundThread;

    public TerminalSession(TerminalOutput output) {
        this.output = output;
    }

    public boolean isActive() { return active; }
    public int getBoundPid() { return boundPid; }
    public String getBoundProcessGeneration() { return boundProcessGeneration; }
    public boolean isExecuting() { return executing; }
    public int getBraceDepth() { return braceDepth; }
    public String getWorkingDirectory() { return workingDirectory; }
    public TerminalOutput getOutput() { return output; }
    public Object getSessionLock() { return sessionLock; }

    public void bindProcess(int pid) {
        String procPath = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
        if (FileUtil.exists(procPath)) {
            Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(procPath));
            ProcessIdentity.ensureDefaults(data);
            this.boundPid = pid;
            this.boundProcessGeneration = ProcessIdentity.generation(data);
        } else {
            throw new IllegalStateException("Process not found: " + pid);
        }
    }

    public String getPrompt() {
        String wd = workingDirectory.isEmpty() ? "/" : workingDirectory;
        return wd + "> ";
    }

    public String getContinuationPrompt() {
        return "... ";
    }

    public void appendInput(String line) {
        synchronized (sessionLock) {
            if (inputBuffer.length() > 0) inputBuffer.append('\n');
            inputBuffer.append(line);
            braceDepth += countBraces(line);
        }
    }

    public String getAndClearInput() {
        synchronized (sessionLock) {
            String result = inputBuffer.toString();
            inputBuffer.setLength(0);
            braceDepth = 0;
            return result;
        }
    }

    public void clearInput() {
        synchronized (sessionLock) {
            inputBuffer.setLength(0);
            braceDepth = 0;
        }
    }

    public boolean isMultiline() {
        return braceDepth > 0;
    }

    public void setExecuting(boolean executing) {
        this.executing = executing;
        if (!executing) {
            this.submissionStartLine = -1;
            this.submissionEndLine = -1;
            this.interruptRequested = false;
        }
    }

    public void setSubmissionBounds(int start, int end) {
        this.submissionStartLine = start;
        this.submissionEndLine = end;
    }

    public int getSubmissionStartLine() { return submissionStartLine; }
    public int getSubmissionEndLine() { return submissionEndLine; }

    public void requestInterrupt() {
        this.interruptRequested = true;
        if (boundThread != null) {
            boundThread.interrupt();
        }
    }

    public boolean isInterruptRequested() { return interruptRequested; }

    public void setBoundThread(Thread thread) {
        this.boundThread = thread;
    }

    public void setWorkingDirectory(String dir) {
        this.workingDirectory = dir;
    }

    public void close() {
        active = false;
    }

    @SuppressWarnings("unchecked")
    public List<String> readTerminalOutput() {
        try {
            String procPath = Constants.SYSTEM_PROCESS_PATH + boundPid + ".proc";
            if (!FileUtil.exists(procPath)) return List.of();
            Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(procPath));
            if (!boundProcessGeneration.equals(data.get("ProcessGeneration"))) return List.of();
            Map<String, Object> program = (Map<String, Object>) data.get("Program");
            if (program == null) return List.of();
            Map<String, Object> vars = (Map<String, Object>) program.get("Data");
            if (vars == null) return List.of();
            Object outputObj = vars.get(Constants.TERMINAL_OUTPUT_FIELD);
            if (outputObj instanceof List) {
                return new ArrayList<>((List<String>) outputObj);
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public void clearTerminalOutput() {
        try {
            String procPath = Constants.SYSTEM_PROCESS_PATH + boundPid + ".proc";
            if (!FileUtil.exists(procPath)) return;
            JsonUtil.updateFile(procPath, data -> {
                if (!boundProcessGeneration.equals(data.get("ProcessGeneration"))) return;
                Map<String, Object> program = (Map<String, Object>) data.get("Program");
                if (program == null) return;
                Map<String, Object> vars = (Map<String, Object>) program.get("Data");
                if (vars != null) vars.remove(Constants.TERMINAL_OUTPUT_FIELD);
            });
        } catch (Exception ignored) {
        }
    }

    public boolean isProcessPaused() {
        try {
            String procPath = Constants.SYSTEM_PROCESS_PATH + boundPid + ".proc";
            if (!FileUtil.exists(procPath)) return false;
            Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(procPath));
            if (!boundProcessGeneration.equals(data.get("ProcessGeneration"))) return false;
            return ProcessState.PAUSED.name().equals(data.get("ProcessState"));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isProcessTerminal() {
        try {
            String procPath = Constants.SYSTEM_PROCESS_PATH + boundPid + ".proc";
            if (!FileUtil.exists(procPath)) return true;
            Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(procPath));
            return ProcessState.restore(data.get("ProcessState")).isTerminal();
        } catch (Exception e) {
            return true;
        }
    }

    public int getProcessCurrentLine() {
        try {
            String procPath = Constants.SYSTEM_PROCESS_PATH + boundPid + ".proc";
            if (!FileUtil.exists(procPath)) return -1;
            Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(procPath));
            Map<String, Object> program = (Map<String, Object>) data.get("Program");
            if (program == null) return -1;
            Map<String, Object> code = (Map<String, Object>) program.get("Code");
            if (code == null) return -1;
            Object line = code.get("runningCodeLine");
            return line instanceof Number ? ((Number) line).intValue() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public int getProcessCodeSize() {
        try {
            String procPath = Constants.SYSTEM_PROCESS_PATH + boundPid + ".proc";
            if (!FileUtil.exists(procPath)) return 0;
            Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(procPath));
            Map<String, Object> program = (Map<String, Object>) data.get("Program");
            if (program == null) return 0;
            Map<String, Object> code = (Map<String, Object>) program.get("Code");
            if (code == null) return 0;
            Object lines = code.get("Code");
            return lines instanceof List ? ((List<?>) lines).size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    public void setProcessState(ProcessState state) {
        try {
            String procPath = Constants.SYSTEM_PROCESS_PATH + boundPid + ".proc";
            if (!FileUtil.exists(procPath)) return;
            JsonUtil.updateFile(procPath, data -> {
                if (!boundProcessGeneration.equals(data.get("ProcessGeneration"))) return;
                ProcessState current = ProcessState.restore(data.get("ProcessState"));
                if (current.isTerminal()) return;
                if (state == ProcessState.PAUSED && current == ProcessState.READY) {
                    data.put("ProcessState", ProcessState.PAUSED.name());
                    data.put("ResumeState", ProcessState.READY.name());
                    data.put("StateMessage", "Waiting for terminal input");
                } else if (state == ProcessState.READY && current == ProcessState.PAUSED) {
                    data.put("ProcessState", ProcessState.READY.name());
                    data.put("StateMessage", null);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static int countBraces(String line) {
        int count = 0;
        boolean inString = false;
        boolean inSingleLineComment = false;
        char stringChar = '"';
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inSingleLineComment) continue;

            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == stringChar) { inString = false; }
                continue;
            }

            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
                continue;
            }

            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                inSingleLineComment = true;
                continue;
            }

            if (c == '{') count++;
            if (c == '}') count--;
        }
        return count;
    }
}
