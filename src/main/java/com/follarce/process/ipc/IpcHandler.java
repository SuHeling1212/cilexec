package com.follarce.process.ipc;

import com.follarce.Constants;
import com.follarce.log.Logger;
import com.follarce.process.ProcessRunner;
import com.follarce.process.code.CodeLoader;
import com.follarce.process.state.ProcessFileLock;
import com.follarce.process.state.StateManager;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;
import com.follarce.util.UserUtil;

import static com.follarce.Constants.USE_VIRTUAL_THREADS;

import java.util.*;
import java.util.function.Supplier;

/**
 * 进程间操作处理器 —— fork/exec/kill/wait/waitPid/pause/continue。
 * <p>
 * 职责：
 * <ul>
 *   <li>fork() — 创建子进程文件</li>
 *   <li>exec(path) — 替换当前进程代码</li>
 *   <li>kill(pid) — 终止指定进程</li>
 *   <li>wait() / waitPid(pid) — 阻塞等待子进程</li>
 *   <li>pause(pid) / continue(pid) — 暂停/恢复进程</li>
 * </ul>
 */
public class IpcHandler {

    private final int pid;
    private final Runnable saveToFile;
    private final Runnable loadFromFile;
    private final Supplier<Integer> currentLineSupplier;
    private final Supplier<Map<String, Object>> processDataSupplier;
    private final Supplier<Map<String, Object>> dataSupplier;
    private final Supplier<Integer> stateSupplier;
    private final java.util.function.Consumer<Integer> stateSetter;
    private final CodeLoader codeLoader;
    private final StateManager stateManager;

    public IpcHandler(
            int pid,
            Runnable saveToFile,
            Runnable loadFromFile,
            Supplier<Integer> currentLineSupplier,
            Supplier<Map<String, Object>> processDataSupplier,
            Supplier<Map<String, Object>> dataSupplier,
            Supplier<Integer> stateSupplier,
            java.util.function.Consumer<Integer> stateSetter,
            CodeLoader codeLoader,
            StateManager stateManager
    ) {
        this.pid = pid;
        this.saveToFile = saveToFile;
        this.loadFromFile = loadFromFile;
        this.currentLineSupplier = currentLineSupplier;
        this.processDataSupplier = processDataSupplier;
        this.dataSupplier = dataSupplier;
        this.stateSupplier = stateSupplier;
        this.stateSetter = stateSetter;
        this.codeLoader = codeLoader;
        this.stateManager = stateManager;
    }

    // ════════════════════════════════════════════
    // fork
    // ════════════════════════════════════════════

    /**
     * 处理 fork() —— 创建子进程文件。
     *
     * @return 子进程 PID（-1 表示失败）
     */
    @SuppressWarnings("unchecked")
    public int handleFork() {
        try {
            Map<String, Object> processData = processDataSupplier.get();
            int childPid = allocatePid();
            Map<String, Object> childData = new LinkedHashMap<>(processData);
            childData.put("PID", childPid);
            childData.put("Name", childData.get("Name") + "-" + childPid);
            childData.put("Status", true);
            childData.put("RunningTime", 0);

            Map<String, Object> parentInfo = new LinkedHashMap<>();
            parentInfo.put("PID", pid);
            parentInfo.put("Name", processData.get("Name"));
            childData.put("Parent", parentInfo);

            childData.remove("Child");
            childData.put("Child", new LinkedHashMap<>());

            // 复制 Program 数据，剔除注释后写入（确保 .proc 文件干净）
            Map<String, Object> childProgram = (Map<String, Object>) childData.get("Program");
            if (childProgram != null) {
                Map<String, Object> code = (Map<String, Object>) childProgram.get("Code");
                if (code != null) {
                    Object codeLinesObj = code.get("Code");
                    if (codeLinesObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> rawLines = (List<String>) codeLinesObj;
                        code.put("Code", CodeLoader.stripComments(rawLines));
                    }
                    // 子进程复制父进程的程序计数器，然后各自 +1（父进程在调用方 +1）
                    int parentLine = currentLineSupplier.get();
                    code.put("runningCodeLine", parentLine + 1);
                    code.put("BlockStack", new ArrayList<>());
                }
                childProgram.remove("pendingAssignVarName");
                childProgram.remove("imports");
            }

            // 写入子进程文件
            String childFileName = childPid + ".proc";
            String childJson = JsonUtil.toMetaJson(childData);
            FileUtil.createFile(Constants.SYSTEM_PROCESS_PATH, childFileName);
            FileUtil.write(Constants.SYSTEM_PROCESS_PATH + childFileName, childJson);

            // 更新父进程 Child 列表
            Map<String, Object> children = (Map<String, Object>) processData.get("Child");
            if (children == null) {
                children = new LinkedHashMap<>();
                processData.put("Child", children);
            }
            Map<String, Object> childInfo = new LinkedHashMap<>();
            childInfo.put("Name", childData.get("Name"));
            childInfo.put("PID", childPid);
            childInfo.put("Path", childData.get("Path"));
            children.put(String.valueOf(childPid), childInfo);
            saveToFile.run();

            Logger.info("Fork: PID " + pid + " created child PID " + childPid);
            return childPid;
        } catch (Exception e) {
            Logger.error("Fork failed for PID " + pid + ": " + e.getMessage());
            return -1;
        }
    }

    /**
     * 处理 exec(path) —— 用新脚本替换当前进程代码。
     */
    @SuppressWarnings("unchecked")
    public void handleExec(String line) {
        String inner = line.substring(line.indexOf('(') + 1, line.lastIndexOf(')')).trim();
        String[] parts = parseExecArgs(inner);
        if (parts.length == 0) return;

        String path = parts[0];
        String scriptPath = PathUtil.resolvePath(path);
        if (!FileUtil.exists(scriptPath)) {
            Logger.error("Exec: script not found: " + scriptPath);
            return;
        }

        String currentUser = UserUtil.getCurrentUser();
        if (!FileUtil.checkFilePermission(scriptPath, Constants.PERM_READ, currentUser)) {
            Logger.warn("Exec denied: " + currentUser + " cannot read " + scriptPath);
            return;
        }

        String scriptContent = FileUtil.read(scriptPath);
        if (scriptContent == null || scriptContent.trim().isEmpty()) {
            Logger.error("Exec: empty script: " + scriptPath);
            return;
        }

        // 替换进程数据
        Map<String, Object> processData = processDataSupplier.get();
        processData.put("Path", scriptPath);
        processData.put("startTime", FileUtil.getCurrentTimeArray());

        // 用新代码替换
        Map<String, Object> program = (Map<String, Object>) processData.get("Program");
        if (program == null) {
            program = new LinkedHashMap<>();
            processData.put("Program", program);
        }
        program.remove("Data");
        program.remove("pendingAssignVarName");
        program.remove("imports");

        List<String> newCodeLines = new ArrayList<>();
        for (String l : scriptContent.split("\n")) {
            newCodeLines.add(l);
        }
        codeLoader.load(newCodeLines);

        Map<String, Object> code = new LinkedHashMap<>();
        code.put("Code", new ArrayList<>(codeLoader.getCodeLines()));
        code.put("runningCodeLine", 0);
        code.put("BlockStack", new ArrayList<>());
        program.put("Code", code);

        // 设置 exec 参数
        String detectedUser = detectExecUser(parts);
        String execUser = "";
        if (detectedUser != null) {
            execUser = detectedUser;
            UserUtil.setCurrentUser(detectedUser);
        } else {
            execUser = UserUtil.getCurrentUser();
        }
        processData.put("Owner", execUser);

        saveToFile.run();
        loadFromFile.run();
        Logger.info("Exec: PID " + pid + " replaced with " + scriptPath);
    }

    // ════════════════════════════════════════════
    // kill
    // ════════════════════════════════════════════

    /**
     * 处理 kill(pid) —— 终止指定进程。
     */
    @SuppressWarnings("unchecked")
    public void handleKill(String pidStr) {
        try {
            int targetPid = Integer.parseInt(pidStr.trim());
            if (!checkProcessOwner(targetPid)) {
                Logger.warn("Kill denied: PID " + pid + " cannot kill PID " + targetPid);
                return;
            }
            String processPath = PathUtil.findProcessFilePathByPid(targetPid);
            if (processPath == null || !FileUtil.exists(processPath)) return;

            // 从目标进程文件获取父 PID（用于后续解锁顺序）
            String content = FileUtil.read(processPath);
            Map<String, Object> targetData = JsonUtil.parseToMap(content);
            int parentPid = -1;
            Map<String, Object> parent = (Map<String, Object>) targetData.get("Parent");
            if (parent != null && parent.get("PID") != null) {
                parentPid = ((Number) parent.get("PID")).intValue();
            }

            // 虚拟线程模式：锁定受影响的所有 PID
            if (USE_VIRTUAL_THREADS) {
                if (parentPid > 0) {
                    ProcessFileLock.lockTwo(parentPid, targetPid);
                } else {
                    ProcessFileLock.lock(targetPid);
                }
            }

            try {
                // 子进程迁移到 INIT
                Map<String, Object> children = (Map<String, Object>) targetData.get("Child");
                if (children != null && !children.isEmpty()) {
                    String initPath = PathUtil.getProcessFilePath(Constants.PID_INIT);
                    String initContent = FileUtil.read(initPath);
                    Map<String, Object> initData = JsonUtil.parseToMap(initContent);
                    Map<String, Object> initChildren = (Map<String, Object>) initData.get("Child");
                    if (initChildren == null) {
                        initChildren = new LinkedHashMap<>();
                        initData.put("Child", initChildren);
                    }
                    initChildren.putAll(children);
                    FileUtil.write(initPath, JsonUtil.toMetaJson(initData));
                }

                // 从父进程 Child 列表移除
                if (parentPid > 0) {
                    String parentPath = PathUtil.findProcessFilePathByPid(parentPid);
                    if (parentPath != null && FileUtil.exists(parentPath)) {
                        String parentContent = FileUtil.read(parentPath);
                        Map<String, Object> parentData = JsonUtil.parseToMap(parentContent);
                        Map<String, Object> parentChildren = (Map<String, Object>) parentData.get("Child");
                        if (parentChildren != null) {
                            parentChildren.remove(String.valueOf(targetPid));
                            parentData.put("Child", parentChildren);
                            FileUtil.write(parentPath, JsonUtil.toMetaJson(parentData));
                        }
                    }
                }

                // 标记为已终止
                targetData.put("Status", false);
                targetData.put("_killed", true);
                FileUtil.write(processPath, JsonUtil.toMetaJson(targetData));
                Logger.info("Kill: PID " + targetPid + " killed by PID " + pid);
            } finally {
                if (USE_VIRTUAL_THREADS) {
                    if (parentPid > 0) {
                        ProcessFileLock.unlockTwo(parentPid, targetPid);
                    } else {
                        ProcessFileLock.unlock(targetPid);
                    }
                }
            }

            // 虚拟线程模式：唤醒目标进程和父进程的虚拟线程
            if (USE_VIRTUAL_THREADS) {
                ProcessRunner.unparkProcess(targetPid);
                if (parentPid > 0) {
                    ProcessRunner.unparkProcess(parentPid);
                }
            }
        } catch (Exception e) {
            Logger.error("Kill failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════
    // wait / waitPid
    // ════════════════════════════════════════════

    /**
     * 处理 wait() —— 等待任意子进程。
     * 设置阻塞状态，返回 true。
     */
    public void handleWait() {
        Logger.info("Process " + pid + " blocked on wait()");
        stateSetter.accept(2); // BLOCKED
    }

    /**
     * 处理 waitPid(pid) —— 等待指定子进程。
     */
    @SuppressWarnings("unchecked")
    public void handleWaitPid(String pidStr) {
        try {
            int targetPid = Integer.parseInt(pidStr.trim());
            Map<String, Object> processData = processDataSupplier.get();
            Map<String, Object> children = (Map<String, Object>) processData.get("Child");
            if (children != null && children.containsKey(String.valueOf(targetPid))) {
                Map<String, Object> blockingInfo = new LinkedHashMap<>();
                blockingInfo.put("type", "WAITPID");
                blockingInfo.put("targetPid", targetPid);
                processData.put("_blockingInfo", blockingInfo);
                stateSetter.accept(2); // BLOCKED
                Logger.info("Process " + pid + " blocked on waitPID(" + targetPid + ")");
            }
        } catch (NumberFormatException e) {
            Logger.warn("Invalid waitPid argument: " + pidStr);
        }
    }

    // ════════════════════════════════════════════
    // pause / continue
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    public void handlePause(String pidStr) {
        try {
            int targetPid = Integer.parseInt(pidStr.trim());
            if (!checkProcessOwner(targetPid)) {
                Logger.warn("Pause denied: PID " + pid + " cannot pause PID " + targetPid);
                return;
            }
            String targetPath = PathUtil.findProcessFilePathByPid(targetPid);
            if (targetPath != null && FileUtil.exists(targetPath)) {
                String content = FileUtil.read(targetPath);
                Map<String, Object> targetData = JsonUtil.parseToMap(content);
                targetData.put("Status", false);
                FileUtil.write(targetPath, JsonUtil.toMetaJson(targetData));
            }
        } catch (Exception e) {
            Logger.warn("Pause failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void handleContinue(String pidStr) {
        try {
            int targetPid = Integer.parseInt(pidStr.trim());
            if (!checkProcessOwner(targetPid)) {
                Logger.warn("Continue denied: PID " + pid + " cannot continue PID " + targetPid);
                return;
            }
            String targetPath = PathUtil.findProcessFilePathByPid(targetPid);
            if (targetPath != null && FileUtil.exists(targetPath)) {
                String content = FileUtil.read(targetPath);
                Map<String, Object> targetData = JsonUtil.parseToMap(content);
                targetData.put("Status", true);
                FileUtil.write(targetPath, JsonUtil.toMetaJson(targetData));
            }
        } catch (Exception e) {
            Logger.warn("Continue failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════
    // 权限检查
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    public boolean checkProcessOwner(int targetPid) {
        if (UserUtil.isLocal()) return true;
        String currentUser = UserUtil.getCurrentUser();
        String targetPath = PathUtil.findProcessFilePathByPid(targetPid);
        if (targetPath == null || !FileUtil.exists(targetPath)) return false;
        String content = FileUtil.read(targetPath);
        Map<String, Object> targetData = JsonUtil.parseToMap(content);
        Object owner = targetData.get("Owner");
        return owner != null && owner.toString().equals(currentUser);
    }

    // ════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════

    private int allocatePid() {
        // 从 2 开始分配（PID 1 固定为 INIT）
        int base = 2;
        Map<Integer, Map<String, Object>> existing = stateManager != null
                ? scanProcessFiles()
                : new LinkedHashMap<>();
        while (existing.containsKey(base)) {
            base++;
        }
        return base;
    }

    private Map<Integer, Map<String, Object>> scanProcessFiles() {
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        String processDir = PathUtil.toRealPath(Constants.SYSTEM_PROCESS_PATH);
        java.io.File dir = new java.io.File(processDir);
        if (!dir.exists() || !dir.isDirectory()) return result;
        java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".proc"));
        if (files == null) return result;
        for (java.io.File file : files) {
            try {
                String content = FileUtil.read(Constants.SYSTEM_PROCESS_PATH + file.getName());
                if (content == null || content.trim().isEmpty()) continue;
                Map<String, Object> data = JsonUtil.parseToMap(content);
                Object pidObj = data.get("PID");
                if (!(pidObj instanceof Number)) continue;
                int existingPid = ((Number) pidObj).intValue();
                if (existingPid > 0) {
                    result.put(existingPid, data);
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    public static String[] parseExecArgs(String inner) {
        List<String> result = new ArrayList<>();
        inner = inner.trim();
        if (inner.startsWith("\"")) {
            int endQuote = inner.indexOf('"', 1);
            if (endQuote > 0) {
                result.add(inner.substring(1, endQuote));
                String rest = inner.substring(endQuote + 1).trim();
                if (!rest.isEmpty()) {
                    for (String p : rest.split("\\s+")) {
                        p = p.trim();
                        if (!p.isEmpty()) result.add(p);
                    }
                }
            }
        } else {
            for (String p : inner.split("\\s+")) {
                p = p.trim();
                if (!p.isEmpty()) result.add(p);
            }
        }
        return result.toArray(new String[0]);
    }

    private static String detectExecUser(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            if ("-user".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }
}
