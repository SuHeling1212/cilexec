package com.follarce.process;

import com.follarce.Constants;
import com.follarce.exception.ExceptionContext;
import com.follarce.exception.ProcessException;
import com.follarce.exception.RecoverableException;
import com.follarce.exception.UnrecoverableException;
import com.follarce.function.FunctionRegistry;
import com.follarce.function.EffectPolicy;
import com.follarce.function.FunctionContext;
import com.follarce.log.Logger;
import com.follarce.script.FunctionDef;
import com.follarce.util.UserUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;



/**
 * 进程执行引擎 —— 由调度器驱动，每次 step() 执行一行 FCL 代码。
 * <p>
 * 状态机：NEW → READY → RUNNING → READY/BLOCKED/PAUSED/TERMINATED/FAILED
 * <p>
 * <strong>重构说明：</strong>本类已拆分为多个专注组件，此处仅作为协调器：
 * <ul>
 *   <li>{@link StateManager} — 进程文件 I/O 与持久化</li>
 *   <li>{@link CodeLoader} — 代码加载与预扫描生成边界表</li>
 *   <li>{@link ExpressionEvaluator} — 表达式求值</li>
 *   <li>{@link ControlFlow} — 基于边界表 + BlockStack 的控制流</li>
 *   <li>{@link IpcHandler} — fork/exec/kill/wait 等进程间操作</li>
 *   <li>{@link FunctionManager} — 用户函数定义与调用栈</li>
 *   <li>{@link ImportManager} — import/include 处理</li>
 * </ul>
 */
public class ProcessRunner {

    // ════════════════════════════════════════════
    // 公开枚举（供 Scheduler 使用）
    // ════════════════════════════════════════════

    public enum StepResult {
        COMPLETED, BLOCKED, TERMINATED
    }

    // ════════════════════════════════════════════
    // 全局注册表 + 消息队列
    // ════════════════════════════════════════════

    /** PID → ProcessRunner 映射，供 postMessage 查找目标进程 */
    private static final ConcurrentHashMap<Integer, ProcessRunner> RUNNERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<ProcessRunner> CURRENT_RUNNER = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, Object> LIFECYCLE_CLEANUP_GATES = new ConcurrentHashMap<>();

    // ════════════════════════════════════════════
    // 组件
    // ════════════════════════════════════════════

    private final StateManager stateManager;
    private final CodeLoader codeLoader;
    private final ExpressionEvaluator expressionEvaluator;
    private final ControlFlow controlFlow;
    private final IpcHandler ipcHandler;
    private final FunctionManager functionManager;
    private final ImportManager importManager;
    private final StatementAttemptManager attemptManager;

    // ════════════════════════════════════════════
    // ProcessRunner 级别状态
    // ════════════════════════════════════════════

    private volatile boolean running = true;
    private volatile boolean killed;
    private volatile boolean lifecycleCleaned;
    private final Object persistenceLock = new Object();
    private final int pid;
    private volatile ProcessState state = ProcessState.NEW;
    private int priority = Constants.DEFAULT_PRIORITY;
    private volatile BlockReason blockReason = BlockReason.NONE;
    private ExitReason exitReason = ExitReason.NONE;
    private String stateMessage;
    private ProcessState resumeState = ProcessState.READY;
    private final long processStartMs;

    // 进入用户函数体后置为 true，dispatchStatement 据此跳过 currentLine++
    private volatile boolean enteredUserFunction = false;
    private boolean controlTransferred;

    // 待赋值变量名：调用用户函数时的赋值目标变量，函数返回后完成赋值
    private String pendingAssignmentVar = null;

    // ── 虚拟线程管理（全局） ──
    // PID → 虚拟线程映射，用于跨进程 unpark（kill 唤醒 wait）
    private static final ConcurrentHashMap<Integer, Thread> VIRTUAL_THREADS = new ConcurrentHashMap<>();

    // 运行时状态（每次 tick 从文件加载 → 执行 → 写回）
    private Map<String, Object> processData;
    private Map<String, Object> data;
    private List<String> codeLines;
    private int currentLine;
    private List<Map<String, Object>> blockStack;
    private String processGeneration;
    private String effectiveUser;
    private Map<String, String> pathAliases = new LinkedHashMap<>();
    private String activePackageDataPath;
    private String ownedGeneration;
    private volatile boolean registered;
    private volatile boolean executorStopping;

    // ════════════════════════════════════════════
    // 构造
    // ════════════════════════════════════════════

    public ProcessRunner(int pid, Map<String, Object> processData) {
        this.pid = pid;
        this.processData = processData;
        ProcessIdentity.ensureDefaults(this.processData);
        this.processStartMs = System.currentTimeMillis();

        // 创建组件 —— ProcessRunner 负责构造和生命周期
        this.stateManager = new StateManager(pid, processStartMs, processData);
        this.state = stateManager.getState();
        this.blockReason = stateManager.getBlockReason();
        this.exitReason = stateManager.getExitReason();
        this.stateMessage = stateManager.getStateMessage();
        this.codeLoader = new CodeLoader();
        this.attemptManager = new StatementAttemptManager(this.processData, this::persistState);
        this.expressionEvaluator = new ExpressionEvaluator(
                pid, stateManager::extractParentPid, this::onFunctionArgCallback,
                this::createFunctionContext);
        this.controlFlow = new ControlFlow(expressionEvaluator);
        this.importManager = new ImportManager(() -> effectiveUser,
                () -> new LinkedHashMap<>(pathAliases), this::currentScriptPath);
        this.functionManager = new FunctionManager(pid, expressionEvaluator);
        this.ipcHandler = new IpcHandler(
                pid,
                this::reloadFromFile,
                () -> currentLine,
                () -> this.processData,
                this::blockProcess,
                codeLoader,
                stateManager
        );

        // 从进程数据加载初始状态
        loadFromProcessDataInternal();
        syncLifecycleFromManager();

    }

    // ════════════════════════════════════════════
    // 公共 API（供 Scheduler 调用）
    // ════════════════════════════════════════════

    public void init() {
        try {
            // Scheduler scans can become stale before construction; disk remains authoritative.
            loadRuntimeState();
            ownedGeneration = processGeneration;
            stateManager.setExpectedGeneration(ownedGeneration);
            RUNNERS.compute(pid, (ignored, existing) ->
                    existing == null || !existing.isRunning() ? this : existing);
            if (RUNNERS.get(pid) != this) {
                throw new IllegalStateException("An active runner already owns PID " + pid);
            }
            registered = true;
            priority = stateManager.extractPriority();
            functionManager.parseFunctions(codeLines);
            // A crash may leave RUNNING on disk; the committed instruction snapshot is safe to resume.
            if (state == ProcessState.NEW || state == ProcessState.RUNNING) {
                transitionTo(ProcessState.READY, null);
            }
            if (!state.isTerminal()) persistState();
            Logger.info("Process " + pid + " (" + getProcessName() + ") initialized, priority=" + priority);
        } catch (RuntimeException e) {
            if (registered) RUNNERS.remove(pid, this);
            registered = false;
            throw e;
        }
    }

    public StepResult step() {
        StepResult result;
        boolean reconcileLifecycle = false;
        synchronized (persistenceLock) {
            CURRENT_RUNNER.set(this);
            UserUtil.setCurrentUser(effectiveUser);
            try {
                result = stepAtInstructionBoundary();
                if (result == StepResult.TERMINATED && !lifecycleCleaned) {
                    lifecycleCleaned = true;
                    reconcileLifecycle = true;
                }
            } finally {
                CURRENT_RUNNER.remove();
                UserUtil.clearCurrentUser();
            }
        }
        if (reconcileLifecycle) reconcileLifecycle(pid, processGeneration);
        return result;
    }

    private StepResult stepAtInstructionBoundary() {
        if (state.isTerminal()) return StepResult.TERMINATED;

        try {
            // Disk is the baseline. Merge queued control requests before entering RUNNING.
            loadRuntimeState();
            boolean externallyChanged = processPendingMessages();
            if (state.isTerminal()) return StepResult.TERMINATED;
            if (state == ProcessState.BLOCKED || state == ProcessState.PAUSED) {
                if (externallyChanged) persistState();
                return StepResult.BLOCKED;
            }
            transitionTo(ProcessState.RUNNING, null);
            String statement = currentLine >= 0 && currentLine < codeLines.size()
                    ? codeLines.get(currentLine) : "<eof>";
            attemptManager.begin(currentLine, statement);
            executeLine();
            if (state == ProcessState.BLOCKED || state == ProcessState.PAUSED) return StepResult.BLOCKED;
            if (state == ProcessState.FAILED) {
                return StepResult.TERMINATED;
            }
            if (!running) {
                if (!state.isTerminal()) terminateNormally("Program completed");
                attemptManager.commit();
                persistState();
                return StepResult.TERMINATED;
            }
            transitionTo(ProcessState.READY, null);
            return StepResult.COMPLETED;
        } catch (Exception e) {
            handleException(e, "step");
            if (state.isTerminal()) {
                return StepResult.TERMINATED;
            }
            transitionTo(ProcessState.READY, null);
            return StepResult.COMPLETED;
        }
    }

    public boolean checkWakeup() {
        if (state != ProcessState.BLOCKED) return true;
        if (blockReason == BlockReason.EFFECT_RECOVERY) return false;
        if (blockReason == BlockReason.NONE) {
            unblockProcess();
            return true;
        }
        // 从文件加载以获取最新子进程状态
        reloadFromFile();
        if (!running) {
            return false;
        }
        if (consumeMatchingExitEvent()) {
            unblockProcess();
            Logger.info("Process " + pid + " woken by a recorded child exit");
            return true;
        }
        // 没有子进程 → 不阻塞
        @SuppressWarnings("unchecked")
        Map<String, Object> children = (Map<String, Object>) processData.get("Child");
        if (children == null || children.isEmpty()) {
            unblockProcess();
            Logger.info("Process " + pid + " woken from wait (no children)");
            return true;
        }
        Integer waitPid = null;
        if (blockReason == BlockReason.WAIT_PID) {
            Object target = processData.get("BlockTargetPid");
            if (target instanceof Number) waitPid = ((Number) target).intValue();
        }
        // WAIT_PID 只关注目标进程；WAIT_ANY 在任意子进程结束时唤醒。
        for (String pidStr : children.keySet()) {
            try {
                int childPid = Integer.parseInt(pidStr);
                if (waitPid != null && childPid != waitPid) continue;
                Object childInfo = children.get(pidStr);
                String expectedChildGeneration = childInfo instanceof Map
                        && ((Map<?, ?>) childInfo).get("Generation") instanceof String
                        ? ((Map<?, ?>) childInfo).get("Generation").toString() : null;
                if (isChildFinished(childPid, expectedChildGeneration)) {
                    Map<String, Object> recoveredEvent = new LinkedHashMap<>();
                    recoveredEvent.put("PID", childPid);
                    if (childInfo instanceof Map && ((Map<?, ?>) childInfo).get("Generation") instanceof String) {
                        recoveredEvent.put("Generation", ((Map<?, ?>) childInfo).get("Generation"));
                    }
                    recoveredEvent.put("ExitReason", ExitReason.NONE.name());
                    recordChildExitInMemory(pidStr, recoveredEvent);
                    consumeMatchingExitEvent();
                    unblockProcess();
                    Logger.info("Process " + pid + " woken from wait (child " + childPid + " terminated)");
                    return true;
                }
            } catch (NumberFormatException ignored) {}
        }
        return false; // 仍在等待
    }

    public void stopProcess() {
        // Stop the Java executor without changing the persisted FCL lifecycle.
        // A later Cilexec start can resume the last committed process snapshot.
        executorStopping = true;
        unparkProcess(pid);
        synchronized (persistenceLock) {
            RUNNERS.remove(pid, this);
            registered = false;
        }
        // 清理本进程的函数定义，防止跨进程残留
        com.follarce.function.FunctionRegistry.clearUserFunctions(pid);
    }

    // ── 访问器 ──

    public int getPid() { return pid; }
    public int getPriority() { return priority; }
    public ProcessState getState() { return state; }
    public String getProcessName() { return stateManager.extractName(); }
    public boolean isRunning() { return running && !executorStopping && !state.isTerminal(); }
    public String getProcessGeneration() { return processGeneration; }

    // ════════════════════════════════════════════
    // 虚拟线程运行
    // ════════════════════════════════════════════

    /**
     * 虚拟线程入口 —— 每进程一个虚拟线程，自循环执行 FCL 代码。
     * <p>
     * 设计要点：
     * <ul>
     *   <li>每次 step() 后 {@link Thread#yield()} 让出 CPU，供其他虚拟线程竞争</li>
     *   <li>BLOCKED 时用 {@link LockSupport#parkNanos(long)} 休眠，不占平台线程</li>
     *   <li>每行执行后持久化到 .proc 文件</li>
     *   <li>自然结束或因异常退出时自动清理</li>
     * </ul>
     */
    public void virtualThreadRun() {
        Thread.currentThread().setName("vt-process-" + pid);
        VIRTUAL_THREADS.put(pid, Thread.currentThread());
        Logger.info("Virtual thread started for PID " + pid + " (" + getProcessName() + ")");

        try {
            UserUtil.setCurrentUser(effectiveUser);

            while (!executorStopping && running && !state.isTerminal()) {
                StepResult result = step();

                if (result == StepResult.TERMINATED) {
                    Logger.info("Virtual thread: PID " + pid + " terminated naturally");
                    VIRTUAL_THREADS.remove(pid, Thread.currentThread());
                    return;
                }

                if (result == StepResult.BLOCKED) {
                    parkWhileBlocked();
                    continue;
                }

                // 让出 CPU 给其他虚拟线程
                if (!executorStopping && running && !state.isTerminal()
                        && state != ProcessState.BLOCKED && state != ProcessState.PAUSED) {
                    Thread.yield();
                }
            }
        } catch (Exception e) {
            Logger.error("Virtual thread for PID " + pid + " crashed: " + e.getMessage());
        } finally {
            UserUtil.clearCurrentUser();
            VIRTUAL_THREADS.remove(pid, Thread.currentThread());
            RUNNERS.remove(pid, this);
            registered = false;
            Logger.info("Virtual thread for PID " + pid + " (" + getProcessName() + ") finished");
        }
    }

    /**
     * 虚拟线程阻塞等待 —— 用 parkNanos 轮询子进程状态。
     * <p>
     * 虚拟线程 park 时不占用平台线程，JVM 会将其从载体线程卸载。
     * 每 50ms 检查一次唤醒条件，或被 kill 处理中的 unpark 提前唤醒。
     */
    private void parkWhileBlocked() {
        while ((state == ProcessState.BLOCKED || state == ProcessState.PAUSED)
                && running && !executorStopping) {
            boolean terminated;
            synchronized (persistenceLock) {
                if (processPendingMessages()) persistState();
                terminated = state.isTerminal();
                if (!terminated && state != ProcessState.PAUSED && checkWakeup()) {
                    Logger.info("Process " + pid + " woken from wait (virtual thread)");
                    return;
                }
            }
            if (terminated) {
                step();
                return;
            }
            LockSupport.parkNanos(50_000_000L);
        }
    }

    /**
     * 唤醒指定 PID 的虚拟线程（用于 kill 处理中告知等待的父进程）。
     */
    public static void unparkProcess(int pid) {
        Thread vt = VIRTUAL_THREADS.get(pid);
        if (vt != null) {
            LockSupport.unpark(vt);
            Logger.info("Unparked virtual thread for PID " + pid);
        }
    }

    /**
     * 向指定进程发送字段更新消息。
     * <p>
     * 这是 Java 层修改进程数据的<strong>唯一入口</strong>——任何外部组件
     * 想修改某个进程的某字段，必须通过此方法。消息由目标进程的
     * ProcessRunner 在 executeLine() 开头处理，保证字段与行号同时落盘。
     * <p>
     * 若目标进程不在 RUNNERS 注册表中（已终止或尚未启动），
     * 则直接写 .proc 文件作为兜底。
     *
     * @param targetPid 目标进程 PID
     * @param field     要更新的字段名
     * @param value     新值
     */
    public static void postMessage(int targetPid, String field, Object value) {
        postMessage(targetPid, field, value, UUID.randomUUID().toString(), 0, null);
    }

    public static void postMessage(int targetPid, String field, Object value, String messageId,
                                   int senderPid, String senderGeneration) {
        String processPath = Constants.SYSTEM_PROCESS_PATH + targetPid + ".proc";
        if (!com.follarce.util.FileUtil.exists(processPath)) return;
        Map<String, Object> targetData = com.follarce.util.JsonUtil.parseToMapStrict(
                com.follarce.util.FileUtil.read(processPath));
        if (ProcessIdentity.ensureDefaults(targetData)) {
            com.follarce.util.JsonUtil.updateFile(processPath, ProcessIdentity::ensureDefaults);
            targetData = com.follarce.util.JsonUtil.parseToMapStrict(
                    com.follarce.util.FileUtil.read(processPath));
        }
        String targetGeneration = ProcessIdentity.generation(targetData);
        postMessageToGeneration(targetPid, targetGeneration, field, value, messageId,
                senderPid, senderGeneration);
    }

    public static boolean postMessageToGeneration(int targetPid, String targetGeneration,
                                                  String field, Object value, String messageId,
                                                  int senderPid, String senderGeneration) {
        String processPath = Constants.SYSTEM_PROCESS_PATH + targetPid + ".proc";
        if (!com.follarce.util.FileUtil.exists(processPath)) return false;
        Map<String, Object> targetData = com.follarce.util.JsonUtil.parseToMapStrict(
                com.follarce.util.FileUtil.read(processPath));
        if (!targetGeneration.equals(targetData.get("ProcessGeneration"))) return false;
        if (ProcessInbox.isApplied(targetData, messageId)) return true;
        ProcessMessage published = ProcessInbox.publish(targetPid, targetGeneration, messageId, senderPid,
                senderGeneration, field, value);
        if (!targetGeneration.equals(published.targetGeneration())) return false;

        ProcessRunner target = RUNNERS.get(targetPid);
        if (target != null && target.isRunning() && targetGeneration.equals(target.processGeneration)) {
            unparkProcess(targetPid);
        } else {
            applyOfflineInbox(targetPid, targetGeneration);
        }
        return true;
    }



    /**
     * 统一的字段更新入口 —— 外部消息和 ProcessRunner 内部都走此方法。
     * <p>
     * 支持点号分隔的嵌套路径，例如：
     * <ul>
     *   <li>{@code "Status"} — 顶层字段</li>
     *   <li>{@code "Program.Data.x"} — 等价于 {@code processData.Program.Data.x = value}</li>
     *   <li>{@code "Child.2.Status"} — 等价于 {@code processData.Child["2"].Status = value}</li>
     * </ul>
     * 路径中的每一段都是 Map 的 key，末段为最终设置值的 key。
     * 中间路径若不存在则自动创建 LinkedHashMap。
     * <p>
     * {@code ProcessState} 是生命周期控制入口；布尔 {@code Status} 仅兼容旧调用方。
     *
     * @param field 字段路径，点号分隔
     * @param value 新值
     */
    @SuppressWarnings("unchecked")
    private void applyFieldUpdate(String field, Object value) {
        if ("__Terminate".equals(field)) {
            running = false;
            exitReason = ExitReason.KILLED;
            blockReason = BlockReason.NONE;
            transitionTo(ProcessState.TERMINATED, "Killed");
            prepareLifecycleCleanup(true);
            return;
        }
        if (field.startsWith("ChildExit.")) {
            recordChildExitInMemory(field.substring("ChildExit.".length()), value);
            return;
        }
        String[] parts = field.split("\\.");
        if (parts.length == 1 && "ProcessState".equals(parts[0])) {
            applyRequestedState(value);
            return;
        }
        if (parts.length == 1 && "Status".equals(parts[0])) {
            applyRequestedState(Boolean.FALSE.equals(value)
                    ? ProcessState.PAUSED.name() : ProcessState.READY.name());
            return;
        }
        Map<String, Object> current = processData;

        // 沿路径深入到倒数第二段
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }

        // 末段设值
        String leafKey = parts[parts.length - 1];
        if (value == null) current.remove(leafKey);
        else current.put(leafKey, value);

    }

    /**
     * 终止指定进程 —— 停止 ProcessRunner 并删除 .proc 文件。
     * <p>
     * kill 操作始终删除进程文件，不受 {@link Constants#DELETE_PROCESS_FILE_ON_EXIT} 影响。
     * 与 pause 不同：pause 转入 PAUSED 并保留快照，kill 是彻底终止。
     */
    public static void terminateProcess(int targetPid) {
        ProcessRunner target = RUNNERS.get(targetPid);
        String generation = null;
        if (target != null) {
            synchronized (target.persistenceLock) {
                generation = target.processGeneration;
                if (!target.state.isTerminal()) {
                    target.running = false;
                    target.exitReason = ExitReason.KILLED;
                    target.transitionTo(ProcessState.TERMINATED, "Killed");
                }
                target.prepareLifecycleCleanup(true);
                target.persistState();
                target.killed = true;
                target.lifecycleCleaned = true;
            }
        } else {
            String path = com.follarce.util.PathUtil.findProcessFilePathByPid(targetPid);
            if (path != null && com.follarce.util.FileUtil.exists(path)) {
                Map<String, Object> snapshot = com.follarce.util.JsonUtil.parseToMapStrict(
                        com.follarce.util.FileUtil.read(path));
                if (ProcessIdentity.ensureDefaults(snapshot)) {
                    com.follarce.util.JsonUtil.updateFile(path, ProcessIdentity::ensureDefaults);
                    snapshot = com.follarce.util.JsonUtil.parseToMapStrict(
                            com.follarce.util.FileUtil.read(path));
                }
                generation = ProcessIdentity.generation(snapshot);
                String expectedGeneration = generation;
                com.follarce.util.JsonUtil.updateFile(path, data -> {
                    if (!expectedGeneration.equals(data.get("ProcessGeneration"))) return;
                    if (!ProcessState.restore(data.get("ProcessState"), data.get("Status")).isTerminal()) {
                        data.put("ProcessState", ProcessState.TERMINATED.name());
                        data.put("Status", false);
                        data.put("ExitReason", ExitReason.KILLED.name());
                        data.put("StateMessage", "Killed");
                    }
                    data.put("LifecycleCleanup", lifecycleCleanupRecord(data, true));
                });
            }
        }
        if (generation != null) reconcileLifecycle(targetPid, generation);
        Logger.info("Terminated process PID " + targetPid + ", file removed");
    }

    /** Queue FCL-initiated kills so no process waits on another runner while holding its own lock. */
    public static void requestTermination(int targetPid) {
        ProcessRunner caller = CURRENT_RUNNER.get();
        if (caller == null) {
            terminateProcess(targetPid);
            return;
        }
        requestTermination(targetPid, UUID.randomUUID().toString(), caller.pid, caller.processGeneration);
    }

    public static void requestTermination(int targetPid, String messageId,
                                           int senderPid, String senderGeneration) {
        postMessage(targetPid, "__Terminate", ExitReason.KILLED.name(), messageId,
                senderPid, senderGeneration);
    }

    public static boolean requestTermination(int targetPid, String targetGeneration,
                                             String messageId, int senderPid,
                                             String senderGeneration) {
        return postMessageToGeneration(targetPid, targetGeneration, "__Terminate",
                ExitReason.KILLED.name(), messageId, senderPid, senderGeneration);
    }

    /** Record a durable child exit event without losing other simultaneous exits. */
    public static void recordChildExit(int parentPid, int childPid, ExitReason reason) {
        recordChildExit(parentPid, null, childPid, null, reason);
    }

    public static void recordChildExit(int parentPid, String parentGeneration,
                                       int childPid, String childGeneration, ExitReason reason) {
        // A PID without its incarnation is never a safe lifecycle target.
        if (parentGeneration == null || childGeneration == null) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("PID", childPid);
        if (childGeneration != null) event.put("Generation", childGeneration);
        event.put("ExitReason", (reason != null ? reason : ExitReason.NONE).name());

        ProcessRunner parent = RUNNERS.get(parentPid);
        if (parent != null && !parent.state.isTerminal()
                && (parentGeneration == null || parentGeneration.equals(parent.processGeneration))) {
            synchronized (parent.persistenceLock) {
                if (parent.state.isTerminal()
                        || (parentGeneration != null && !parentGeneration.equals(parent.processGeneration))) return;
                recordChildExit(parent.processData, childPid, childGeneration, event);
                parent.persistState();
            }
            unparkProcess(parentPid);
            return;
        }

        String path = Constants.SYSTEM_PROCESS_PATH + parentPid + ".proc";
        if (!com.follarce.util.FileUtil.exists(path)) return;
        com.follarce.util.JsonUtil.updateFile(path, data -> {
            if (parentGeneration != null && !parentGeneration.equals(data.get("ProcessGeneration"))) return;
            ProcessState saved = ProcessState.restore(data.get("ProcessState"), data.get("Status"));
            if (!saved.isTerminal()) recordChildExit(data, childPid, childGeneration, event);
        });
    }

    /** Change both sides of an orphan relationship to INIT. */
    public static void reparentToInit(int childPid, Object childInfo) {
        if (childPid == Constants.PID_INIT) return;
        String childGeneration = childInfo instanceof Map
                && ((Map<?, ?>) childInfo).get("Generation") instanceof String
                ? ((Map<?, ?>) childInfo).get("Generation").toString() : null;
        if (childGeneration == null) return;
        String initGeneration = readProcessGeneration(Constants.PID_INIT);
        Map<String, Object> initParent = new LinkedHashMap<>();
        initParent.put("PID", Constants.PID_INIT);
        initParent.put("Name", "INIT");
        if (initGeneration != null) initParent.put("Generation", initGeneration);

        boolean updated = updateProcessData(childPid, childGeneration, data -> {
            ProcessState saved = ProcessState.restore(data.get("ProcessState"), data.get("Status"));
            if (!saved.isTerminal()) data.put("Parent", initParent);
        }, true);
        if (updated) {
            updateProcessData(Constants.PID_INIT, initGeneration,
                    data -> setNestedField(data, "Child." + childPid, childInfo), false);
        }
    }

    /**
     * 处理所有待处理的外部消息 —— 在 executeLine() 开头调用，
     * 确保任何外部请求在当前行执行前落地。
     */
    private boolean processPendingMessages() {
        boolean changed = false;
        for (ProcessMessage msg : ProcessInbox.list(pid, processGeneration)) {
            if (msg.targetPid() != pid || !processGeneration.equals(msg.targetGeneration())) continue;
            if (ProcessInbox.isApplied(processData, msg.messageId())) {
                ProcessInbox.acknowledge(msg);
                continue;
            }
            applyFieldUpdate(msg.field(), msg.value());
            if ("__Terminate".equals(msg.field())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cleanup = (Map<String, Object>) processData.get("LifecycleCleanup");
                if (cleanup != null) cleanup.put("RequestId", msg.messageId());
            }
            ProcessInbox.recordApplied(processData, msg);
            persistState();
            if ("__Terminate".equals(msg.field())) killed = true;
            ProcessInbox.acknowledge(msg);
            changed = true;
        }
        return changed;
    }

    // ════════════════════════════════════════════
    // 主执行逻辑
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void executeLine() {
        try {
            // The state snapshot and external messages were merged by step().
            if (!running) {
                persistState();
                return;
            }

            // 1. 检查是否执行完毕
            if (currentLine >= codeLines.size()) {
                // 如果在函数调用中，自动返回到调用者
                if (functionManager.isInCall()) {
                    FunctionManager.CallFrame frame = functionManager.popFrame();
                    this.data = frame.savedData;
                    this.codeLines = frame.savedCodeLines;
                    this.blockStack = new ArrayList<>(frame.savedBlockStack);
                    this.activePackageDataPath = frame.savedPackageDataPath;
                    completePendingAssignment();
                    codeChanged();
                    settle(frame.savedCurrentLine + 1);
                    return;
                }
                terminateNormally("Program completed");
                attemptManager.commit();
                persistState();
                return;
            }

            String line = codeLines.get(currentLine);

            // 3. 跳过空行和残留注释（兼容旧 .proc 文件中未剔除的注释行）
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("//") || trimmedLine.startsWith("#")) {
                settle(currentLine + 1);
                return;
            }

            // 4. 处理花括号行
            if (line.trim().startsWith("}")) {
                int[] counts = ControlFlow.countBraces(line);
                settle(controlFlow.handleClosingBraces(counts[1], currentLine));
                return;
            }
            if (line.trim().equals("{")) {
                settle(currentLine + 1);
                return;
            }

            // 5. 语句分发
            dispatchStatement(line);

        } catch (Exception e) {
            handleException(e, "line: " + (currentLine < codeLines.size() ? codeLines.get(currentLine) : "?"));
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatchStatement(String line) {
        controlTransferred = false;
        String trimmed = line.trim();

        // func 定义
        if (trimmed.startsWith("func ")) {
            settle(skipFunctionBody(currentLine));
            return;
        }

        // import
        if (trimmed.startsWith("import ")) {
            @SuppressWarnings("unchecked")
            List<String> imported = (List<String>) invokeEngineEffect(
                    "engine.import", EffectPolicy.RECORDED_RESULT, List.of(trimmed),
                    ignored -> importManager.handleImport(trimmed, codeLines));
            for (String imp : imported) {
                importManager.addImportedFile(imp);
            }
            codeChanged();
            functionManager.setPackageDataByFunction(importManager.getPackageDataByFunction());
            functionManager.parseFunctions(codeLines);
            settle(currentLine + 1);
            return;
        }

        // include
        if (trimmed.startsWith("include ")) {
            int newLine = importManager.handleInclude(trimmed, codeLines, currentLine);
            codeChanged();
            settle(newLine);
            return;
        }

        // if
        if (trimmed.startsWith("if ") || trimmed.startsWith("if(")) {
            String condition = extractCondition(trimmed, "if");
            settle(controlFlow.handleIf(condition, currentLine));
            return;
        }

        // else — if 条件为 false 且无边界表时回退到此处
        if (trimmed.equals("else") || trimmed.startsWith("else ")) {
            int newLine = currentLine + 1;
            while (newLine < codeLines.size()) {
                String nl = codeLines.get(newLine).trim();
                if (nl.equals("{")) { newLine++; continue; }
                break;
            }
            settle(newLine);
            return;
        }

        // while
        if (trimmed.startsWith("while ") || trimmed.startsWith("while(")) {
            String condition = extractCondition(trimmed, "while");
            settle(controlFlow.handleWhile(condition, currentLine));
            return;
        }

        // switch
        if (trimmed.startsWith("switch ") || trimmed.startsWith("switch(")) {
            String expr = extractCondition(trimmed, "switch");
            settle(controlFlow.handleSwitch(expr, currentLine));
            return;
        }

        // case：隐式 break —— 已在 switch 内且匹配完成，跳到 switch 结尾
        if (trimmed.startsWith("case ")) {
            if (!controlFlow.getBlockStack().isEmpty()) {
                java.util.Map<String, Object> top = controlFlow.getBlockStack()
                        .get(controlFlow.getBlockStack().size() - 1);
                if ("SWITCH".equals(top.get("type"))) {
                    int el = ((Number) top.get("endLine")).intValue();
                    controlFlow.getBlockStack().remove(
                            controlFlow.getBlockStack().size() - 1);
                    settle(el + 1);
                    return;
                }
            }
            settle(currentLine + 1);
            return;
        }

        // default：与 case 相同处理
        if (trimmed.equals("default")) {
            if (!controlFlow.getBlockStack().isEmpty()) {
                java.util.Map<String, Object> top = controlFlow.getBlockStack()
                        .get(controlFlow.getBlockStack().size() - 1);
                if ("SWITCH".equals(top.get("type"))) {
                    int el = ((Number) top.get("endLine")).intValue();
                    controlFlow.getBlockStack().remove(
                            controlFlow.getBlockStack().size() - 1);
                    settle(el + 1);
                    return;
                }
            }
            settle(currentLine + 1);
            return;
        }

        // return
        if (trimmed.startsWith("return")) {
            String expr = trimmed.substring(6).trim();
            ControlFlow.ReturnResult ret = controlFlow.handleReturn(expr, functionManager.isInCall(), currentLine);
            if (ret.hasCaller) {
                FunctionManager.CallFrame frame = functionManager.popFrame();
                if (frame != null) {
                    this.data = frame.savedData;
                    this.codeLines = frame.savedCodeLines;
                    this.blockStack = new ArrayList<>(frame.savedBlockStack);
                    this.activePackageDataPath = frame.savedPackageDataPath;
                    // 数据变更必须在 settle 之前完成
                    if (ret.value != null) {
                        data.put("__return_value", ret.value);
                    }
                    completePendingAssignment();
                    codeChanged();
                    settle(frame.savedCurrentLine + 1);
                }
            } else {
                terminateNormally("Top-level return");
                settle(codeLines.size());
            }
            return;
        }

        // break
        if (trimmed.equals("break")) {
            settle(controlFlow.handleBreak(currentLine));
            return;
        }

        // continue
        if (trimmed.equals("continue")) {
            settle(controlFlow.handleContinue(currentLine));
            return;
        }

        // fork()
        if (trimmed.matches("^\\s*fork\\s*\\(\\s*\\)\\s*$")) {
            invokeEngineEffect("process.fork", EffectPolicy.LOCAL_TRANSACTIONAL, List.of(),
                    invocation -> invokeFork(null, invocation));
            settle(currentLine + 1);
            return;
        }

        // exec(...)
        if (trimmed.startsWith("exec(") || trimmed.startsWith("exec (")) {
            attemptManager.commit();
            ipcHandler.handleExec(trimmed);
            // exec 替换了代码，重新加载
            loadRuntimeState();
            return;
        }

        // 索引赋值 arr[0] = expr
        java.util.regex.Matcher indexAssignMatcher = ExpressionEvaluator.INDEX_ASSIGN_PATTERN.matcher(trimmed);
        if (indexAssignMatcher.matches()) {
            // 数据变更在 handleIndexAssignment 内完成，然后行号+数据一起落盘
            handleIndexAssignment(indexAssignMatcher, line);
            settle(currentLine + 1);
            return;
        }

        // 普通赋值 x = expr
        java.util.regex.Matcher assignMatcher = ExpressionEvaluator.ASSIGN_PATTERN.matcher(trimmed);
        if (assignMatcher.matches()) {
            // 数据变更在 handleAssignment 内完成，然后行号+数据一起落盘
            handleAssignment(assignMatcher, line);
            if (controlTransferred) { controlTransferred = false; return; }
            if (enteredUserFunction) { enteredUserFunction = false; persistState(); return; }
            settle(currentLine + 1);
            return;
        }

        // 通用表达式（函数调用、字面量等）
        Object exprResult = expressionEvaluator.evaluateExpression(trimmed);
        if (exprResult instanceof String) {
            String marker = (String) exprResult;
            handleMarker(marker);
            if (controlTransferred) { controlTransferred = false; return; }
            if (state == ProcessState.BLOCKED) {
                settle(currentLine + 1);
                return;
            }
        }
        if (enteredUserFunction) { enteredUserFunction = false; persistState(); return; }
        settle(currentLine + 1);
    }

    // ════════════════════════════════════════════
    // 赋值处理
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void handleAssignment(java.util.regex.Matcher matcher, String rawLine) {
        String varName = matcher.group(1).trim();
        String expr = matcher.group(2).trim();

        if (expr.startsWith("func ")) {
            // 函数定义赋值：func f(x) { ... }
            expressionEvaluator.setData(data);
            expressionEvaluator.evaluateExpression(varName);
            return;
        }

        expressionEvaluator.setData(data);
        Object value = expressionEvaluator.evaluateExpression(expr);

        if (value instanceof String) {
            String marker = (String) value;
            // 用户函数调用：在被 handleMarkerResult 替换代码/数据前，
            // 先将赋值目标存入 __pending_assign，随 call frame 一起保存
            if (marker.startsWith("USER:")) {
                data.put("__pending_assign", varName);
            }
            value = handleMarkerResult(marker, varName);
        }

        // 若进入了用户函数体，赋值延迟到函数返回后完成
        if (enteredUserFunction) return;
        if (controlTransferred) return;

        data.put(varName, value);
    }

    @SuppressWarnings("unchecked")
    private void handleIndexAssignment(java.util.regex.Matcher matcher, String rawLine) {
        String varName = matcher.group(1).trim();
        String indexChain = matcher.group(2).trim();
        String valueExpr = matcher.group(3).trim();

        Object value = expressionEvaluator.evaluateExpression(valueExpr);
        Object target = data.get(varName);
        java.util.regex.Matcher indexMatcher = java.util.regex.Pattern
                .compile("\\[([^\\]]+)\\]").matcher(indexChain);
        List<Object> indices = new ArrayList<>();
        while (indexMatcher.find()) {
            indices.add(expressionEvaluator.evaluateExpression(indexMatcher.group(1).trim()));
        }
        if (indices.isEmpty()) {
            throw new IllegalArgumentException("Index assignment requires at least one index: " + rawLine);
        }

        for (int i = 0; i < indices.size() - 1; i++) {
            target = indexedValue(target, indices.get(i), data);
            if (target == null) {
                throw new IllegalArgumentException("Cannot traverse index " + indices.get(i)
                        + " in assignment: " + rawLine);
            }
        }
        Object index = indices.getLast();
        if (target instanceof List) {
            int idx = toIntIndex(index, data);
            if (idx >= 0 && idx < ((List<Object>) target).size()) {
                ((List<Object>) target).set(idx, value);
            }
        } else if (target instanceof Map) {
            ((Map<Object, Object>) target).put(index, value);
        } else if (target instanceof Object[]) {
            int idx = toIntIndex(index, data);
            if (idx >= 0 && idx < ((Object[]) target).length) {
                ((Object[]) target)[idx] = value;
            }
        } else {
            throw new IllegalArgumentException("Index assignment target is not a list, map, or array: "
                    + rawLine);
        }
    }

    private static Object indexedValue(Object target, Object index, Map<String, Object> variables) {
        if (target instanceof List<?>) {
            int idx = toIntIndex(index, variables);
            return idx >= 0 && idx < ((List<?>) target).size() ? ((List<?>) target).get(idx) : null;
        }
        if (target instanceof Map<?, ?>) return ((Map<?, ?>) target).get(index);
        if (target instanceof Object[]) {
            int idx = toIntIndex(index, variables);
            return idx >= 0 && idx < ((Object[]) target).length ? ((Object[]) target)[idx] : null;
        }
        return null;
    }

    // ════════════════════════════════════════════
    // 特殊标记处理
    // ════════════════════════════════════════════

    /**
     * 处理表达式求值返回的标记字符串（在通用表达式中触发）。
     */
    @SuppressWarnings("unchecked")
    private void handleMarker(String marker) {
        if (marker == null) return;
        if (marker.equals("FORK")) {
            invokeEngineEffect("process.fork", EffectPolicy.LOCAL_TRANSACTIONAL, List.of(),
                    invocation -> invokeFork(null, invocation));
            return;
        }
        if (marker.startsWith("KILL:")) {
            String target = marker.substring(5);
            invokeControlEffect("process.kill", target, ipcHandler::handleKill);
        } else if (marker.equals("WAIT")) {
            ipcHandler.handleWait();
        } else if (marker.startsWith("WAITPID:")) {
            ipcHandler.handleWaitPid(marker.substring(8));
        } else if (marker.startsWith("PAUSE:")) {
            String target = marker.substring(6);
            invokeControlEffect("process.pause", target, ipcHandler::handlePause);
        } else if (marker.startsWith("CONTINUE:")) {
            String target = marker.substring(9);
            invokeControlEffect("process.continue", target, ipcHandler::handleContinue);
        } else if (marker.startsWith("EXEC:")) {
            attemptManager.commit();
            ipcHandler.handleExecMarker(marker);
            loadRuntimeState();
            controlTransferred = true;
        } else if (marker.equals("EXIT")) {
            terminateNormally("Exit requested");
        } else if (marker.startsWith("USER:")) {
            String funcName = marker.substring(5);
            handleUserFunctionCall(funcName);
        }
    }

    /**
     * 处理赋值语境中的标记求值结果。
     */
    @SuppressWarnings("unchecked")
    private Object handleMarkerResult(String marker, String varName) {
        if (marker.equals("FORK")) {
            return invokeEngineEffect("process.fork", EffectPolicy.LOCAL_TRANSACTIONAL,
                    List.of(varName), invocation -> this.invokeFork(varName, invocation));
        } else if (marker.startsWith("KILL:")) {
            handleMarker(marker);
            return true;
        } else if (marker.equals("WAIT")) {
            ipcHandler.handleWait();
            return true; // 不阻塞
        } else if (marker.startsWith("WAITPID:")) {
            ipcHandler.handleWaitPid(marker.substring(8));
            return true;
        } else if (marker.startsWith("PAUSE:")) {
            handleMarker(marker);
            return true;
        } else if (marker.startsWith("CONTINUE:")) {
            handleMarker(marker);
            return true;
        } else if (marker.startsWith("EXEC:") || marker.equals("EXIT")) {
            handleMarker(marker);
            return true;
        } else if (marker.startsWith("USER:")) {
            String funcName = marker.substring(5);
            handleUserFunctionCall(funcName);
            Object retVal = data.get("__return_value");
            if (retVal != null) {
                data.remove("__return_value");
            }
            return retVal != null ? retVal : true;
        }
        return marker;
    }

    // ════════════════════════════════════════════
    // 用户函数调用
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void handleUserFunctionCall(String funcName) {
        FunctionDef def = functionManager.getFunction(funcName);
        if (def == null) {
            Logger.warn("User function not found: " + funcName);
            return;
        }
        FunctionManager.CallFrame frame = functionManager.saveFrame(
                new LinkedHashMap<>(data), new ArrayList<>(codeLines), currentLine,
                new ArrayList<>(blockStack), activePackageDataPath);
        this.data = new LinkedHashMap<>();
        this.codeLines = new ArrayList<>(def.bodyLines);
        this.currentLine = 0;
        this.blockStack = new ArrayList<>();
        this.activePackageDataPath = def.packageDataPath;

        List<Object> args = functionManager.getPendingFuncArgs();
        functionManager.clearPending();
        if (args == null) args = new ArrayList<>();
        if (def.params != null && args != null) {
            for (int i = 0; i < def.params.size() && i < args.size(); i++) {
                data.put(def.params.get(i), args.get(i));
            }
        }
        codeChanged();
        enteredUserFunction = true;  // 告知 dispatchStatement 跳过 currentLine++
        attemptManager.commit();
        persistState();
    }

    // ════════════════════════════════════════════
    // 函数参数回调（由 NodeEvaluator 触发）
    // ════════════════════════════════════════════

    private void onFunctionArgCallback(String funcName, List<Object> args) {
        // 检查是否是用户函数（非系统内置函数）
        if (functionManager.getFunction(funcName) != null) {
            functionManager.setFunctionArgs(funcName, args);
            // 在表达式中触发用户函数调用的标记
            // 当前线程通过 data 传递标记——这是现有机制的保留
            // 调用方的 evaluateExpression 会在检测到函数名时自动触发回调
        }
    }

    // ════════════════════════════════════════════
    // 状态持久化
    // ════════════════════════════════════════════

    private void loadRuntimeState() {
        stateManager.loadFromFile();
        processData = stateManager.getProcessData();
        syncLifecycleFromManager();
        loadFromProcessDataInternal();
        expressionEvaluator.setData(data);
        controlFlow.setCode(codeLines, codeLoader.getBoundaryTable());
        controlFlow.setBlockStack(blockStack);
    }

    @SuppressWarnings("unchecked")
    private void loadFromProcessDataInternal() {
        ProcessIdentity.ensureDefaults(processData);
        String loadedGeneration = ProcessIdentity.generation(processData);
        if (ownedGeneration != null && !ownedGeneration.equals(loadedGeneration)) {
            throw new IllegalStateException("PID " + pid + " was replaced by another process generation");
        }
        this.processGeneration = loadedGeneration;
        Object user = processData.get("EffectiveUser");
        this.effectiveUser = user instanceof String ? user.toString() : stateManager.extractOwner();
        this.pathAliases = extractPathAliases(processData.get("PathAliases"));
        attemptManager.load(processData);
        StateManager.RuntimeSnapshot snap = stateManager.loadFromProcessData();
        this.data = snap.data;
        this.codeLines = snap.codeLines;
        this.currentLine = snap.currentLine;
        this.blockStack = snap.blockStack;
        // returnValue 已不再作为字段维护（直接通过 data.__return_value 传递）

        // 用 codeLoader 重新加载并扫描边界表，结果覆盖运行时副本
        codeLoader.load(codeLines);
        this.codeLines = codeLoader.getCodeLines();
        expressionEvaluator.setData(data);

        // 恢复函数定义
        if (snap.imports != null && !snap.imports.isEmpty()) {
            importManager.setImportedFiles(snap.imports);
        }
        importManager.setPackageDataByFunction(snap.packageDataByFunction);
        functionManager.setPackageDataByFunction(snap.packageDataByFunction);
        this.activePackageDataPath = snap.activePackageDataPath;
        functionManager.parseFunctions(codeLines);

        // 恢复调用栈
        functionManager.getCallStack().clear();
        List<Map<String, Object>> restoredFrames = new ArrayList<>(snap.callStackData);
        Collections.reverse(restoredFrames);
        for (Map<String, Object> frameData : restoredFrames) {
            @SuppressWarnings("unchecked")
            Map<String, Object> savedData = (Map<String, Object>) frameData.get("Data");
            @SuppressWarnings("unchecked")
            List<String> savedCode = (List<String>) frameData.get("Code");
            Object lineObj = frameData.get("CodeLine");
            int savedLine = lineObj instanceof Number ? ((Number) lineObj).intValue() : 0;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> savedBlocks = frameData.get("BlockStack") instanceof List
                    ? (List<Map<String, Object>>) frameData.get("BlockStack") : new ArrayList<>();
            String savedPackageData = frameData.get("PackageData") instanceof String path
                    ? path : null;
            if (savedData != null && savedCode != null) {
                functionManager.saveFrame(savedData, savedCode, savedLine, savedBlocks,
                        savedPackageData);
            }
        }
        if (!functionManager.getCallStack().isEmpty()) {
            FunctionManager.CallFrame rootFrame = functionManager.getCallStack().peekLast();
            if (rootFrame != null) functionManager.parseFunctions(rootFrame.savedCodeLines);
        }

        if (snap.pendingAssignVarName != null) {
            functionManager.setPendingFuncName(snap.pendingAssignVarName);
        }
    }

    /**
     * 函数调用返回后，将返回值写入之前记录的赋值目标变量。
     * {@code __pending_assign} 在进入函数体前由 handleAssignment 存入 data，
     * 随调用帧保存/恢复，因此函数返回后 data 中已包含该标记。
     */
    @SuppressWarnings("unchecked")
    private void completePendingAssignment() {
        Object varName = data.remove("__pending_assign");
        if (varName != null) {
            Object retVal = data.remove("__return_value");
            if (retVal != null) {
                data.put(varName.toString(), retVal);
            }
        }
    }

    private void persistState() {
        synchronized (persistenceLock) {
            if (killed) return;
            // 将 ProcessRunner 的运行时状态同步到 processData
            stateManager.setLifecycle(state, blockReason, exitReason, stateMessage);
            processData.put("ResumeState", resumeState.name());
            StateManager.RuntimeSnapshot snap = new StateManager.RuntimeSnapshot(
                    data,
                    codeLines,
                    currentLine,
                    blockStack,
                    serializeCallStack(),
                    functionManager.getPendingFuncName(),
                    importManager.getImportedFiles(),
                    importManager.getPackageDataByFunction(),
                    activePackageDataPath
            );
            stateManager.saveToFile(snap);
        }
    }

    /**
     * 原子提交：行号与当前数据状态同时生效、同时落盘。
     * 调用前必须确保所有数据变更已应用到 {@link #data}。
     * 断电恢复时，要么读到旧行号+旧数据（指令未执行），
     * 要么读到新行号+新数据（指令已完成），不会出现中间态。
     *
     * @param newLine 新的程序计数器行号
     */
    private void settle(int newLine) {
        this.currentLine = newLine;
        attemptManager.commit();
        persistState();
    }

    private List<Map<String, Object>> serializeCallStack() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (FunctionManager.CallFrame frame : functionManager.getCallStack()) {
            Map<String, Object> frameData = new LinkedHashMap<>();
            frameData.put("Data", new LinkedHashMap<>(frame.savedData));
            frameData.put("Code", new ArrayList<>(frame.savedCodeLines));
            frameData.put("CodeLine", frame.savedCurrentLine);
            frameData.put("BlockStack", new ArrayList<>(frame.savedBlockStack));
            if (frame.savedPackageDataPath != null) {
                frameData.put("PackageData", frame.savedPackageDataPath);
            }
            result.add(frameData);
        }
        return result;
    }

    private void reloadFromFile() {
        stateManager.loadFromFile();
        processData = stateManager.getProcessData();
        syncLifecycleFromManager();
        loadFromProcessDataInternal();
    }

    private String currentScriptPath() {
        Object path = processData != null ? processData.get("Path") : null;
        if (path instanceof String scriptPath && !scriptPath.isBlank()) {
            return scriptPath;
        }
        Object dataPath = data != null ? data.get("__current_script") : null;
        return dataPath instanceof String scriptPath && !scriptPath.isBlank() ? scriptPath : "/";
    }

    /**
     * 代码行变化后重新扫描边界表。
     */
    private void codeChanged() {
        codeLoader.load(codeLines);
        controlFlow.setCode(codeLoader.getCodeLines(), codeLoader.getBoundaryTable());
    }

    // ════════════════════════════════════════════
    // 异常处理
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void handleException(Exception e, String operation) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (data == null) {
            data = new LinkedHashMap<>();
        }
        String currentLineText = (currentLine >= 0 && codeLines != null && currentLine < codeLines.size())
                ? codeLines.get(currentLine) : null;
        ExceptionContext ctx = new ExceptionContext(pid, currentLine, null, currentLineText, operation);

        if (e instanceof ProcessException) {
            ProcessException pe = (ProcessException) e;
            ExceptionContext existingCtx = pe.getContext();
            if (existingCtx.getProcessId() <= 0) existingCtx.setProcessId(pid);
            if (existingCtx.getLineNumber() <= 0) existingCtx.setLineNumber(currentLine);
            if (existingCtx.getCurrentLine() == null) existingCtx.setCurrentLine(currentLineText);
            if (existingCtx.getOperation() == null) existingCtx.setOperation(operation);
        }

        Logger.error("Process " + pid + " error at line " + currentLine + " (" + operation + "): " + msg
                + " | context=" + ctx.toDetailedString());

        if (e instanceof EffectRecoveryRequiredException recovery) {
            Map<String, Object> recoveryInfo = new LinkedHashMap<>();
            recoveryInfo.put("EffectId", recovery.getEffectId());
            recoveryInfo.put("Message", msg);
            processData.put("_effectRecovery", recoveryInfo);
            blockReason = BlockReason.EFFECT_RECOVERY;
            transitionTo(ProcessState.BLOCKED, msg);
        } else if (e instanceof RetryableEffectException) {
            data.put("_warning", msg);
            transitionTo(ProcessState.READY, msg);
        } else if (e instanceof RecoverableException) {
            data.put("_warning", msg);
            attemptManager.abandon();
        } else if (e instanceof UnrecoverableException) {
            data.put("_error", msg);
            attemptManager.abandon();
            failProcess(msg);
        } else if (e instanceof RuntimeException) {
            data.put("_error", msg);
            attemptManager.abandon();
            failProcess(msg);
        } else {
            data.put("_warning", msg);
            attemptManager.abandon();
        }
        persistState();
    }

    private void blockProcess(BlockReason reason) {
        blockReason = reason != null ? reason : BlockReason.NONE;
        transitionTo(ProcessState.BLOCKED, blockReason.name());
    }

    private void unblockProcess() {
        blockReason = BlockReason.NONE;
        processData.remove("_blockingInfo");
        processData.remove("BlockTargetPid");
        transitionTo(ProcessState.READY, null);
        persistState();
    }

    private void terminateNormally(String message) {
        running = false;
        exitReason = ExitReason.NORMAL;
        blockReason = BlockReason.NONE;
        transitionTo(ProcessState.TERMINATED, message);
        prepareLifecycleCleanup(Constants.DELETE_PROCESS_FILE_ON_EXIT);
    }

    private void failProcess(String message) {
        running = false;
        exitReason = ExitReason.ERROR;
        blockReason = BlockReason.NONE;
        transitionTo(ProcessState.FAILED, message);
        prepareLifecycleCleanup(Constants.DELETE_PROCESS_FILE_ON_EXIT);
    }

    private void prepareLifecycleCleanup(boolean deleteAfterCleanup) {
        processData.put("LifecycleCleanup", lifecycleCleanupRecord(processData, deleteAfterCleanup));
        processData.remove("TerminationCleanup");
    }

    private static Map<String, Object> lifecycleCleanupRecord(Map<String, Object> process,
                                                               boolean deleteAfterCleanup) {
        Map<String, Object> cleanup = new LinkedHashMap<>();
        cleanup.put("Phase", "PENDING");
        cleanup.put("DeleteAfterCleanup", deleteAfterCleanup);
        cleanup.put("ProcessGeneration", process.get("ProcessGeneration"));
        return cleanup;
    }

    private void applyRequestedState(Object value) {
        ProcessState requested;
        try {
            requested = ProcessState.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            Logger.warn("Ignored invalid process state for PID " + pid + ": " + value);
            return;
        }
        if (requested == ProcessState.PAUSED) {
            if (state != ProcessState.PAUSED) {
                resumeState = state == ProcessState.BLOCKED ? ProcessState.BLOCKED : ProcessState.READY;
                transitionTo(ProcessState.PAUSED, "Paused externally");
            }
        } else if (requested == ProcessState.READY && state == ProcessState.PAUSED) {
            transitionTo(resumeState, resumeState == ProcessState.BLOCKED ? blockReason.name() : null);
        } else {
            transitionTo(requested, null);
        }
    }

    private void transitionTo(ProcessState next, String message) {
        if (state == next) {
            stateMessage = message;
            return;
        }
        if (!state.canTransitionTo(next)) {
            Logger.warn("Invalid process state transition for PID " + pid + ": " + state + " -> " + next);
            return;
        }
        boolean schedulingTransition = (state == ProcessState.READY && next == ProcessState.RUNNING)
                || (state == ProcessState.RUNNING && next == ProcessState.READY);
        if (!schedulingTransition) {
            Logger.info("Process " + pid + " state: " + state + " -> " + next);
        }
        state = next;
        stateMessage = message;
    }

    private void syncLifecycleFromManager() {
        state = stateManager.getState();
        blockReason = stateManager.getBlockReason();
        exitReason = stateManager.getExitReason();
        stateMessage = stateManager.getStateMessage();
        Object savedResume = processData.get("ResumeState");
        if (savedResume != null) {
            try {
                resumeState = ProcessState.valueOf(savedResume.toString());
            } catch (IllegalArgumentException ignored) {
                resumeState = ProcessState.READY;
            }
        }
        if (state.isTerminal()) running = false;
    }

    private boolean isChildFinished(int childPid, String expectedGeneration) {
        String path = com.follarce.util.PathUtil.findProcessFilePathByPid(childPid);
        if (path == null || !com.follarce.util.FileUtil.exists(path)) return true;
        if (expectedGeneration != null) {
            Object actualGeneration = com.follarce.util.JsonUtil.getField(path, "ProcessGeneration");
            if (!expectedGeneration.equals(actualGeneration)) return true;
        }
        Object childState = com.follarce.util.JsonUtil.getField(path, "ProcessState");
        Object legacyStatus = com.follarce.util.JsonUtil.getField(path, "Status");
        return ProcessState.restore(childState, legacyStatus).isTerminal();
    }

    private static void applyOfflineStateRequest(Map<String, Object> data, Object value) {
        ProcessState requested;
        try {
            requested = ProcessState.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return;
        }
        ProcessState current = ProcessState.restore(data.get("ProcessState"), data.get("Status"));
        if (current.isTerminal()) return;
        if (requested == ProcessState.PAUSED && current.canTransitionTo(ProcessState.PAUSED)) {
            data.put("ResumeState", current == ProcessState.BLOCKED
                    ? ProcessState.BLOCKED.name() : ProcessState.READY.name());
            data.put("ProcessState", ProcessState.PAUSED.name());
            data.put("StateMessage", "Paused externally");
        } else if (requested == ProcessState.READY && current == ProcessState.PAUSED) {
            ProcessState next = ProcessState.BLOCKED.name().equals(data.get("ResumeState"))
                    ? ProcessState.BLOCKED : ProcessState.READY;
            data.put("ProcessState", next.name());
            data.put("StateMessage", next == ProcessState.BLOCKED ? data.get("BlockReason") : null);
        }
    }

    @SuppressWarnings("unchecked")
    private void recordChildExitInMemory(String childPid, Object event) {
        String generation = event instanceof Map && ((Map<?, ?>) event).get("Generation") instanceof String
                ? ((Map<?, ?>) event).get("Generation").toString() : null;
        Object childrenObj = processData.get("Child");
        if (childrenObj instanceof Map) {
            Object currentChild = ((Map<?, ?>) childrenObj).get(childPid);
            if (generation != null && currentChild instanceof Map
                    && ((Map<?, ?>) currentChild).get("Generation") instanceof String
                    && !generation.equals(((Map<?, ?>) currentChild).get("Generation"))) return;
            ((Map<String, Object>) childrenObj).remove(childPid);
        }
        Map<String, Object> exited = (Map<String, Object>) processData.computeIfAbsent(
                "ExitedChildren", ignored -> new LinkedHashMap<>());
        if (isReaped(processData, childPid, generation)) return;
        exited.put(childPid, event);
    }

    @SuppressWarnings("unchecked")
    private boolean consumeMatchingExitEvent() {
        Object exitedObj = processData.get("ExitedChildren");
        if (!(exitedObj instanceof Map)) return false;
        Map<String, Object> exited = (Map<String, Object>) exitedObj;
        String childPid = null;
        if (blockReason == BlockReason.WAIT_PID) {
            Object target = processData.get("BlockTargetPid");
            if (target instanceof Number) childPid = String.valueOf(((Number) target).intValue());
        } else if (!exited.isEmpty()) {
            childPid = exited.keySet().iterator().next();
        }
        if (childPid == null || !exited.containsKey(childPid)) return false;
        Object event = exited.remove(childPid);
        String generation = event instanceof Map && ((Map<?, ?>) event).get("Generation") instanceof String
                ? ((Map<?, ?>) event).get("Generation").toString() : null;
        markReaped(processData, childPid, generation);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void recordChildExit(Map<String, Object> parentData, int childPid,
                                        String childGeneration, Object event) {
        Object childrenObj = parentData.get("Child");
        if (childrenObj instanceof Map) {
            Object childInfo = ((Map<String, Object>) childrenObj).get(String.valueOf(childPid));
            if (childGeneration != null && childInfo instanceof Map
                    && ((Map<?, ?>) childInfo).get("Generation") instanceof String
                    && !childGeneration.equals(((Map<?, ?>) childInfo).get("Generation"))) {
                return;
            }
            ((Map<String, Object>) childrenObj).remove(String.valueOf(childPid));
        }
        Map<String, Object> exited = (Map<String, Object>) parentData.computeIfAbsent(
                "ExitedChildren", ignored -> new LinkedHashMap<>());
        if (isReaped(parentData, String.valueOf(childPid), childGeneration)) return;
        exited.put(String.valueOf(childPid), event);
    }

    @SuppressWarnings("unchecked")
    private static boolean isReaped(Map<String, Object> process, String childPid, String generation) {
        Object reaped = process.get("ReapedChildren");
        return reaped instanceof Map
                && (((Map<String, Object>) reaped).containsKey(incarnationKey(childPid, generation))
                || (generation != null && ((Map<String, Object>) reaped).containsKey(childPid)));
    }

    @SuppressWarnings("unchecked")
    private static void markReaped(Map<String, Object> process, String childPid, String generation) {
        Map<String, Object> reaped = (Map<String, Object>) process.computeIfAbsent(
                "ReapedChildren", ignored -> new LinkedHashMap<String, Object>());
        reaped.put(incarnationKey(childPid, generation), true);
    }

    private static String incarnationKey(String pid, String generation) {
        return generation == null ? pid : pid + "@" + generation;
    }

    @SuppressWarnings("unchecked")
    private static void reparentSnapshotChildren(Map<String, Object> snapshot) {
        Object childrenObj = snapshot.get("Child");
        if (!(childrenObj instanceof Map)) return;
        Map<String, Object> children = new LinkedHashMap<>((Map<String, Object>) childrenObj);
        for (Map.Entry<String, Object> child : children.entrySet()) {
            try {
                reparentToInit(Integer.parseInt(child.getKey()), child.getValue());
            } catch (NumberFormatException ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static int extractParentPid(Map<String, Object> snapshot) {
        Object parentObj = snapshot.get("Parent");
        if (!(parentObj instanceof Map)) return 0;
        Object parentPid = ((Map<String, Object>) parentObj).get("PID");
        return parentPid instanceof Number ? ((Number) parentPid).intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static String extractParentGeneration(Map<String, Object> snapshot) {
        Object parentObj = snapshot.get("Parent");
        if (!(parentObj instanceof Map)) return null;
        Object generation = ((Map<String, Object>) parentObj).get("Generation");
        return generation instanceof String ? generation.toString() : null;
    }

    private static void finalizeTerminalSnapshot(Map<String, Object> snapshot, int targetPid) {
        reparentSnapshotChildren(snapshot);
        int parentPid = extractParentPid(snapshot);
        ExitReason reason = ExitReason.NONE;
        Object savedReason = snapshot.get("ExitReason");
        if (savedReason != null) {
            try {
                reason = ExitReason.valueOf(savedReason.toString());
            } catch (IllegalArgumentException ignored) {
            }
        }
        String childGeneration = snapshot.get("ProcessGeneration") instanceof String
                ? snapshot.get("ProcessGeneration").toString() : null;
        if (parentPid > 0) recordChildExit(parentPid, extractParentGeneration(snapshot),
                targetPid, childGeneration, reason);
    }

    private static boolean removeProcessFile(int targetPid, String expectedGeneration) {
        String path = com.follarce.util.PathUtil.findProcessFilePathByPid(targetPid);
        if (path == null || !com.follarce.util.FileUtil.exists(path)) return false;
        java.util.concurrent.locks.ReentrantLock lock = com.follarce.util.JsonUtil.lockFile(path);
        try {
            if (!com.follarce.util.FileUtil.exists(path)) return false;
            Map<String, Object> current = com.follarce.util.JsonUtil.parseToMapStrict(
                    com.follarce.util.FileUtil.read(path));
            if (!expectedGeneration.equals(current.get("ProcessGeneration"))) return false;
            com.follarce.util.FileUtil.removeFile(path);
        } finally {
            lock.unlock();
        }
        ProcessInbox.removeIncarnation(targetPid, expectedGeneration);
        return true;
    }

    private static void applyOfflineInbox(int targetPid, String generation) {
        String processPath = Constants.SYSTEM_PROCESS_PATH + targetPid + ".proc";
        for (ProcessMessage message : ProcessInbox.list(targetPid, generation)) {
            final boolean[] terminate = {false};
            com.follarce.util.JsonUtil.updateFile(processPath, data -> {
                if (!generation.equals(data.get("ProcessGeneration"))) return;
                if (ProcessInbox.isApplied(data, message.messageId())) return;
                if ("ProcessState".equals(message.field())) {
                    applyOfflineStateRequest(data, message.value());
                } else if ("Status".equals(message.field())) {
                    applyOfflineStateRequest(data, Boolean.FALSE.equals(message.value())
                            ? ProcessState.PAUSED.name() : ProcessState.READY.name());
                } else if ("__Terminate".equals(message.field())) {
                    ProcessState current = ProcessState.restore(data.get("ProcessState"), data.get("Status"));
                    if (!current.isTerminal()) {
                        data.put("ProcessState", ProcessState.TERMINATED.name());
                        data.put("Status", false);
                        data.put("ExitReason", ExitReason.KILLED.name());
                        data.put("BlockReason", null);
                        data.put("StateMessage", "Killed");
                    }
                    Map<String, Object> cleanup = lifecycleCleanupRecord(data, true);
                    cleanup.put("RequestId", message.messageId());
                    data.put("LifecycleCleanup", cleanup);
                    terminate[0] = true;
                } else {
                    setNestedField(data, message.field(), message.value());
                }
                ProcessInbox.recordApplied(data, message);
            });
            ProcessInbox.acknowledge(message);
            if (terminate[0]) reconcileLifecycle(targetPid, generation);
        }
    }

    public static void recoverInbox(int targetPid, String generation) {
        applyOfflineInbox(targetPid, generation);
    }

    public static void reconcileTermination(int targetPid) {
        reconcileLifecycle(targetPid);
    }

    public static void reconcileLifecycle(int targetPid) {
        String path = Constants.SYSTEM_PROCESS_PATH + targetPid + ".proc";
        if (!com.follarce.util.FileUtil.exists(path)) return;
        Map<String, Object> snapshot = com.follarce.util.JsonUtil.parseToMapStrict(
                com.follarce.util.FileUtil.read(path));
        ProcessIdentity.ensureDefaults(snapshot);
        reconcileLifecycle(targetPid, ProcessIdentity.generation(snapshot));
    }

    @SuppressWarnings("unchecked")
    private static void reconcileLifecycle(int targetPid, String expectedGeneration) {
        String gateKey = targetPid + "@" + expectedGeneration;
        Object gate = LIFECYCLE_CLEANUP_GATES.computeIfAbsent(gateKey, ignored -> new Object());
        try {
            synchronized (gate) {
                String path = Constants.SYSTEM_PROCESS_PATH + targetPid + ".proc";
                if (!com.follarce.util.FileUtil.exists(path)) return;
                Map<String, Object> snapshot = com.follarce.util.JsonUtil.parseToMapStrict(
                        com.follarce.util.FileUtil.read(path));
                if (!expectedGeneration.equals(snapshot.get("ProcessGeneration"))) return;
                Object cleanupObject = snapshot.get("LifecycleCleanup");
                if (!(cleanupObject instanceof Map)) cleanupObject = snapshot.get("TerminationCleanup");
                if (!(cleanupObject instanceof Map)
                        || !ProcessState.restore(snapshot.get("ProcessState"), snapshot.get("Status")).isTerminal()) {
                    return;
                }
                Map<String, Object> cleanup = (Map<String, Object>) cleanupObject;
                boolean delete = Boolean.TRUE.equals(cleanup.get("DeleteAfterCleanup"))
                        || snapshot.get("TerminationCleanup") instanceof Map;
                finalizeTerminalSnapshot(snapshot, targetPid);
                if (delete) {
                    if (removeProcessFile(targetPid, expectedGeneration)) {
                        ProcessRunner runner = RUNNERS.get(targetPid);
                        if (runner != null && expectedGeneration.equals(runner.processGeneration)) {
                            runner.running = false;
                            RUNNERS.remove(targetPid, runner);
                            runner.registered = false;
                        }
                    }
                } else {
                    com.follarce.util.JsonUtil.updateFile(path, data -> {
                        if (!expectedGeneration.equals(data.get("ProcessGeneration"))) return;
                        data.remove("LifecycleCleanup");
                        data.remove("TerminationCleanup");
                    });
                }
                unparkProcess(targetPid);
            }
        } finally {
            LIFECYCLE_CLEANUP_GATES.remove(gateKey, gate);
        }
    }

    /** Applies an explicit operator decision to the newest in-doubt effect. */
    public static boolean resolveEffect(int targetPid, String decision, Object suppliedResult) {
        return resolveEffect(targetPid, null, decision, suppliedResult);
    }

    public static boolean resolveEffect(int targetPid, String expectedEffectId,
                                        String decision, Object suppliedResult) {
        String path = Constants.SYSTEM_PROCESS_PATH + targetPid + ".proc";
        if (!com.follarce.util.FileUtil.exists(path)) return false;
        final boolean[] resolved = {false};
        com.follarce.util.JsonUtil.updateFile(path,
                data -> resolved[0] = StatementAttemptManager.resolve(
                        data, expectedEffectId, decision, suppliedResult));
        if (!resolved[0]) return false;

        ProcessRunner target = RUNNERS.get(targetPid);
        if (target != null) {
            synchronized (target.persistenceLock) {
                target.reloadFromFile();
            }
        }
        unparkProcess(targetPid);
        if ("fail".equalsIgnoreCase(decision)) reconcileLifecycle(targetPid);
        return true;
    }

    private static void updateProcessData(int targetPid, Consumer<Map<String, Object>> updater) {
        updateProcessData(targetPid, null, updater, false);
    }

    private static boolean updateProcessData(int targetPid, Consumer<Map<String, Object>> updater,
                                             boolean requireActive) {
        return updateProcessData(targetPid, null, updater, requireActive);
    }

    private static boolean updateProcessData(int targetPid, String expectedGeneration,
                                             Consumer<Map<String, Object>> updater,
                                             boolean requireActive) {
        ProcessRunner target = RUNNERS.get(targetPid);
        if (target != null) {
            synchronized (target.persistenceLock) {
                if (expectedGeneration != null && !expectedGeneration.equals(target.processGeneration)) return false;
                if (requireActive && target.state.isTerminal()) return false;
                updater.accept(target.processData);
                target.persistState();
                return true;
            }
        }

        String path = Constants.SYSTEM_PROCESS_PATH + targetPid + ".proc";
        if (!com.follarce.util.FileUtil.exists(path)) return false;
        boolean[] updated = {false};
        com.follarce.util.JsonUtil.updateFile(path, data -> {
            if (expectedGeneration != null && !expectedGeneration.equals(data.get("ProcessGeneration"))) return;
            ProcessState saved = ProcessState.restore(data.get("ProcessState"), data.get("Status"));
            if (requireActive && saved.isTerminal()) return;
            updater.accept(data);
            updated[0] = true;
        });
        return updated[0];
    }

    private static String readProcessGeneration(int targetPid) {
        String path = Constants.SYSTEM_PROCESS_PATH + targetPid + ".proc";
        if (!com.follarce.util.FileUtil.exists(path)) return null;
        try {
            Object generation = com.follarce.util.JsonUtil.getField(path, "ProcessGeneration");
            return generation instanceof String ? generation.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void setNestedField(Map<String, Object> data, String field, Object value) {
        String[] parts = field.split("\\.");
        Map<String, Object> current = data;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        if (value == null) current.remove(parts[parts.length - 1]);
        else current.put(parts[parts.length - 1], value);
    }

    // ════════════════════════════════════════════
    // 辅助方法
    // ════════════════════════════════════════════

    private int skipFunctionBody(int startLine) {
        int depth = 0;
        String line;
        for (int i = startLine; i < codeLines.size(); i++) {
            line = codeLines.get(i);
            int[] counts = ControlFlow.countBraces(line);
            depth += counts[0] - counts[1];
            if (depth <= 0) return i + 1;
        }
        return codeLines.size();
    }

    /**
     * 从条件行提取条件表达式（与 BoundaryTable 中的 extractCondition 逻辑一致）。
     */
    private static String extractCondition(String line, String keyword) {
        String after = line.substring(keyword.length()).trim();
        if (after.startsWith("(")) after = after.substring(1);
        int braceIdx = after.indexOf('{');
        if (braceIdx >= 0) after = after.substring(0, braceIdx).trim();
        int parenIdx = after.lastIndexOf(')');
        if (parenIdx >= 0 && parenIdx == after.length() - 1) after = after.substring(0, parenIdx).trim();
        return after.trim();
    }

    private static int toIntIndex(Object index, Map<String, Object> data) {
        int result;
        if (index instanceof Number) {
            result = ((Number) index).intValue();
        } else {
            result = 0;
        }
        return result >= 0 ? result : 0;
    }

    private FunctionContext createFunctionContext() {
        return new FunctionContext(pid, stateManager.extractParentPid(), effectiveUser,
                processGeneration, pathAliases, this::setEffectiveUser,
                this::setPathAliases, this::executeFunctionEffect, activePackageDataPath);
    }

    private Object executeFunctionEffect(String operation, EffectPolicy policy, List<Object> arguments,
                                         java.util.function.Function<FunctionContext, Object> invocation) {
        return attemptManager.invoke(operation, policy, arguments, effect ->
                invocation.apply(createFunctionContext().forEffect(effect.effectId(), effect.replay())));
    }

    private Object invokeEngineEffect(String operation, EffectPolicy policy, List<Object> arguments,
                                      java.util.function.Function<StatementAttemptManager.Invocation, Object> action) {
        return attemptManager.invoke(operation, policy, arguments, action);
    }

    private Object invokeControlEffect(
            String operation,
            String targetText,
            java.util.function.BiFunction<IpcHandler.ControlTarget, String, Boolean> action) {
        IpcHandler.ControlTarget target = ipcHandler.resolveControlTarget(targetText);
        if (target == null) return false;
        return invokeEngineEffect(operation, EffectPolicy.LOCAL_TRANSACTIONAL,
                List.of(target.pid(), target.generation()),
                invocation -> {
                    try {
                        return action.apply(target, invocation.effectId());
                    } catch (RuntimeException e) {
                        throw new RetryableEffectException(
                                "Durable control publication failed: " + operation, e);
                    }
                });
    }

    private Object invokeFork(String resultVariable, StatementAttemptManager.Invocation invocation) {
        try {
            return ipcHandler.handleFork(resultVariable, invocation.effectId());
        } catch (com.follarce.function.UnknownEffectOutcomeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RetryableEffectException("Fork publication failed", e);
        }
    }

    private void setEffectiveUser(String username) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Effective user is required");
        effectiveUser = username;
        processData.put("EffectiveUser", username);
        UserUtil.setCurrentUser(username);
    }

    private void setPathAliases(Map<String, String> aliases) {
        pathAliases = aliases == null ? new LinkedHashMap<>() : new LinkedHashMap<>(aliases);
        processData.put("PathAliases", new LinkedHashMap<>(pathAliases));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> extractPathAliases(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!(value instanceof Map)) return result;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                result.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return result;
    }
}
