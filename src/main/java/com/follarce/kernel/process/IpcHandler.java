package com.follarce.kernel.process;

import com.follarce.kernel.Constants;
import com.follarce.kernel.api.function.UnknownEffectOutcomeException;
import com.follarce.kernel.log.Logger;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

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
    public record ControlTarget(int pid, String generation) {}

    private final int pid;
    private final Runnable loadFromFile;
    private final Supplier<Integer> currentLineSupplier;
    private final Supplier<Map<String, Object>> processDataSupplier;
    private final java.util.function.Consumer<BlockReason> blockProcess;
    private final CodeLoader codeLoader;
    private final StateManager stateManager;

    public IpcHandler(
            int pid,
            Runnable loadFromFile,
            Supplier<Integer> currentLineSupplier,
            Supplier<Map<String, Object>> processDataSupplier,
            java.util.function.Consumer<BlockReason> blockProcess,
            CodeLoader codeLoader,
            StateManager stateManager
    ) {
        this.pid = pid;
        this.loadFromFile = loadFromFile;
        this.currentLineSupplier = currentLineSupplier;
        this.processDataSupplier = processDataSupplier;
        this.blockProcess = blockProcess;
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
        return handleFork(null, "legacy-fork-" + UUID.randomUUID());
    }

    @SuppressWarnings("unchecked")
    public int handleFork(String childResultVariable) {
        return handleFork(childResultVariable, "legacy-fork-" + UUID.randomUUID());
    }

    @SuppressWarnings("unchecked")
    public int handleFork(String childResultVariable, String effectId) {
        try {
            Map<String, Object> processData = processDataSupplier.get();
            String parentGeneration = ProcessIdentity.generation(processData);
            ForkLedger.Entry ledger = ForkLedger.read(effectId);
            if (ledger != null && (ledger.parentPid() != pid
                    || !ledger.parentGeneration().equals(parentGeneration))) {
                throw new IllegalStateException("Fork ledger belongs to another parent");
            }
            if (ledger != null && "CREATED".equals(ledger.state())) {
                String childPath = Constants.SYSTEM_PROCESS_PATH + ledger.childPid() + ".proc";
                if (FileUtil.exists(childPath)) {
                    Map<String, Object> savedChild = JsonUtil.parseToMapStrict(FileUtil.read(childPath));
                    if (effectId.equals(savedChild.get("CreatedByEffectId"))
                            && ledger.childGeneration().equals(savedChild.get("ProcessGeneration"))) {
                        restoreParentChildRelation(processData, ledger.childPid());
                    } else {
                        throw new UnknownEffectOutcomeException("Fork result PID " + ledger.childPid()
                                + " is now owned by another process generation", null);
                    }
                } else {
                    recordMissingForkResult(processData, ledger.childPid(), ledger.childGeneration());
                }
                return ledger.childPid();
            }
            Integer recoveredPid = findChildCreatedBy(effectId);
            if (recoveredPid != null) {
                Map<String, Object> recoveredChild = JsonUtil.parseToMapStrict(
                        FileUtil.read(Constants.SYSTEM_PROCESS_PATH + recoveredPid + ".proc"));
                if (ledger == null) {
                    ForkLedger.reserve(effectId, pid, parentGeneration, recoveredPid,
                            ProcessIdentity.generation(recoveredChild));
                    ledger = ForkLedger.read(effectId);
                }
                ForkLedger.markCreated(ledger);
                restoreParentChildRelation(processData, recoveredPid);
                return recoveredPid;
            }
            int childPid;
            String childGeneration;
            if (ledger != null) {
                childPid = ledger.childPid();
                childGeneration = ledger.childGeneration();
                ensureForkReservation(ledger);
            } else {
                Integer reservedPid = findReservationCreatedBy(effectId);
                childPid = reservedPid != null ? reservedPid : allocatePid(effectId);
                Map<String, Object> reservation = JsonUtil.parseToMapStrict(
                        FileUtil.read(Constants.SYSTEM_PROCESS_PATH + childPid + ".proc"));
                childGeneration = ProcessIdentity.generation(reservation);
                ForkLedger.reserve(effectId, pid, parentGeneration, childPid, childGeneration);
                ledger = ForkLedger.read(effectId);
            }
            Map<String, Object> childData = JsonUtil.deepCopy(processData);
            childData.put("PID", childPid);
            childData.put("Name", childData.get("Name") + "-" + childPid);
            childData.put("ProcessState", ProcessState.READY.name());
            childData.put("BlockReason", null);
            childData.put("ExitReason", null);
            childData.put("StateMessage", null);
            childData.put("RunningTime", 0);
            childData.put("ProcessGeneration", childGeneration);
            childData.put("CreatedByEffectId", effectId);
            Map<String, Object> childExecution = new LinkedHashMap<>();
            childExecution.put("NextAttemptOrdinal", 0L);
            childData.put("Execution", childExecution);
            childData.remove("Reservation");
            childData.remove("ReservedByPid");
            childData.remove("ReservedByGeneration");
            childData.put("ExitedChildren", new LinkedHashMap<>());
            childData.put("ReapedChildren", new LinkedHashMap<>());
            childData.remove("InboxState");
            childData.remove("LifecycleCleanup");
            childData.remove("TerminationCleanup");

            Map<String, Object> parentInfo = new LinkedHashMap<>();
            parentInfo.put("PID", pid);
            parentInfo.put("Name", processData.get("Name"));
            parentInfo.put("Generation", ProcessIdentity.generation(processData));
            childData.put("Parent", parentInfo);

            childData.remove("Child");
            childData.put("Child", new LinkedHashMap<>());

            // 复制 Program 数据，剔除注释后写入（确保 .proc 文件干净）
            Map<String, Object> childProgram = (Map<String, Object>) childData.get("Program");
            if (childProgram != null) {
                Object childVariables = childProgram.get("Data");
                if (childResultVariable != null && childVariables instanceof Map) {
                    ((Map<String, Object>) childVariables).put(childResultVariable, 0);
                }
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

            // 写入子进程文件（PID 已在 allocatePid 中原子创建）
            String childFileName = childPid + ".proc";
            String childJson = JsonUtil.toMetaJson(childData);
            JsonUtil.writeFile(Constants.SYSTEM_PROCESS_PATH + childFileName, childJson);
            ForkLedger.markCreated(ledger);

            // 更新父进程 Child 列表
            Map<String, Object> childInfo = new LinkedHashMap<>();
            childInfo.put("Name", childData.get("Name"));
            childInfo.put("PID", childPid);
            childInfo.put("Path", childData.get("Path"));
            childInfo.put("Generation", ProcessIdentity.generation(childData));

            // 同步更新内存中的 processData，供后续 commitAndPersist 落盘
            @SuppressWarnings("unchecked")
            Map<String, Object> children = (Map<String, Object>) processData.get("Child");
            if (children == null) {
                children = new LinkedHashMap<>();
                processData.put("Child", children);
            }
            children.put(String.valueOf(childPid), childInfo);
            Object reapedObject = processData.get("ReapedChildren");
            if (reapedObject instanceof Map) {
                String prefix = childPid + "@";
                ((Map<String, Object>) reapedObject).keySet().removeIf(
                        key -> key.equals(String.valueOf(childPid)) || key.startsWith(prefix));
            }

            Logger.info("Fork: PID " + pid + " created child PID " + childPid);
            return childPid;
        } catch (Exception e) {
            Logger.error("Fork failed for PID " + pid + ": " + e.getMessage());
            throw e instanceof RuntimeException ? (RuntimeException) e
                    : new RuntimeException("Fork failed", e);
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
        Map<String, Object> processData = processDataSupplier.get();
        String currentUser = String.valueOf(processData.getOrDefault("EffectiveUser",
                processData.getOrDefault("Owner", Constants.DEFAULT_USER_LOCAL)));
        String scriptPath = PathUtil.resolvePath(path, currentUser, extractAliases(processData));
        if (!FileUtil.exists(scriptPath)) {
            Logger.error("Exec: script not found: " + scriptPath);
            return;
        }

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
            boolean canAssume = Constants.DEFAULT_USER_LOCAL.equals(currentUser)
                    || detectedUser.equals(currentUser);
            if (!canAssume || !UserUtil.getListOfUsers().containsKey(detectedUser)) {
                Logger.warn("Exec denied: " + currentUser + " cannot assume " + detectedUser);
                return;
            }
            execUser = detectedUser;
        } else {
            execUser = currentUser;
        }
        processData.put("EffectiveUser", execUser);
        processData.put("ProcessState", ProcessState.READY.name());
        processData.put("BlockReason", null);
        processData.put("StateMessage", null);
        Object execution = processData.get("Execution");
        if (execution instanceof Map) ((Map<String, Object>) execution).remove("ActiveAttempt");

        String expectedGeneration = ProcessIdentity.generation(processData);
        JsonUtil.updateFile(stateManager.getProcessFilePath(), current -> {
            if (!expectedGeneration.equals(current.get("ProcessGeneration"))) {
                throw new IllegalStateException("Exec target generation changed for PID " + pid);
            }
            current.clear();
            current.putAll(JsonUtil.deepCopy(processData));
        });
        loadFromFile.run();
        Logger.info("Exec: PID " + pid + " replaced with " + scriptPath);
    }

    public void handleExecMarker(String marker) {
        String[] fields = marker.substring("EXEC:".length()).split(":", -1);
        if (fields.length == 0) return;
        StringBuilder line = new StringBuilder("exec(\"").append(fields[0]).append("\"");
        for (int i = 1; i < fields.length; i++) line.append(" ").append(fields[i]);
        line.append(")");
        handleExec(line.toString());
    }

    // ════════════════════════════════════════════
    // kill
    // ════════════════════════════════════════════

    /**
     * 处理 kill(pid) —— 终止指定进程。
     * <p>
     * 子进程迁移到 INIT、从父进程移除引用通过 postMessage 发送，
     * 目标进程本身直接删除 .proc 文件并停止 ProcessRunner。
     * 与 pause（转入 PAUSED 并保留快照）不同，kill 是彻底终止。
     */
    @SuppressWarnings("unchecked")
    public boolean handleKill(String pidStr) {
        return handleKill(pidStr, UUID.randomUUID().toString());
    }

    public boolean handleKill(String pidStr, String messageId) {
        ControlTarget target = resolveControlTarget(pidStr);
        return target != null && handleKill(target, messageId);
    }

    public boolean handleKill(ControlTarget target, String messageId) {
        boolean published = ProcessRunner.requestTermination(target.pid(), target.generation(),
                messageId, pid, ProcessIdentity.generation(processDataSupplier.get()));
        if (published) Logger.info("Kill: PID " + target.pid() + " killed by PID " + pid);
        return published;
    }

    // ════════════════════════════════════════════
    // wait / waitPid
    // ════════════════════════════════════════════

    /**
     * 处理 wait() —— 等待任意子进程。
     * 设置阻塞状态，返回 true。
     */
    public void handleWait() {
        Map<String, Object> processData = processDataSupplier.get();
        processData.remove("BlockTargetPid");
        if (consumeExitedChild(processData, null)) return;
        Logger.info("Process " + pid + " blocked on wait()");
        blockProcess.accept(BlockReason.WAIT_ANY);
    }

    /**
     * 处理 waitPid(pid) —— 等待指定子进程。
     */
    @SuppressWarnings("unchecked")
    public void handleWaitPid(String pidStr) {
        try {
            int targetPid = Integer.parseInt(pidStr.trim());
            Map<String, Object> processData = processDataSupplier.get();
            if (consumeExitedChild(processData, targetPid)) return;
            Map<String, Object> children = (Map<String, Object>) processData.get("Child");
            if (children != null && children.containsKey(String.valueOf(targetPid))) {
                Map<String, Object> blockingInfo = new LinkedHashMap<>();
                blockingInfo.put("type", "WAITPID");
                blockingInfo.put("targetPid", targetPid);
                processData.put("_blockingInfo", blockingInfo);
                processData.put("BlockTargetPid", targetPid);
                blockProcess.accept(BlockReason.WAIT_PID);
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
    public boolean handlePause(String pidStr) {
        return handlePause(pidStr, UUID.randomUUID().toString());
    }

    public boolean handlePause(String pidStr, String messageId) {
        ControlTarget target = resolveControlTarget(pidStr);
        return target != null && handlePause(target, messageId);
    }

    public boolean handlePause(ControlTarget target, String messageId) {
        return ProcessRunner.postMessageToGeneration(target.pid(), target.generation(),
                "ProcessState", ProcessState.PAUSED.name(), messageId, pid,
                ProcessIdentity.generation(processDataSupplier.get()));
    }

    @SuppressWarnings("unchecked")
    public boolean handleContinue(String pidStr) {
        return handleContinue(pidStr, UUID.randomUUID().toString());
    }

    public boolean handleContinue(String pidStr, String messageId) {
        ControlTarget target = resolveControlTarget(pidStr);
        return target != null && handleContinue(target, messageId);
    }

    public boolean handleContinue(ControlTarget target, String messageId) {
        return ProcessRunner.postMessageToGeneration(target.pid(), target.generation(),
                "ProcessState", ProcessState.READY.name(), messageId, pid,
                ProcessIdentity.generation(processDataSupplier.get()));
    }

    // ════════════════════════════════════════════
    // 权限检查
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    public boolean checkProcessOwner(int targetPid) {
        return resolveControlTarget(Integer.toString(targetPid)) != null;
    }

    public ControlTarget resolveControlTarget(String pidText) {
        final int targetPid;
        try {
            targetPid = Integer.parseInt(pidText.trim());
        } catch (RuntimeException e) {
            Logger.warn("Invalid process target: " + pidText);
            return null;
        }
        String currentUser = String.valueOf(processDataSupplier.get().getOrDefault("EffectiveUser",
                processDataSupplier.get().getOrDefault("Owner", Constants.DEFAULT_USER_LOCAL)));
        String targetPath = PathUtil.findProcessFilePathByPid(targetPid);
        if (targetPath == null || !FileUtil.exists(targetPath)) return null;
        java.util.concurrent.locks.ReentrantLock lock = JsonUtil.lockFile(targetPath);
        try {
            if (!FileUtil.exists(targetPath)) return null;
            Map<String, Object> targetData = JsonUtil.parseToMapStrict(FileUtil.read(targetPath));
            ProcessIdentity.ensureDefaults(targetData);
            Object owner = targetData.get("Owner");
            if (!Constants.DEFAULT_USER_LOCAL.equals(currentUser)
                    && (owner == null || !owner.toString().equals(currentUser))) {
                Logger.warn("Control denied: PID " + pid + " cannot control PID " + targetPid);
                return null;
            }
            return new ControlTarget(targetPid, ProcessIdentity.generation(targetData));
        } finally {
            lock.unlock();
        }
    }

    // ════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════

    private static final java.util.concurrent.locks.ReentrantLock PID_ALLOC_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    private int allocatePid(String effectId) {
        PID_ALLOC_LOCK.lock();
        try {
            String processDir = com.follarce.kernel.vfs.PathUtil.toRealPath(
                    com.follarce.kernel.Constants.SYSTEM_PROCESS_PATH);
            Set<Integer> unavailable = unavailablePids(processDir);
            int base = 2;
            while (true) {
                if (unavailable.contains(base)) {
                    base++;
                    continue;
                }
                java.io.File procFile = new java.io.File(processDir, base + ".proc");
                try {
                    Map<String, Object> reservation = new LinkedHashMap<>();
                    reservation.put("Name", "RESERVED-" + base);
                    reservation.put("Owner", Constants.DEFAULT_USER_LOCAL);
                    reservation.put("PID", base);
                    reservation.put("ProcessState", ProcessState.PAUSED.name());
                    reservation.put("ProcessGeneration", ProcessIdentity.newGeneration());
                    reservation.put("CreatedByEffectId", effectId);
                    reservation.put("Reservation", true);
                    reservation.put("ReservedByPid", pid);
                    reservation.put("ReservedByGeneration",
                            ProcessIdentity.generation(processDataSupplier.get()));
                    Map<String, Object> reservationCode = new LinkedHashMap<>();
                    reservationCode.put("Code", new ArrayList<String>());
                    reservationCode.put("runningCodeLine", 0);
                    reservationCode.put("BlockStack", new ArrayList<>());
                    reservation.put("Program", new LinkedHashMap<>(Map.of(
                            "Data", new LinkedHashMap<String, Object>(), "Code", reservationCode)));
                    writeNewProcessReservation(procFile.toPath(), reservation);
                    if (procFile.exists()) {
                        return base;
                    }
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    // Another allocator claimed this PID.
                } catch (java.io.IOException e) {
                    throw new RuntimeException("Failed to reserve PID " + base, e);
                }
                base++;
            }
        } finally {
            PID_ALLOC_LOCK.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private Set<Integer> unavailablePids(String processDir) {
        Set<Integer> result = new HashSet<>();
        java.io.File dir = new java.io.File(processDir);
        java.io.File[] files = dir.listFiles((d, name) -> name.matches("\\d+\\.proc(?:\\.tmp)?"));
        if (files == null) return result;
        for (java.io.File file : files) {
            String name = file.getName();
            try {
                int filePid = Integer.parseInt(name.substring(0, name.indexOf('.')));
                result.add(filePid);
                String canonicalName = filePid + ".proc";
                if (!FileUtil.exists(Constants.SYSTEM_PROCESS_PATH + canonicalName)) continue;
                Map<String, Object> data = JsonUtil.parseToMapStrict(
                        FileUtil.read(Constants.SYSTEM_PROCESS_PATH + canonicalName));
                Object exitedObj = data.get("ExitedChildren");
                if (exitedObj instanceof Map) {
                    for (String exitedPid : ((Map<String, Object>) exitedObj).keySet()) {
                        result.add(Integer.parseInt(exitedPid));
                    }
                }
            } catch (Exception ignored) {
                // A malformed or temporary snapshot still reserves its filename PID.
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean consumeExitedChild(Map<String, Object> processData, Integer targetPid) {
        Object exitedObj = processData.get("ExitedChildren");
        if (!(exitedObj instanceof Map)) return false;
        Map<String, Object> exited = (Map<String, Object>) exitedObj;
        String key = targetPid != null ? String.valueOf(targetPid)
                : exited.keySet().stream().findFirst().orElse(null);
        if (key == null || !exited.containsKey(key)) return false;
        Object event = exited.remove(key);
        String generation = event instanceof Map && ((Map<?, ?>) event).get("Generation") instanceof String
                ? ((Map<?, ?>) event).get("Generation").toString() : null;
        Map<String, Object> reaped = (Map<String, Object>) processData.computeIfAbsent(
                "ReapedChildren", ignored -> new LinkedHashMap<String, Object>());
        reaped.put(generation == null ? key : key + "@" + generation, true);
        return true;
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

    @SuppressWarnings("unchecked")
    private Map<String, String> extractAliases(Map<String, Object> processData) {
        Map<String, String> result = new LinkedHashMap<>();
        Object aliases = processData.get("PathAliases");
        if (aliases instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) aliases).entrySet()) {
                if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                    result.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
        }
        return result;
    }

    private Integer findChildCreatedBy(String effectId) {
        if (effectId == null) return null;
        for (Map.Entry<Integer, String> entry : PathUtil.scanProcessFileNames().entrySet()) {
            if (entry.getKey() == pid) continue;
            try {
                Map<String, Object> candidate = JsonUtil.parseToMapStrict(
                        FileUtil.read(Constants.SYSTEM_PROCESS_PATH + entry.getValue()));
                if (effectId.equals(candidate.get("CreatedByEffectId"))
                        && !Boolean.TRUE.equals(candidate.get("Reservation"))) return entry.getKey();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Integer findReservationCreatedBy(String effectId) {
        if (effectId == null) return null;
        for (Map.Entry<Integer, String> entry : PathUtil.scanProcessFileNames().entrySet()) {
            try {
                Map<String, Object> candidate = JsonUtil.parseToMapStrict(
                        FileUtil.read(Constants.SYSTEM_PROCESS_PATH + entry.getValue()));
                if (Boolean.TRUE.equals(candidate.get("Reservation"))
                        && effectId.equals(candidate.get("CreatedByEffectId"))
                        && ((Number) candidate.getOrDefault("ReservedByPid", -1)).intValue() == pid
                        && Objects.equals(candidate.get("ReservedByGeneration"),
                        ProcessIdentity.generation(processDataSupplier.get()))) {
                    return entry.getKey();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void ensureForkReservation(ForkLedger.Entry entry) {
        String vfsPath = Constants.SYSTEM_PROCESS_PATH + entry.childPid() + ".proc";
        if (FileUtil.exists(vfsPath)) {
            Map<String, Object> reservation = JsonUtil.parseToMapStrict(FileUtil.read(vfsPath));
            if (Boolean.TRUE.equals(reservation.get("Reservation"))
                    && entry.effectId().equals(reservation.get("CreatedByEffectId"))
                    && entry.childGeneration().equals(reservation.get("ProcessGeneration"))) return;
            throw new UnknownEffectOutcomeException(
                    "Reserved fork PID was reused: " + entry.childPid(), null);
        }
        Map<String, Object> reservation = new LinkedHashMap<>();
        reservation.put("Name", "RESERVED-" + entry.childPid());
        reservation.put("Owner", Constants.DEFAULT_USER_LOCAL);
        reservation.put("PID", entry.childPid());
        reservation.put("ProcessState", ProcessState.PAUSED.name());
        reservation.put("ProcessGeneration", entry.childGeneration());
        reservation.put("CreatedByEffectId", entry.effectId());
        reservation.put("Reservation", true);
        reservation.put("ReservedByPid", entry.parentPid());
        reservation.put("ReservedByGeneration", entry.parentGeneration());
        Map<String, Object> code = new LinkedHashMap<>();
        code.put("Code", new ArrayList<String>());
        code.put("runningCodeLine", 0);
        code.put("BlockStack", new ArrayList<>());
        reservation.put("Program", new LinkedHashMap<>(Map.of(
                "Data", new LinkedHashMap<String, Object>(), "Code", code)));
        try {
            writeNewProcessReservation(java.nio.file.Path.of(PathUtil.toRealPath(vfsPath)), reservation);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            ensureForkReservation(entry);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to restore fork reservation", e);
        }
    }

    private static void writeNewProcessReservation(java.nio.file.Path target,
                                                   Map<String, Object> reservation)
            throws java.io.IOException {
        java.nio.file.Path temp = target.resolveSibling(
                target.getFileName() + ".reservation-" + UUID.randomUUID() + ".tmp");
        try {
            java.nio.file.Files.writeString(temp, JsonUtil.toJson(reservation),
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    temp, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            try (java.nio.channels.FileChannel directory = java.nio.channels.FileChannel.open(
                    target.getParent(), java.nio.file.StandardOpenOption.READ)) {
                directory.force(true);
            } catch (Exception ignored) {
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    private void restoreParentChildRelation(Map<String, Object> parent, int childPid) {
        String path = Constants.SYSTEM_PROCESS_PATH + childPid + ".proc";
        if (!FileUtil.exists(path)) return;
        Map<String, Object> child = JsonUtil.parseToMapStrict(FileUtil.read(path));
        @SuppressWarnings("unchecked")
        Map<String, Object> children = (Map<String, Object>) parent.computeIfAbsent(
                "Child", ignored -> new LinkedHashMap<String, Object>());
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("Name", child.get("Name"));
        info.put("PID", childPid);
        info.put("Path", child.get("Path"));
        info.put("Generation", child.get("ProcessGeneration"));
        children.put(String.valueOf(childPid), info);
    }

    @SuppressWarnings("unchecked")
    private void recordMissingForkResult(Map<String, Object> parent, int childPid,
                                         String childGeneration) {
        Object children = parent.get("Child");
        if (children instanceof Map) ((Map<String, Object>) children).remove(String.valueOf(childPid));
        Map<String, Object> exited = (Map<String, Object>) parent.computeIfAbsent(
                "ExitedChildren", ignored -> new LinkedHashMap<String, Object>());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("PID", childPid);
        event.put("Generation", childGeneration);
        event.put("ExitReason", ExitReason.NONE.name());
        exited.putIfAbsent(String.valueOf(childPid), event);
    }
}
