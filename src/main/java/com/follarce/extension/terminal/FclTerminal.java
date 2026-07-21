package com.follarce.extension.terminal;

import com.follarce.kernel.Constants;
import com.follarce.kernel.log.Logger;
import com.follarce.kernel.process.*;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FclTerminal {
    private final BufferedReader input;
    private final TerminalOutput output;
    private final TerminalSession session;
    private final TerminalCommandDispatcher commandDispatcher;
    private final InteractiveCodeSubmitter submitter;
    private final Scheduler scheduler;
    private final Runnable shutdownAction;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();

    public FclTerminal(Reader input, PrintWriter writer, Scheduler scheduler,
                       Runnable shutdownAction) {
        this.input = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
        this.output = new WriterTerminalOutput(writer);
        this.scheduler = scheduler;
        this.shutdownAction = shutdownAction;
        this.session = new TerminalSession(output);
        this.submitter = new InteractiveCodeSubmitter(session);
        this.commandDispatcher = new TerminalCommandDispatcher(session, output, scheduler, submitter);
    }

    public void run() {
        try {
            initializeTerminalProcess();

            output.writeLine("\033[2J\033[H");
            output.writeLine("FCL Terminal");
            output.writeLine("Bound process: PID " + session.getBoundPid());
            output.writeLine("Working directory: " + session.getWorkingDirectory());
            output.writeLine("Type /help for available commands.");
            output.writeLine("");

            boolean running = true;
            while (running && session.isActive()) {
                running = readAndExecute();
            }
        } catch (Exception e) {
            output.writeError("TerminalError: " + rootMessage(e));
        } finally {
            shutdown();
        }
    }

    private void initializeTerminalProcess() {
        int pid = Constants.TERMINAL_DEFAULT_PID;
        String procPath = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";

        if (!FileUtil.exists(procPath)) {
            createTerminalProcess(pid);
        }

        session.bindProcess(pid);
        session.setBoundThread(Thread.currentThread());
        session.setWorkingDirectory("/");

        output.writeSystemMessage("Waiting for PID " + pid + " to complete...");

        boolean finished = waitForPidToFinish(pid);
        if (!finished) {
            output.writeSystemMessage("Interrupting PID " + pid + " to take over.");
            ensureProcessPaused(pid);
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        markTerminalBound(pid);

        boolean terminal = isProcessTerminal(pid);
        if (terminal) {
            resetTerminalProcess(pid);
            createRunnerForPid(pid);
        }

        ensureProcessPaused(pid);
    }

    private boolean waitForPidToFinish(int pid) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            String path = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
            if (!FileUtil.exists(path)) return false;
            Map<String, Object> data;
            try { data = JsonUtil.parseToMapStrict(FileUtil.read(path)); }
            catch (Exception e) { continue; }
            if (!session.getBoundProcessGeneration().equals(data.get("ProcessGeneration"))) return false;
            ProcessState st = ProcessState.restore(data.get("ProcessState"));
            if (st.isTerminal()) return true;
            if (st == ProcessState.PAUSED) return true;
        }
        return false;
    }

    private void markTerminalBound(int pid) {
        String path = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
        String gen = session.getBoundProcessGeneration();
        JsonUtil.updateFile(path, data -> {
            if (!gen.equals(data.get("ProcessGeneration"))) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> program = (Map<String, Object>) data.computeIfAbsent(
                    "Program", k -> new LinkedHashMap<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> vars = (Map<String, Object>) program.computeIfAbsent(
                    "Data", k -> new LinkedHashMap<>());
            vars.put(Constants.TERMINAL_ATTACHED_FIELD, true);
            vars.putIfAbsent(Constants.TERMINAL_WORKING_DIR_FIELD, "/");
            vars.put(Constants.TERMINAL_SUBMISSION_START, -1);
            vars.put(Constants.TERMINAL_SUBMISSION_END, -1);
            vars.put(Constants.TERMINAL_INTERRUPT_FIELD, false);
        });
        ProcessRunner.unparkProcess(pid);
    }

    private boolean isProcessTerminal(int pid) {
        String path = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
        if (!FileUtil.exists(path)) return true;
        try {
            Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(path));
            return ProcessState.restore(data.get("ProcessState")).isTerminal();
        } catch (Exception e) { return true; }
    }

    private void resetTerminalProcess(int pid) {
        String path = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
        JsonUtil.updateFile(path, data -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> program = (Map<String, Object>) data.computeIfAbsent(
                    "Program", k -> new LinkedHashMap<>());
            program.computeIfAbsent("Data", k -> new LinkedHashMap<>());
            program.computeIfAbsent("Code", k -> new LinkedHashMap<>());
            program.computeIfAbsent("CallStack", k -> new ArrayList<>());

            data.put("ProcessState", ProcessState.PAUSED.name());
            data.put("StateMessage", "Waiting for terminal input");
            data.remove("ExitReason");
            data.remove("BlockReason");
            data.remove("LifecycleCleanup");
            data.remove("TerminationCleanup");
            data.put("RunningTime", 0);
            data.put("Execution", new LinkedHashMap<>(Map.of("NextAttemptOrdinal", 0L)));
        });
        session.bindProcess(pid);
    }

    private void createRunnerForPid(int pid) {
        try {
            String path = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
            if (!FileUtil.exists(path)) return;
            ProcessRunner existing = scheduler.getProcess(pid);
            if (existing != null && existing.isRunning()) return;
            String content = FileUtil.read(path);
            Map<String, Object> pd = JsonUtil.parseToMapStrict(content);
            ProcessIdentity.ensureDefaults(pd);
            ProcessRunner newRunner = new ProcessRunner(pid, pd);
            newRunner.init();
            scheduler.addProcess(newRunner);
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } catch (Exception e) {
            Logger.warn("Failed to create runner for PID " + pid + ": " + e.getMessage());
        }
    }

    private void ensureProcessPaused(int pid) {
        String path = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
        if (!FileUtil.exists(path)) return;
        try {
            Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(path));
            ProcessState st = ProcessState.restore(data.get("ProcessState"));
            if (!st.isTerminal() && st != ProcessState.PAUSED) {
                ProcessRunner.postMessage(pid, "ProcessState", ProcessState.PAUSED.name());
                ProcessRunner.unparkProcess(pid);
            }
        } catch (Exception ignored) {}
    }

    private void createTerminalProcess(int pid) {
        String procPath = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
        String generation = ProcessIdentity.newGeneration();

        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("Name", Constants.TERMINAL_DEFAULT_PROCESS);
        processData.put("Owner", Constants.DEFAULT_USER_LOCAL);
        processData.put("EffectiveUser", Constants.DEFAULT_USER_LOCAL);
        processData.put("ProcessGeneration", generation);
        processData.put("PathAliases", new LinkedHashMap<String, String>());
        processData.put("PID", pid);
        processData.put("Path", "/");
        processData.put("ProcessState", ProcessState.PAUSED.name());
        processData.put("BlockReason", null);
        processData.put("ExitReason", null);
        processData.put("StateMessage", "Waiting for terminal input");
        processData.put("startTime", FileUtil.getCurrentTimeArray());
        processData.put("RunningTime", 0);
        processData.put("Priority", Constants.PRIORITY_NORMAL);
        processData.put("Parent", new LinkedHashMap<>());
        processData.put("Child", new LinkedHashMap<>());
        processData.put("ExitedChildren", new LinkedHashMap<>());
        processData.put("ReapedChildren", new LinkedHashMap<>());

        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("NextAttemptOrdinal", 0L);
        processData.put("Execution", execution);

        Map<String, Object> program = new LinkedHashMap<>();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("__current_script", "/");
        vars.put(Constants.TERMINAL_ATTACHED_FIELD, true);
        vars.put(Constants.TERMINAL_WORKING_DIR_FIELD, "/");
        vars.put(Constants.TERMINAL_SUBMISSION_START, -1);
        vars.put(Constants.TERMINAL_SUBMISSION_END, -1);
        vars.put(Constants.TERMINAL_INTERRUPT_FIELD, false);
        program.put("Data", vars);

        Map<String, Object> codeMap = new LinkedHashMap<>();
        codeMap.put("Code", new ArrayList<String>());
        codeMap.put("runningCodeLine", 0);
        codeMap.put("BlockStack", new ArrayList<>());
        program.put("Code", codeMap);
        program.put("CallStack", new ArrayList<>());
        program.put("pendingAssignVarName", null);
        program.put("PackageDataByFunction", new LinkedHashMap<String, String>());
        program.put("imports", new ArrayList<String>());
        processData.put("Program", program);

        String json = JsonUtil.toMetaJson(processData);
        JsonUtil.writeFile(procPath, json);
        Logger.info("Created terminal process PID " + pid);
    }

    private boolean readAndExecute() {
        try {
            output.write(session.getPrompt());
            output.flush();
            String line = input.readLine();
            if (line == null) return false;
            line = line.trim();

            if (session.isMultiline()) {
                return handleMultilineInput(line);
            }
            if (line.isEmpty()) return true;
            if (line.startsWith("/")) {
                return commandDispatcher.dispatch(line);
            }
            if (isPotentiallyMultiline(line)) {
                session.appendInput(line);
                if (session.isMultiline()) return true;
                return executeFclCode(session.getAndClearInput());
            }
            return executeFclCode(line);
        } catch (InterruptedIOException e) {
            return handleInterrupt();
        } catch (IOException e) {
            output.writeError("TerminalError: input error: " + e.getMessage());
            return true;
        }
    }

    private boolean handleMultilineInput(String line) {
        session.appendInput(line.trim());
        if (!session.isMultiline()) {
            return executeFclCode(session.getAndClearInput());
        }
        return true;
    }

    private boolean isPotentiallyMultiline(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("if ") || trimmed.startsWith("if(")
                || trimmed.startsWith("while ") || trimmed.startsWith("while(")
                || trimmed.startsWith("switch ") || trimmed.startsWith("switch(")
                || trimmed.startsWith("func ") || trimmed.equals("func");
    }

    private boolean executeFclCode(String code) {
        session.clearTerminalOutput();
        InteractiveCodeSubmitter.SubmissionResult result = submitter.submitCode(code);

        List<String> terminalOut = session.readTerminalOutput();
        for (String out : terminalOut) {
            output.writeLine(out);
        }
        session.clearTerminalOutput();

        if (result.isInterrupted()) {
            output.writeSystemMessage("Execution interrupted.");
            output.writeSystemMessage("Process " + session.getBoundPid() + " suspended.");
        } else if (result.isError()) {
            output.writeError("FclRuntimeError: " + result.message());
        }
        return true;
    }

    private boolean handleInterrupt() {
        if (session.isExecuting()) {
            session.requestInterrupt();
            output.writeLine("");
            output.writeSystemMessage("Execution interrupted.");
            output.writeSystemMessage("Process " + session.getBoundPid() + " suspended.");
            return true;
        } else if (session.isMultiline()) {
            session.clearInput();
            output.writeLine("");
            output.writeSystemMessage("Multiline input cleared.");
            return true;
        } else {
            commandDispatcher.dispatch("/left");
            return false;
        }
    }

    private void shutdown() {
        if (shutdownRequested.compareAndSet(false, true)) {
            try { ensureProcessPaused(session.getBoundPid()); } catch (Exception ignored) {}
            session.close();
            shutdownAction.run();
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static class WriterTerminalOutput implements TerminalOutput {
        private final PrintWriter writer;
        WriterTerminalOutput(PrintWriter writer) { this.writer = writer; }
        @Override public void write(String text) { writer.print(text); }
        @Override public void writeLine(String text) { writer.println(text); }
        @Override public void writeResult(Object value) { writer.println(value == null ? "null" : value.toString()); }
        @Override public void writeError(String error) { writer.println(error); }
        @Override public void writeSystemMessage(String message) { writer.println(message); }
        @Override public void flush() { writer.flush(); }
    }
}
