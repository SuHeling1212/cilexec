package com.follarce.extension.terminal;

import com.follarce.extension.pack.PackageManager;
import com.follarce.kernel.Constants;
import com.follarce.kernel.process.ProcessIdentity;
import com.follarce.kernel.process.ProcessLauncher;
import com.follarce.kernel.process.ProcessRunner;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Disk-backed operations exposed to the host shell. */
public final class SystemControlService {
    private static final String SHELL_GENERATION = "host-shell";

    private final ProcessLauncher processLauncher;
    private final PackageManager packageManager;

    public SystemControlService() {
        this(new ProcessLauncher(), PackageManager.getInstance());
    }

    public SystemControlService(ProcessLauncher processLauncher, PackageManager packageManager) {
        this.processLauncher = processLauncher;
        this.packageManager = packageManager;
    }

    public record ProcessSummary(int pid, String name, String user, ProcessState state,
                                 int priority, long runningTime, String path) {}

    public List<ProcessSummary> listProcesses() {
        List<ProcessSummary> result = new ArrayList<>();
        for (int pid : PathUtil.scanProcessFileNames().keySet()) {
            try {
                Map<String, Object> process = readProcessSnapshot(pid);
                result.add(new ProcessSummary(
                        pid,
                        text(process.get("Name"), "PID-" + pid),
                        text(process.get("EffectiveUser"), text(process.get("Owner"), "local")),
                        ProcessState.restore(process.get("ProcessState")),
                        number(process.get("Priority"), Constants.DEFAULT_PRIORITY).intValue(),
                        number(process.get("RunningTime"), 0L).longValue(),
                        text(process.get("Path"), "")));
            } catch (IllegalArgumentException ignored) {
                // A reservation or a transiently replaced snapshot is not a visible process.
            }
        }
        result.sort(Comparator.comparingInt(ProcessSummary::pid));
        return result;
    }

    public Map<String, Object> inspectProcess(int pid) {
        return JsonUtil.deepCopy(readProcessSnapshot(pid));
    }

    public int startProcess(String scriptPath, String user, String name, int priority) {
        return processLauncher.start(scriptPath, user, name, priority);
    }

    public void pauseProcess(int pid) {
        postState(pid, ProcessState.PAUSED);
    }

    public void continueProcess(int pid) {
        postState(pid, ProcessState.READY);
    }

    public void killProcess(int pid) {
        rejectInitControl(pid);
        Map<String, Object> process = readControllableProcess(pid);
        String generation = ProcessIdentity.generation(process);
        boolean published = ProcessRunner.requestTermination(
                pid, generation, messageId("kill", pid), 0, SHELL_GENERATION);
        if (!published) throw new IllegalStateException("Process changed before kill was delivered: " + pid);
    }

    public List<Map<String, Object>> listPackages(String user) {
        validateUser(user);
        return packageManager.list(user);
    }

    public Map<String, Object> buildPackage(String user, String source, String output) {
        validateUser(user);
        return packageManager.build(user, resolveUserPath(source, user), resolveUserPath(output, user));
    }

    public Map<String, Object> installPackage(String user, String source, String binding,
                                              String repository) {
        validateUser(user);
        return packageManager.install(user, resolveUserPath(source, user), binding,
                repository == null ? null : resolveUserPath(repository, user),
                messageId("package-install", 0), 0, SHELL_GENERATION);
    }

    public Map<String, Object> removePackage(String user, String binding) {
        validateUser(user);
        return packageManager.remove(user, binding,
                messageId("package-remove", 0), 0, SHELL_GENERATION);
    }

    public Map<String, Object> packageInfo(String user, String binding) {
        validateUser(user);
        return packageManager.info(user, binding);
    }

    public Map<String, Object> verifyPackage(String user, String binding) {
        validateUser(user);
        return packageManager.verify(user, binding);
    }

    public Map<String, Object> pinPackage(String user, String bindingOrIntegrity) {
        validateUser(user);
        return packageManager.pin(user, bindingOrIntegrity);
    }

    public boolean unpinPackage(String user, String bindingOrIntegrity) {
        validateUser(user);
        return packageManager.unpin(user, bindingOrIntegrity);
    }

    public Map<String, Object> garbageCollectPackages() {
        return packageManager.garbageCollect();
    }

    public void recoverPackages() {
        packageManager.recoverTransactions();
    }

    private void postState(int pid, ProcessState requestedState) {
        rejectInitControl(pid);
        Map<String, Object> process = readControllableProcess(pid);
        String generation = ProcessIdentity.generation(process);
        boolean published = ProcessRunner.postMessageToGeneration(
                pid, generation, "ProcessState", requestedState.name(),
                messageId(requestedState.name().toLowerCase(), pid), 0, SHELL_GENERATION);
        if (!published) {
            throw new IllegalStateException("Process changed before control was delivered: " + pid);
        }
    }

    private Map<String, Object> readControllableProcess(int pid) {
        Map<String, Object> process = readProcessSnapshot(pid);
        if (ProcessState.restore(process.get("ProcessState")).isTerminal()) {
            throw new IllegalArgumentException("Process is already terminal: " + pid);
        }
        return process;
    }

    private Map<String, Object> readProcessSnapshot(int pid) {
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
        String path = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
        if (!FileUtil.exists(path)) throw new IllegalArgumentException("Process not found: " + pid);
        if (!Constants.DEFAULT_USER_LOCAL.equals(FileUtil.readFileMetaData(path).get("Owner"))) {
            throw new IllegalArgumentException("Rejected non-system process snapshot: " + pid);
        }
        Map<String, Object> process = JsonUtil.parseToMapStrict(FileUtil.read(path));
        if (Boolean.TRUE.equals(process.get("Reservation"))) {
            throw new IllegalArgumentException("Process is still being created: " + pid);
        }
        Object savedPid = process.get("PID");
        if (!(savedPid instanceof Number) || ((Number) savedPid).intValue() != pid) {
            throw new IllegalArgumentException("Process snapshot PID mismatch: " + pid);
        }
        ProcessIdentity.generation(process);
        return process;
    }

    private static void rejectInitControl(int pid) {
        if (pid == Constants.PID_INIT) {
            throw new IllegalArgumentException("PID 1 is protected; use exit to stop Cilexec");
        }
    }

    private static void validateUser(String user) {
        if (user == null || !UserUtil.getListOfUsers().containsKey(user)) {
            throw new IllegalArgumentException("Unknown user: " + user);
        }
    }

    private static String messageId(String operation, int pid) {
        return "shell-" + operation + "-" + pid + "-" + UUID.randomUUID();
    }

    private static String resolveUserPath(String path, String user) {
        return PathUtil.resolvePath(path, user, Map.of());
    }

    private static Number number(Object value, Number fallback) {
        return value instanceof Number ? (Number) value : fallback;
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }
}
