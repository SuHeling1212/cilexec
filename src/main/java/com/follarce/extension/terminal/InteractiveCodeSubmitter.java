package com.follarce.extension.terminal;

import com.follarce.kernel.Constants;
import com.follarce.kernel.process.ProcessRunner;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.vfs.FileUtil;

import java.util.*;

public class InteractiveCodeSubmitter {
    private final TerminalSession session;

    public InteractiveCodeSubmitter(TerminalSession session) {
        this.session = session;
    }

    public SubmissionResult submitCode(String code) {
        if (code == null || code.isBlank()) return SubmissionResult.empty();

        int pid = session.getBoundPid();
        String procPath = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";

        if (!FileUtil.exists(procPath)) {
            return SubmissionResult.error("Process file not found: PID " + pid);
        }

        try {
            List<String> lines = new ArrayList<>();
            for (String line : code.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) lines.add(trimmed);
            }
            if (lines.isEmpty()) return SubmissionResult.empty();

            String joinedCode = String.join("\n", lines);
            int oldCodeSize = session.getProcessCodeSize();

            ProcessRunner.postMessage(pid, Constants.TERMINAL_APPEND_CODE, joinedCode);
            ProcessRunner.unparkProcess(pid);

            session.setExecuting(true);
            session.setSubmissionBounds(oldCodeSize, oldCodeSize + lines.size());

            long startTime = System.currentTimeMillis();
            while (session.isExecuting() && session.isActive()) {
                try { Thread.sleep(Constants.TERMINAL_POLL_INTERVAL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }

                if (session.isInterruptRequested()) {
                    ProcessRunner.postMessage(pid, Constants.TERMINAL_INTERRUPT_FIELD, true);
                    ProcessRunner.unparkProcess(pid);
                    waitForPause();
                    session.setExecuting(false);
                    return SubmissionResult.interrupted();
                }

                if (System.currentTimeMillis() - startTime > Constants.TERMINAL_EXEC_TIMEOUT_MS) {
                    session.setExecuting(false);
                    return SubmissionResult.error("Execution timed out");
                }

                if (session.isProcessTerminal()) {
                    session.setExecuting(false);
                    return SubmissionResult.error("Process terminated during execution");
                }

                if (session.isProcessPaused()) {
                    int cl = session.getProcessCurrentLine();
                    if (cl >= oldCodeSize + lines.size()) {
                        session.setExecuting(false);
                        break;
                    }
                }

                int cl = session.getProcessCurrentLine();
                if (cl >= oldCodeSize + lines.size()) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    if (session.isProcessPaused()) {
                        session.setExecuting(false);
                        break;
                    }
                }
            }

            return SubmissionResult.ok(lines.size());
        } catch (Exception e) {
            return SubmissionResult.error("Submission error: " + rootMsg(e));
        }
    }

    private void waitForPause() {
        int pid = session.getBoundPid();
        for (int i = 0; i < 100; i++) {
            try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            if (session.isProcessPaused() || session.isProcessTerminal()) return;
            ProcessRunner.postMessage(pid, "ProcessState", ProcessState.PAUSED.name());
        }
    }

    private static String rootMsg(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    public record SubmissionResult(String status, String message, int submissionCount) {
        public boolean isOk() { return "ok".equals(status); }
        public boolean isEmpty() { return "empty".equals(status); }
        public boolean isError() { return "error".equals(status); }
        public boolean isInterrupted() { return "interrupted".equals(status); }
        public static SubmissionResult ok(int c) { return new SubmissionResult("ok", null, c); }
        public static SubmissionResult empty() { return new SubmissionResult("empty", null, 0); }
        public static SubmissionResult error(String m) { return new SubmissionResult("error", m, 0); }
        public static SubmissionResult interrupted() { return new SubmissionResult("interrupted", "Execution interrupted", 0); }
    }
}
