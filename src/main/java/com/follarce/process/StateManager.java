package com.follarce.process;

import com.follarce.Constants;
import com.follarce.log.Logger;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;

import java.io.File;
import java.util.*;



/**
 * 进程状态持久化管理器 —— 负责 .proc 文件的读/写/清理。
 * <p>
 * 职责：
 * <ul>
 *   <li>从 {@code processData} 反序列化运行时状态</li>
 *   <li>从磁盘 .proc 文件重新加载状态</li>
 *   <li>将当前状态写入 .proc 文件</li>
 *   <li>清理瞬态字段（不落盘字段）</li>
 *   <li>进程终止后清理父进程 Child 列表 / 删除进程文件</li>
 * </ul>
 * <p>
 * 原则：磁盘是唯一真相，内存只是瞬态暂存。
 */
public class StateManager {

    private final int pid;
    private final long processStartMs;
    private final long previousRunningSeconds;
    private Map<String, Object> processData;
    private ProcessState state;
    private BlockReason blockReason;
    private ExitReason exitReason;
    private String stateMessage;
    private String expectedGeneration;

    public StateManager(int pid, long processStartMs, Map<String, Object> processData) {
        this.pid = pid;
        this.processStartMs = processStartMs;
        this.processData = processData;
        Object previous = processData.get("RunningTime");
        this.previousRunningSeconds = previous instanceof Number ? ((Number) previous).longValue() : 0L;
        loadLifecycle();
    }

    // ════════════════════════════════════════════
    // 进程数据访问
    // ════════════════════════════════════════════

    public Map<String, Object> getProcessData() { return processData; }

    public int getPid() { return pid; }

    public ProcessState getState() { return state; }

    public BlockReason getBlockReason() { return blockReason; }

    public ExitReason getExitReason() { return exitReason; }

    public String getStateMessage() { return stateMessage; }

    public void setExpectedGeneration(String generation) {
        if (generation == null || generation.isBlank()) {
            throw new IllegalArgumentException("Expected process generation is required");
        }
        if (expectedGeneration != null && !expectedGeneration.equals(generation)) {
            throw new IllegalStateException("Process generation cannot change for PID " + pid);
        }
        expectedGeneration = generation;
    }

    public void setLifecycle(ProcessState state, BlockReason blockReason,
                             ExitReason exitReason, String stateMessage) {
        this.state = state;
        this.blockReason = blockReason != null ? blockReason : BlockReason.NONE;
        this.exitReason = exitReason != null ? exitReason : ExitReason.NONE;
        this.stateMessage = stateMessage;
    }

    public int extractPriority() {
        Object priorityObj = processData.get("Priority");
        if (priorityObj instanceof Number) {
            int p = ((Number) priorityObj).intValue();
            if (p == Constants.PRIORITY_HIGH || p == Constants.PRIORITY_LOW) {
                return p;
            }
        }
        return Constants.PRIORITY_NORMAL;
    }

    public String extractOwner() {
        Object owner = processData.get("Owner");
        return owner instanceof String ? (String) owner : Constants.DEFAULT_USER_LOCAL;
    }

    public String extractName() {
        Object name = processData.get("Name");
        return name != null ? name.toString() : "PID-" + pid;
    }

    @SuppressWarnings("unchecked")
    public int extractParentPid() {
        Object parent = processData.get("Parent");
        if (parent instanceof Map) {
            Object parentPid = ((Map<String, Object>) parent).get("PID");
            if (parentPid instanceof Number) return ((Number) parentPid).intValue();
        }
        return 0;
    }

    // ════════════════════════════════════════════
    // 文件 I/O
    // ════════════════════════════════════════════

    /**
     * 从磁盘 .proc 文件重新加载进程数据。
     * 调度器调用 checkWakeup() 前会调用此方法。
     */
    @SuppressWarnings("unchecked")
    public void loadFromFile() {
        String path = PathUtil.findProcessFilePathByPid(pid);
        if (path == null) return;
        String content = FileUtil.read(path);
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalStateException("Empty process snapshot for PID " + pid);
        }
        processData = JsonUtil.parseToMapStrict(content);
        ProcessIdentity.ensureDefaults(processData);
        if (expectedGeneration != null
                && !expectedGeneration.equals(processData.get("ProcessGeneration"))) {
            throw new IllegalStateException("PID " + pid + " now belongs to another process generation");
        }
        loadLifecycle();
    }

    /**
     * 从 {@code processData} 中提取运行时状态，返回 {@link RuntimeSnapshot}。
     * 在 ProcessRunner 构造时和每次 step() 开始时调用。
     */
    @SuppressWarnings("unchecked")
    public RuntimeSnapshot loadFromProcessData() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<String> codeLines = new ArrayList<>();
        int currentLine = 0;
        List<Map<String, Object>> blockStack = new ArrayList<>();
        List<Map<String, Object>> callStackData = new ArrayList<>();
        String pendingAssignVar = null;
        List<String> imports = new ArrayList<>();

        Map<String, Object> program = (Map<String, Object>) processData.get("Program");
        if (program != null) {
            Object dataObj = program.get("Data");
            if (dataObj instanceof Map) {
                data = (Map<String, Object>) dataObj;
            }

            Object codeObj = program.get("Code");
            if (codeObj instanceof Map) {
                Map<String, Object> code = (Map<String, Object>) codeObj;
                Object linesObj = code.get("Code");
                if (linesObj instanceof List) {
                    codeLines = new ArrayList<>((List<String>) linesObj);
                }
                Object lineObj = code.get("runningCodeLine");
                currentLine = lineObj instanceof Number ? ((Number) lineObj).intValue() : 0;
                Object bsObj = code.get("BlockStack");
                if (bsObj instanceof List) {
                    blockStack = (List<Map<String, Object>>) bsObj;
                }
            }

            callStackData = (List<Map<String, Object>>) program.get("CallStack");
            pendingAssignVar = (String) program.get("pendingAssignVarName");

            Object savedImports = program.get("imports");
            if (savedImports instanceof List) {
                imports = new ArrayList<>((List<String>) savedImports);
            }
        }

        return new RuntimeSnapshot(
                data, codeLines, currentLine, blockStack,
                callStackData, pendingAssignVar, imports
        );
    }

    /**
     * 将运行时状态写入 .proc 文件。
     *
     * @param snapshot 当前运行时快照
     */
    @SuppressWarnings("unchecked")
    public void saveToFile(RuntimeSnapshot snapshot) {
        try {
            processData.put("ProcessState", state.name());
            processData.put("Status", !state.isTerminal());
            processData.put("BlockReason", blockReason == BlockReason.NONE ? null : blockReason.name());
            processData.put("ExitReason", exitReason == ExitReason.NONE ? null : exitReason.name());
            processData.put("StateMessage", stateMessage);
            processData.put("RunningTime", previousRunningSeconds
                    + (System.currentTimeMillis() - processStartMs) / 1000);

            Map<String, Object> program = (Map<String, Object>) processData.get("Program");
            if (program == null) {
                program = new LinkedHashMap<>();
                processData.put("Program", program);
            }

            // 写入 Data（进程变量）
            program.put("Data", snapshot.data);

            // ── 显式清除瞬态字段，确保 .proc 文件不包含过时数据 ──
            processData.remove("CallStack");
            program.remove("imports");

            // 持久化字段
            program.put("CallStack", snapshot.callStackData);         // 函数调用栈
            program.put("pendingAssignVarName", snapshot.pendingAssignVarName); // 待赋值变量名
            program.put("imports", snapshot.imports);

            // 写入 Code / runningCodeLine / BlockStack（持久化字段）
            Map<String, Object> code = new LinkedHashMap<>();
            code.put("Code", snapshot.codeLines);
            code.put("runningCodeLine",
                    snapshot.currentLine < snapshot.codeLines.size()
                            ? snapshot.currentLine : snapshot.codeLines.size());
            code.put("BlockStack", snapshot.blockStack);
            program.put("Code", code);

            String processPath = getProcessFilePath();
            java.util.concurrent.locks.ReentrantLock fileLock = JsonUtil.lockFile(processPath);
            try {
                if (!FileUtil.exists(processPath)) {
                    throw new IllegalStateException("Process snapshot was removed for PID " + pid);
                }
                Map<String, Object> current = JsonUtil.parseToMapStrict(FileUtil.read(processPath));
                Object currentGeneration = current.get("ProcessGeneration");
                Object expectedGeneration = processData.get("ProcessGeneration");
                Object requiredGeneration = this.expectedGeneration != null
                        ? this.expectedGeneration : expectedGeneration;
                if (currentGeneration != null && !Objects.equals(currentGeneration, requiredGeneration)) {
                    throw new IllegalStateException("Refusing stale write for reused PID " + pid);
                }
                JsonUtil.writeFile(processPath, JsonUtil.toMetaJson(processData));
            } finally {
                fileLock.unlock();
            }
        } catch (Exception e) {
            Logger.error("StateManager: failed to save PID " + pid + ": " + e.getMessage());
            throw e instanceof RuntimeException ? (RuntimeException) e
                    : new RuntimeException("Failed to persist PID " + pid, e);
        }
    }

    // ════════════════════════════════════════════
    // 进程生命周期
    // ════════════════════════════════════════════

    /** Reparent active children, notify the parent, then delete or retain the snapshot. */
    public void cleanup() {
        ProcessRunner.reconcileLifecycle(pid);
    }

    @SuppressWarnings("unchecked")
    private void notifyParentOfExit() {
        try {
            Map<String, Object> parent = (Map<String, Object>) processData.get("Parent");
            if (parent == null) return;
            Object ppidObj = parent.get("PID");
            if (!(ppidObj instanceof Number)) return;
            int ppid = ((Number) ppidObj).intValue();

            ProcessRunner.recordChildExit(ppid, pid, exitReason);
            Logger.info("StateManager: child " + pid + " exit recorded by parent " + ppid);
        } catch (Exception e) {
            Logger.warn("StateManager: failed to notify parent for PID " + pid + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void reparentChildrenToInit() {
        if (pid == Constants.PID_INIT) return;
        Object childrenObj = processData.get("Child");
        if (!(childrenObj instanceof Map)) return;

        Map<String, Object> children = new LinkedHashMap<>((Map<String, Object>) childrenObj);
        for (Map.Entry<String, Object> child : children.entrySet()) {
            try {
                int childPid = Integer.parseInt(child.getKey());
                ProcessRunner.reparentToInit(childPid, child.getValue());
            } catch (Exception e) {
                Logger.warn("StateManager: failed to reparent child " + child.getKey()
                        + " of PID " + pid + ": " + e.getMessage());
            }
        }
    }

    private void handleTermination() {
        try {
            if (pid == Constants.PID_INIT) {
                if (Constants.DELETE_PROCESS_FILE_ON_EXIT) {
                    clearAllProcessFiles();
                    Logger.info("INIT completed, all process files cleaned");
                }
            } else {
                if (Constants.DELETE_PROCESS_FILE_ON_EXIT) {
                    String processPath = getProcessFilePath();
                    if (FileUtil.exists(processPath)) {
                        FileUtil.removeFile(processPath);
                        Logger.info("Process " + pid + " terminated, file removed");
                    }
                }
            }
        } catch (Exception e) {
            Logger.warn("StateManager: termination cleanup failed for PID " + pid + ": " + e.getMessage());
        }
    }

    private void clearAllProcessFiles() {
        String realDir = PathUtil.toRealPath(Constants.SYSTEM_PROCESS_PATH);
        File dir = new File(realDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".proc"));
        if (files != null) {
            for (File f : files) {
                if (f.exists()) f.delete();
            }
        }
    }

    // ════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════

    public String getProcessFilePath() {
        return Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
    }

    private void loadLifecycle() {
        state = ProcessState.restore(processData.get("ProcessState"), processData.get("Status"));
        blockReason = parseEnum(BlockReason.class, processData.get("BlockReason"), BlockReason.NONE);
        exitReason = parseEnum(ExitReason.class, processData.get("ExitReason"), ExitReason.NONE);
        Object message = processData.get("StateMessage");
        stateMessage = message != null ? message.toString() : null;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, Object value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value.toString());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * 运行时快照 —— 不落盘的瞬态字段也包含在内，但 saveToFile 不写入它们。
     */
    public static class RuntimeSnapshot {
        public final Map<String, Object> data;
        public final List<String> codeLines;
        public final int currentLine;
        public final List<Map<String, Object>> blockStack;
        public final List<Map<String, Object>> callStackData;
        public final String pendingAssignVarName;
        public final List<String> imports;

        public RuntimeSnapshot(
                Map<String, Object> data,
                List<String> codeLines,
                int currentLine,
                List<Map<String, Object>> blockStack,
                List<Map<String, Object>> callStackData,
                String pendingAssignVarName,
                List<String> imports
        ) {
            this.data = data != null ? data : new LinkedHashMap<>();
            this.codeLines = codeLines != null ? codeLines : new ArrayList<>();
            this.currentLine = currentLine;
            this.blockStack = blockStack != null ? blockStack : new ArrayList<>();
            this.callStackData = callStackData != null ? callStackData : new ArrayList<>();
            this.pendingAssignVarName = pendingAssignVarName;
            this.imports = imports != null ? imports : new ArrayList<>();
        }
    }
}
