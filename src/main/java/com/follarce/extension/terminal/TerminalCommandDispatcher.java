package com.follarce.extension.terminal;

import com.follarce.extension.pack.PackageManager;
import com.follarce.kernel.Constants;
import com.follarce.kernel.process.*;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.io.File;
import java.util.*;

public class TerminalCommandDispatcher {
    private final TerminalSession session;
    private final TerminalOutput output;
    private final Scheduler scheduler;
    private final InteractiveCodeSubmitter submitter;

    public TerminalCommandDispatcher(TerminalSession session, TerminalOutput output,
                                     Scheduler scheduler,
                                     InteractiveCodeSubmitter submitter) {
        this.session = session;
        this.output = output;
        this.scheduler = scheduler;
        this.submitter = submitter;
    }

    public boolean dispatch(String command) {
        String[] parts = command.substring(1).split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String args = parts.length > 1 ? parts[1].trim() : "";

        return switch (name) {
            case "help" -> { showHelp(); yield true; }
            case "ps" -> { showProcesses(); yield true; }
            case "cat" -> { cat(args); yield true; }
            case "package", "pkg" -> { packageCommand(args); yield true; }
            case "run" -> { runScript(args); yield true; }
            case "clear" -> { clear(); yield true; }
            case "new" -> { newProcess(); yield true; }
            case "exit" -> { exit(); yield false; }
            case "left" -> { left(); yield false; }
            case "open" -> { open(args); yield true; }
            case "cd" -> { changeDirectory(args); yield true; }
            default -> { output.writeError("TerminalError: unknown command '/" + name
                    + "'. Type /help for available commands."); yield true; }
        };
    }

    private void showHelp() {
        output.writeSystemMessage("FCL Terminal Commands:");
        output.writeSystemMessage("  /help           Show this help");
        output.writeSystemMessage("  /ps             List all processes");
        output.writeSystemMessage("  /cat <path>     View file content");
        output.writeSystemMessage("  /package ...    Package management");
        output.writeSystemMessage("  /run <path>     Run FCL script");
        output.writeSystemMessage("  /clear          Clear terminal display");
        output.writeSystemMessage("  /new            Create new terminal process context");
        output.writeSystemMessage("  /exit           Exit terminal");
        output.writeSystemMessage("  /left           Leave terminal (keep processes)");
        output.writeSystemMessage("  /open <pkg>     Open/run a package");
        output.writeSystemMessage("  /cd <path>      Change working directory");
        output.writeSystemMessage("");
        output.writeSystemMessage("FCL Input:");
        output.writeSystemMessage("  Enter FCL code directly without any prefix.");
        output.writeSystemMessage("  Multi-line input: braces {} must be balanced before execution.");
        output.writeSystemMessage("  Control+C during execution: interrupts current code submission.");
        output.writeSystemMessage("  Control+C during input: exits terminal (same as /left).");
        output.writeSystemMessage("  Control+C during multiline input: clears buffer, keeps terminal.");
    }

    @SuppressWarnings("unchecked")
    private void showProcesses() {
        List<Map<String, Object>> processes = new ArrayList<>();
        for (int pid : PathUtil.scanProcessFileNames().keySet()) {
            try {
                String path = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
                if (!FileUtil.exists(path)) continue;
                Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(path));
                if (Boolean.TRUE.equals(data.get("Reservation"))) continue;
                ProcessIdentity.ensureDefaults(data);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("pid", pid);
                info.put("name", data.getOrDefault("Name", "PID-" + pid));
                info.put("state", data.getOrDefault("ProcessState", "UNKNOWN"));
                info.put("bound", pid == session.getBoundPid());
                info.put("isRunning", !ProcessState.restore(data.get("ProcessState")).isTerminal());
                info.put("owner", data.getOrDefault("EffectiveUser",
                        data.getOrDefault("Owner", "local")));
                processes.add(info);
            } catch (Exception ignored) {
            }
        }
        processes.sort(Comparator.comparingInt(p -> ((Number) p.get("pid")).intValue()));

        output.writeSystemMessage(String.format("%-5s %-12s %-12s %-6s %-8s %s",
                "PID", "STATE", "OWNER", "BOUND", "RUNNING", "NAME"));
        for (Map<String, Object> p : processes) {
            output.writeSystemMessage(String.format("%-5d %-12s %-12s %-6s %-8s %s",
                    ((Number) p.get("pid")).intValue(),
                    p.get("state"),
                    p.get("owner"),
                    Boolean.TRUE.equals(p.get("bound")) ? "*" : "",
                    Boolean.TRUE.equals(p.get("isRunning")) ? "yes" : "no",
                    p.get("name")));
        }
    }

    private void cat(String args) {
        if (args.isEmpty()) {
            output.writeError("TerminalError: usage: /cat <path>");
            return;
        }
        try {
            String currentUser = Constants.DEFAULT_USER_LOCAL;
            String resolved = PathUtil.resolvePath(args, currentUser, Map.of());
            if (!FileUtil.exists(resolved)) {
                output.writeError("TerminalError: file not found: " + resolved);
                return;
            }
            if (!FileUtil.checkFilePermission(resolved, Constants.PERM_READ, currentUser)) {
                output.writeError("TerminalError: permission denied: " + resolved);
                return;
            }
            String content = FileUtil.read(resolved);
            if (content != null) {
                output.writeLine(content);
            }
        } catch (Exception e) {
            output.writeError("TerminalError: " + rootMessage(e));
        }
    }

    private void packageCommand(String args) {
        try {
            PackageManager pm = PackageManager.getInstance();
            String[] parts = args.split("\\s+", 2);
            String op = parts.length > 0 ? parts[0] : "";
            if ("list".equals(op)) {
                List<Map<String, Object>> pkgs = pm.list(Constants.DEFAULT_USER_LOCAL);
                output.writeLine(JsonUtil.toJson(pkgs));
            } else {
                output.writeError("TerminalError: /package " + op + " not yet fully supported in terminal. Use host shell for full package operations.");
            }
        } catch (Exception e) {
            output.writeError("TerminalError: " + rootMessage(e));
        }
    }

    private void runScript(String args) {
        if (args.isEmpty()) {
            output.writeError("TerminalError: usage: /run <path>");
            return;
        }
        try {
            String currentUser = Constants.DEFAULT_USER_LOCAL;
            String resolvedPath = PathUtil.resolvePath(args, currentUser, Map.of());
            if (!FileUtil.exists(resolvedPath)) {
                output.writeError("TerminalError: script not found: " + resolvedPath);
                return;
            }
            String source = FileUtil.read(resolvedPath);
            if (source == null || source.isBlank()) {
                output.writeError("TerminalError: script is empty: " + resolvedPath);
                return;
            }
            InteractiveCodeSubmitter.SubmissionResult result = submitter.submitCode(source);
            if (result.isOk()) {
                output.writeSystemMessage("Script executed: " + args);
            } else if (result.isInterrupted()) {
                output.writeSystemMessage("Script execution interrupted.");
            } else {
                output.writeError("TerminalError: script execution failed: " + result.message());
            }
        } catch (Exception e) {
            output.writeError("TerminalError: " + rootMessage(e));
        }
    }

    private void clear() {
        output.write("\033[2J\033[H");
        output.flush();
        output.writeSystemMessage("FCL Terminal");
        output.writeSystemMessage("Bound process: " + session.getBoundPid());
        output.writeSystemMessage("Working directory: " + session.getWorkingDirectory());
    }

    private void newProcess() {
        try {
            pauseProcess();

            int pid = session.getBoundPid();
            String procPath = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
            String gen = session.getBoundProcessGeneration();

            JsonUtil.updateFile(procPath, data -> {
                if (!gen.equals(data.get("ProcessGeneration"))) return;
                ProcessState st = ProcessState.restore(data.get("ProcessState"));
                if (st.isTerminal()) return;

                Map<String, Object> program = new LinkedHashMap<>();
                Map<String, Object> vars = new LinkedHashMap<>();
                Map<String, Object> codeMap = new LinkedHashMap<>();
                codeMap.put("Code", new ArrayList<String>());
                codeMap.put("runningCodeLine", 0);
                codeMap.put("BlockStack", new ArrayList<>());
                program.put("Data", vars);
                program.put("Code", codeMap);
                program.put("CallStack", new ArrayList<>());
                program.put("pendingAssignVarName", null);
                program.put("PackageDataByFunction", new LinkedHashMap<>());
                program.put("imports", new ArrayList<>());
                data.put("Program", program);

                Map<String, Object> execution = new LinkedHashMap<>();
                execution.put("NextAttemptOrdinal", 0L);
                data.put("Execution", execution);
                data.remove("ProcessState");
                data.put("ProcessState", ProcessState.PAUSED.name());
                data.put("ResumeState", ProcessState.READY.name());
                data.put("StateMessage", "Waiting for terminal input");
                data.put("Child", new LinkedHashMap<>());
                data.put("ExitedChildren", new LinkedHashMap<>());
                data.put("ReapedChildren", new LinkedHashMap<>());
            });

            session.setWorkingDirectory("/");
            session.setExecuting(false);
            session.clearInput();
            output.writeSystemMessage("New process context created for PID " + pid);
            output.writeSystemMessage("Working directory: " + session.getWorkingDirectory());
        } catch (Exception e) {
            output.writeError("TerminalError: failed to create new process: " + rootMessage(e));
        }
    }

    private void exit() {
        if (Constants.DELETE_PROCESS_FILE_ON_EXIT) {
            try {
                int pid = session.getBoundPid();
                ProcessRunner.terminateProcess(pid);
            } catch (Exception e) {
                output.writeError("TerminalError: " + rootMessage(e));
            }
        } else {
            pauseProcess();
        }
        output.writeSystemMessage("Exiting terminal.");
        session.close();
    }

    private void left() {
        pauseProcess();
        output.writeSystemMessage("Leaving terminal. Process preserved.");
        session.close();
    }

    private void open(String args) {
        if (args.isEmpty()) {
            output.writeError("TerminalError: usage: /open <package>");
            return;
        }
        try {
            PackageManager pm = PackageManager.getInstance();
            Map<String, Object> info = pm.info(Constants.DEFAULT_USER_LOCAL, args);
            if (info != null && !info.isEmpty()) {
                Object entryPoint = info.get("EntryPoint");
                Object packageDataPath = info.get("PackageDataPath");
                if (entryPoint instanceof String ep && !ep.isBlank()) {
                    String sourcePath = packageDataPath instanceof String pdp
                            ? pdp + "/" + ep : ep;
                    if (FileUtil.exists(sourcePath)) {
                        String source = FileUtil.read(sourcePath);
                        if (source != null) {
                            InteractiveCodeSubmitter.SubmissionResult result = submitter.submitCode(source);
                            if (result.isOk()) {
                                output.writeSystemMessage("Package opened: " + args);
                            } else {
                                output.writeError("TerminalError: package execution failed");
                            }
                            return;
                        }
                    }
                }
            }
            output.writeError("TerminalError: could not open package: " + args);
        } catch (Exception e) {
            output.writeError("TerminalError: " + rootMessage(e));
        }
    }

    private void changeDirectory(String args) {
        if (args.isEmpty()) {
            output.writeSystemMessage(session.getWorkingDirectory());
            return;
        }
        try {
            String currentUser = Constants.DEFAULT_USER_LOCAL;
            String target;
            if (args.startsWith("/")) {
                target = args;
            } else {
                String base = session.getWorkingDirectory();
                if (!base.endsWith("/")) base += "/";
                target = base + args;
            }
            target = PathUtil.normalizePath(target);
            String resolved = PathUtil.resolvePath(target, currentUser, Map.of());

            if (!FileUtil.exists(resolved)) {
                output.writeError("TerminalError: directory does not exist: " + target);
                return;
            }
            Map<String, Object> dirMeta = FileUtil.readFileMetaData(resolved);
            if (dirMeta != null && "file".equals(dirMeta.get("Type"))) {
                output.writeError("TerminalError: not a directory: " + target);
                return;
            }
            if (!FileUtil.checkFilePermission(resolved, Constants.PERM_READ, currentUser)) {
                output.writeError("TerminalError: permission denied: " + target);
                return;
            }

            session.setWorkingDirectory(target);
            updateProcessWorkingDir(target);
            output.writeSystemMessage("Working directory: " + target);
        } catch (Exception e) {
            output.writeError("TerminalError: " + rootMessage(e));
        }
    }

    private void updateProcessWorkingDir(String dir) {
        String procPath = Constants.SYSTEM_PROCESS_PATH + session.getBoundPid() + ".proc";
        String gen = session.getBoundProcessGeneration();
        JsonUtil.updateFile(procPath, data -> {
            if (!gen.equals(data.get("ProcessGeneration"))) return;
            Map<String, Object> program = (Map<String, Object>) data.get("Program");
            if (program == null) return;
            Map<String, Object> vars = (Map<String, Object>) program.get("Data");
            if (vars == null) return;
            vars.put(Constants.TERMINAL_WORKING_DIR_FIELD, dir);
        });
    }

    private void pauseProcess() {
        try {
            String procPath = Constants.SYSTEM_PROCESS_PATH + session.getBoundPid() + ".proc";
            String gen = session.getBoundProcessGeneration();
            JsonUtil.updateFile(procPath, data -> {
                if (!gen.equals(data.get("ProcessGeneration"))) return;
                ProcessState st = ProcessState.restore(data.get("ProcessState"));
                if (!st.isTerminal() && st != ProcessState.PAUSED) {
                    data.put("ProcessState", ProcessState.PAUSED.name());
                    data.put("ResumeState", ProcessState.READY.name());
                    data.put("StateMessage", "Waiting for terminal input");
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
