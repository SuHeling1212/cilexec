package com.follarce.kernel.process;

import com.follarce.kernel.Constants;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creates independent FCL processes requested by a host control plane. */
public final class ProcessLauncher {

    public int start(String scriptPath, String user, String requestedName, int priority) {
        validateUser(user);
        validatePriority(priority);
        String resolvedPath = PathUtil.resolvePath(scriptPath, user, Map.of());
        if (!FileUtil.exists(resolvedPath)) {
            throw new IllegalArgumentException("Script not found: " + resolvedPath);
        }
        if (!FileUtil.checkFilePermission(resolvedPath, Constants.PERM_READ, user)) {
            throw new IllegalArgumentException("Permission denied: read " + resolvedPath);
        }

        String source = FileUtil.read(resolvedPath);
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Script is empty: " + resolvedPath);
        }
        List<String> codeLines = new CodeLoader().loadFromString(source);
        if (codeLines.isEmpty()) {
            throw new IllegalArgumentException("Script contains no executable code: " + resolvedPath);
        }

        ProcessFileAllocator.Reservation reservation = ProcessFileAllocator.reserve(
                "shell-run-" + UUID.randomUUID(), 0, "host-shell");
        try {
            Map<String, Object> snapshot = createSnapshot(
                    reservation.pid(), reservation.generation(), resolvedPath,
                    user, processName(resolvedPath, requestedName), priority, codeLines);
            ProcessFileAllocator.publish(reservation, snapshot);
            return reservation.pid();
        } catch (RuntimeException e) {
            try {
                ProcessFileAllocator.release(reservation);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private static Map<String, Object> createSnapshot(int pid, String generation,
                                                       String scriptPath, String user,
                                                       String name, int priority,
                                                       List<String> codeLines) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", name);
        process.put("Owner", user);
        process.put("EffectiveUser", user);
        process.put("ProcessGeneration", generation);
        process.put("PathAliases", new LinkedHashMap<String, String>());
        process.put("PID", pid);
        process.put("Path", scriptPath);
        // The snapshot is fully published, so it is immediately controllable before discovery.
        process.put("ProcessState", ProcessState.READY.name());
        process.put("BlockReason", null);
        process.put("ExitReason", null);
        process.put("StateMessage", null);
        process.put("startTime", FileUtil.getCurrentTimeArray());
        process.put("RunningTime", 0);
        process.put("Priority", priority);
        process.put("Parent", new LinkedHashMap<String, Object>());
        process.put("Child", new LinkedHashMap<String, Object>());
        process.put("ExitedChildren", new LinkedHashMap<String, Object>());
        process.put("ReapedChildren", new LinkedHashMap<String, Object>());

        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("NextAttemptOrdinal", 0L);
        process.put("Execution", execution);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("__current_script", scriptPath);
        Map<String, Object> code = new LinkedHashMap<>();
        code.put("Code", new ArrayList<>(codeLines));
        code.put("runningCodeLine", 0);
        code.put("BlockStack", new ArrayList<>());
        Map<String, Object> program = new LinkedHashMap<>();
        program.put("Data", data);
        program.put("Code", code);
        program.put("CallStack", new ArrayList<>());
        program.put("pendingAssignVarName", null);
        program.put("PackageDataByFunction", new LinkedHashMap<String, String>());
        program.put("imports", new ArrayList<String>());
        process.put("Program", program);
        return process;
    }

    private static String processName(String path, String requestedName) {
        if (requestedName != null && !requestedName.isBlank()) return requestedName.trim();
        String name = PathUtil.getFileName(path);
        int extension = name.lastIndexOf('.');
        return extension > 0 ? name.substring(0, extension) : name;
    }

    private static void validateUser(String user) {
        if (user == null || !UserUtil.getListOfUsers().containsKey(user)) {
            throw new IllegalArgumentException("Unknown user: " + user);
        }
    }

    private static void validatePriority(int priority) {
        if (priority != Constants.PRIORITY_LOW
                && priority != Constants.PRIORITY_NORMAL
                && priority != Constants.PRIORITY_HIGH) {
            throw new IllegalArgumentException("Priority must be 1 (low), 3 (normal), or 5 (high)");
        }
    }
}
