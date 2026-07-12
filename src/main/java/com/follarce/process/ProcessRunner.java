package com.follarce.process;

import com.follarce.Constants;
import com.follarce.exception.ExceptionContext;
import com.follarce.exception.ProcessException;
import com.follarce.exception.RecoverableException;
import com.follarce.exception.UnrecoverableException;
import com.follarce.function.FunctionRegistry;
import com.follarce.log.Logger;
import com.follarce.script.FunctionDef;
import com.follarce.util.UserUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

import static com.follarce.Constants.USE_VIRTUAL_THREADS;

/**
 * 进程执行引擎 —— 由调度器驱动，每次 step() 执行一行 FCL 代码。
 * <p>
 * 状态机：NEW → READY → RUNNING → (COMPLETED → READY | BLOCKED | TERMINATED)
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

    public enum ProcessState {
        NEW, READY, RUNNING, BLOCKED, TERMINATED
    }

    public enum BlockReason {
        NONE, WAIT_ANY, WAIT_PID
    }

    /**
     * 通用进程间消息 —— 字段名 + 新值。
     * 任何 Java 层组件想修改某个进程的某字段，必须通过
     * {@link #postMessage} 发送消息，由该进程的 ProcessRunner
     * 在 executeLine() 开头统一处理，保证字段和行号同时落盘。
     */
    public record ProcessMessage(String field, Object value) {}

    // ════════════════════════════════════════════
    // 全局注册表 + 消息队列
    // ════════════════════════════════════════════

    /** PID → ProcessRunner 映射，供 postMessage 查找目标进程 */
    private static final ConcurrentHashMap<Integer, ProcessRunner> RUNNERS = new ConcurrentHashMap<>();

    /** 待处理的外部消息队列（线程安全） */
    private final java.util.concurrent.ConcurrentLinkedQueue<ProcessMessage> pendingMessages =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

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

    // ════════════════════════════════════════════
    // ProcessRunner 级别状态
    // ════════════════════════════════════════════

    private volatile boolean running = true;
    private final int pid;
    private ProcessState state = ProcessState.NEW;
    private int priority = Constants.DEFAULT_PRIORITY;
    private BlockReason blockReason = BlockReason.NONE;
    private final long processStartMs;

    // 进入用户函数体后置为 true，dispatchStatement 据此跳过 currentLine++
    private volatile boolean enteredUserFunction = false;

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

    // ════════════════════════════════════════════
    // 构造
    // ════════════════════════════════════════════

    public ProcessRunner(int pid, Map<String, Object> processData) {
        this.pid = pid;
        this.processData = processData;
        this.processStartMs = System.currentTimeMillis();

        // 创建组件 —— ProcessRunner 负责构造和生命周期
        this.stateManager = new StateManager(pid, processStartMs, processData);
        this.codeLoader = new CodeLoader();
        this.expressionEvaluator = new ExpressionEvaluator(pid, this::onFunctionArgCallback);
        this.controlFlow = new ControlFlow(expressionEvaluator);
        this.importManager = new ImportManager();
        this.functionManager = new FunctionManager(pid, expressionEvaluator);
        this.ipcHandler = new IpcHandler(
                pid,
                this::saveToFile,
                this::reloadFromFile,
                () -> currentLine,
                () -> processData,
                () -> data,
                () -> state.ordinal(),
                (s) -> { state = ProcessState.values()[s]; },
                codeLoader,
                stateManager
        );

        // 从进程数据加载初始状态
        loadFromProcessDataInternal();

        // 注册到全局表，供 postMessage 查找
        RUNNERS.put(pid, this);
    }

    // ════════════════════════════════════════════
    // 公共 API（供 Scheduler 调用）
    // ════════════════════════════════════════════

    public void init() {
        String initialOwner = stateManager.extractOwner();
        UserUtil.setCurrentUser(initialOwner != null ? initialOwner : Constants.DEFAULT_USER_LOCAL);
        priority = stateManager.extractPriority();
        // 解析函数定义
        functionManager.parseFunctions(codeLines);
        state = ProcessState.READY;
        Logger.info("Process " + pid + " (" + getProcessName() + ") initialized, priority=" + priority);
    }

    public StepResult step() {
        if (state == ProcessState.TERMINATED) return StepResult.TERMINATED;
        if (state == ProcessState.BLOCKED) return StepResult.BLOCKED;

        state = ProcessState.RUNNING;
        try {
            executeLine();
            if (state == ProcessState.BLOCKED) return StepResult.BLOCKED;
            if (!running) {
                state = ProcessState.TERMINATED;
                stateManager.cleanup();
                return StepResult.TERMINATED;
            }
            state = ProcessState.READY;
            return StepResult.COMPLETED;
        } catch (Exception e) {
            handleException(e, "step");
            state = ProcessState.TERMINATED;
            return StepResult.TERMINATED;
        }
    }

    public boolean checkWakeup() {
        if (state != ProcessState.BLOCKED) return true;
        if (blockReason == BlockReason.NONE) {
            state = ProcessState.READY;
            return true;
        }
        // 从文件加载以获取最新子进程状态
        reloadFromFile();
        if (!running) {
            state = ProcessState.TERMINATED;
            return false;
        }
        // 没有子进程 → 不阻塞
        @SuppressWarnings("unchecked")
        Map<String, Object> children = (Map<String, Object>) processData.get("Child");
        if (children == null || children.isEmpty()) {
            state = ProcessState.READY;
            blockReason = BlockReason.NONE;
            Logger.info("Process " + pid + " woken from wait (no children)");
            return true;
        }
        // 检查子进程文件是否存在
        for (String pidStr : children.keySet()) {
            try {
                int childPid = Integer.parseInt(pidStr);
                if (!com.follarce.util.FileUtil.exists(
                        com.follarce.util.PathUtil.findProcessFilePathByPid(childPid))) {
                    state = ProcessState.READY;
                    blockReason = BlockReason.NONE;
                    Logger.info("Process " + pid + " woken from wait (child " + childPid + " terminated)");
                    return true;
                }
            } catch (NumberFormatException ignored) {}
        }
        return false; // 仍在等待
    }

    public void stopProcess() {
        running = false;
        state = ProcessState.TERMINATED;
        stateManager.setRunning(false);
        RUNNERS.remove(pid);
        // 清理本进程的函数定义，防止跨进程残留
        com.follarce.function.FunctionRegistry.clearUserFunctions(pid);
    }

    // ── 访问器 ──

    public int getPid() { return pid; }
    public int getPriority() { return priority; }
    public ProcessState getState() { return state; }
    public String getProcessName() { return stateManager.extractName(); }
    public boolean isRunning() { return running && state != ProcessState.TERMINATED; }

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
            String owner = stateManager.extractOwner();
            UserUtil.setCurrentUser(owner != null ? owner : Constants.DEFAULT_USER_LOCAL);

            while (running && state != ProcessState.TERMINATED) {
                StepResult result = step();

                if (result == StepResult.TERMINATED) {
                    Logger.info("Virtual thread: PID " + pid + " terminated naturally");
                    VIRTUAL_THREADS.remove(pid);
                    return;
                }

                if (result == StepResult.BLOCKED) {
                    parkWhileBlocked();
                    continue;
                }

                // 让出 CPU 给其他虚拟线程
                if (running && state != ProcessState.TERMINATED && state != ProcessState.BLOCKED) {
                    Thread.yield();
                }
            }
        } catch (Exception e) {
            Logger.error("Virtual thread for PID " + pid + " crashed: " + e.getMessage());
        } finally {
            VIRTUAL_THREADS.remove(pid);
            RUNNERS.remove(pid);
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
        while (state == ProcessState.BLOCKED && running) {
            // 检查是否可唤醒
            if (checkWakeup()) {
                state = ProcessState.READY;
                blockReason = BlockReason.NONE;
                Logger.info("Process " + pid + " woken from wait (virtual thread)");
                return;
            }
            // park 50ms —— 不占平台线程
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
        // 主路径：目标进程在运行中 → 发消息由目标进程自行落盘
        // 每个进程文件只有其自身的 ProcessRunner 写入，保证单一写入者原则
        ProcessRunner target = RUNNERS.get(targetPid);
        if (target != null) {
            target.pendingMessages.offer(new ProcessMessage(field, value));
            unparkProcess(targetPid);
            return;
        }

        // 兜底：目标进程已不在运行，直接原子写入 .proc 文件
        // 此时不可能有并发写入者，但使用 JsonUtil.setField 确保写入完整
        String procPath = com.follarce.Constants.SYSTEM_PROCESS_PATH + targetPid + ".proc";
        if (com.follarce.util.FileUtil.exists(procPath)) {
            com.follarce.util.JsonUtil.setField(procPath, field, value);
        }
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
     * 副作用（仅对顶层字段生效）：Status=true → READY，Status=false → BLOCKED/TERMINATED。
     *
     * @param field 字段路径，点号分隔
     * @param value 新值
     */
    @SuppressWarnings("unchecked")
    private void applyFieldUpdate(String field, Object value) {
        String[] parts = field.split("\\.");
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
        current.put(leafKey, value);

        // 副作用：仅对顶层字段做状态同步
        if (parts.length == 1) {
            if ("Status".equals(leafKey)) {
                boolean isAlive = value instanceof Boolean && (Boolean) value;
                if (isAlive) {
                    // Status=true：从阻塞恢复
                    if (state == ProcessState.BLOCKED) {
                        state = ProcessState.READY;
                    }
                } else {
                    // Status=false：阻塞（暂停）
                    state = ProcessState.BLOCKED;
                }
            }
        }
    }

    /**
     * 终止指定进程 —— 停止 ProcessRunner 并删除 .proc 文件。
     * <p>
     * kill 操作始终删除进程文件，不受 {@link Constants#DELETE_PROCESS_FILE_ON_EXIT} 影响。
     * 与 pause 不同：pause 只设 Status=false 让进程阻塞，kill 是彻底终止。
     */
    public static void terminateProcess(int targetPid) {
        ProcessRunner target = RUNNERS.get(targetPid);
        if (target != null) {
            target.running = false;
            target.state = ProcessState.TERMINATED;
            target.stateManager.setRunning(false);
        }
        // kill 始终删除 .proc 文件
        String path = com.follarce.util.PathUtil.findProcessFilePathByPid(targetPid);
        if (path != null && com.follarce.util.FileUtil.exists(path)) {
            com.follarce.util.FileUtil.removeFile(path);
            Logger.info("Terminated process PID " + targetPid + ", file removed");
        }
    }

    /**
     * 处理所有待处理的外部消息 —— 在 executeLine() 开头调用，
     * 确保任何外部请求在当前行执行前落地。
     */
    private void processPendingMessages() {
        ProcessMessage msg;
        while ((msg = pendingMessages.poll()) != null) {
            applyFieldUpdate(msg.field, msg.value);
        }
    }

    // ════════════════════════════════════════════
    // 主执行逻辑
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void executeLine() {
        try {
            // 0. 处理所有待处理的外部消息（必须在任何 FCL 代码执行前处理）
            processPendingMessages();
            if (!running) {
                persistState();
                return;
            }

            // 1. 从文件加载最新状态
            loadRuntimeState();

            // 2. 检查是否执行完毕
            if (currentLine >= codeLines.size()) {
                // 如果在函数调用中，自动返回到调用者
                if (functionManager.isInCall()) {
                    FunctionManager.CallFrame frame = functionManager.popFrame();
                    this.data = frame.savedData;
                    this.codeLines = frame.savedCodeLines;
                    this.blockStack = new ArrayList<>();
                    completePendingAssignment();
                    codeChanged();
                    commitAndPersist(frame.savedCurrentLine + 1);
                    return;
                }
                running = false;
                stateManager.setRunning(false);
                persistState();
                stateManager.cleanup();
                return;
            }

            String line = codeLines.get(currentLine);

            // 3. 跳过空行和残留注释（兼容旧 .proc 文件中未剔除的注释行）
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("//") || trimmedLine.startsWith("#")) {
                commitAndPersist(currentLine + 1);
                return;
            }

            // 4. 处理花括号行
            if (line.trim().startsWith("}")) {
                int[] counts = ControlFlow.countBraces(line);
                commitAndPersist(controlFlow.handleClosingBraces(counts[1], currentLine));
                return;
            }
            if (line.trim().equals("{")) {
                commitAndPersist(currentLine + 1);
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
        String trimmed = line.trim();

        // func 定义
        if (trimmed.startsWith("func ")) {
            commitAndPersist(skipFunctionBody(currentLine));
            return;
        }

        // import
        if (trimmed.startsWith("import ")) {
            List<String> imported = importManager.handleImport(trimmed, codeLines);
            for (String imp : imported) {
                importManager.addImportedFile(imp);
            }
            codeChanged();
            functionManager.parseFunctions(codeLines);
            commitAndPersist(currentLine + 1);
            return;
        }

        // include
        if (trimmed.startsWith("include ")) {
            int newLine = importManager.handleInclude(trimmed, codeLines, currentLine);
            codeChanged();
            commitAndPersist(newLine);
            return;
        }

        // if
        if (trimmed.startsWith("if ") || trimmed.startsWith("if(")) {
            String condition = extractCondition(trimmed, "if");
            commitAndPersist(controlFlow.handleIf(condition, currentLine));
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
            commitAndPersist(newLine);
            return;
        }

        // while
        if (trimmed.startsWith("while ") || trimmed.startsWith("while(")) {
            String condition = extractCondition(trimmed, "while");
            commitAndPersist(controlFlow.handleWhile(condition, currentLine));
            return;
        }

        // switch
        if (trimmed.startsWith("switch ") || trimmed.startsWith("switch(")) {
            String expr = extractCondition(trimmed, "switch");
            commitAndPersist(controlFlow.handleSwitch(expr, currentLine));
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
                    commitAndPersist(el + 1);
                    return;
                }
            }
            commitAndPersist(currentLine + 1);
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
                    commitAndPersist(el + 1);
                    return;
                }
            }
            commitAndPersist(currentLine + 1);
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
                    // 数据变更必须在 commitAndPersist 之前完成
                    if (ret.value != null) {
                        data.put("__return_value", ret.value);
                    }
                    completePendingAssignment();
                    codeChanged();
                    commitAndPersist(frame.savedCurrentLine + 1);
                }
            } else {
                running = false;
                stateManager.setRunning(false);
                commitAndPersist(codeLines.size());
            }
            return;
        }

        // break
        if (trimmed.equals("break")) {
            commitAndPersist(controlFlow.handleBreak(currentLine));
            return;
        }

        // continue
        if (trimmed.equals("continue")) {
            commitAndPersist(controlFlow.handleContinue(currentLine));
            return;
        }

        // fork()
        if (trimmed.matches("^\\s*fork\\s*\\(\\s*\\)\\s*$")) {
            int childPid = ipcHandler.handleFork();
            commitAndPersist(currentLine + 1);
            return;
        }

        // exec(...)
        if (trimmed.startsWith("exec(") || trimmed.startsWith("exec (")) {
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
            commitAndPersist(currentLine + 1);
            return;
        }

        // 普通赋值 x = expr
        java.util.regex.Matcher assignMatcher = ExpressionEvaluator.ASSIGN_PATTERN.matcher(trimmed);
        if (assignMatcher.matches()) {
            // 数据变更在 handleAssignment 内完成，然后行号+数据一起落盘
            handleAssignment(assignMatcher, line);
            if (enteredUserFunction) { enteredUserFunction = false; persistState(); return; }
            commitAndPersist(currentLine + 1);
            return;
        }

        // 通用表达式（函数调用、字面量等）
        Object exprResult = expressionEvaluator.evaluateExpression(trimmed);
        if (exprResult instanceof String) {
            String marker = (String) exprResult;
            handleMarker(marker);
            if (state == ProcessState.BLOCKED) return;
        }
        if (enteredUserFunction) { enteredUserFunction = false; persistState(); return; }
        commitAndPersist(currentLine + 1);
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

        data.put(varName, value);
    }

    @SuppressWarnings("unchecked")
    private void handleIndexAssignment(java.util.regex.Matcher matcher, String rawLine) {
        String varName = matcher.group(1).trim();
        String indexExpr = matcher.group(2).trim();
        String valueExpr = matcher.group(3).trim();

        Object index = expressionEvaluator.evaluateExpression(indexExpr);
        Object value = expressionEvaluator.evaluateExpression(valueExpr);

        Object target = data.get(varName);
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
        }
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
            // 这种标记不应在表达式上下文中触发
            return;
        }
        if (marker.startsWith("KILL:")) {
            ipcHandler.handleKill(marker.substring(5));
        } else if (marker.startsWith("WAITPID:")) {
            ipcHandler.handleWaitPid(marker.substring(8));
        } else if (marker.startsWith("PAUSE:")) {
            ipcHandler.handlePause(marker.substring(6));
        } else if (marker.startsWith("CONTINUE:")) {
            ipcHandler.handleContinue(marker.substring(9));
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
            return ipcHandler.handleFork();
        } else if (marker.startsWith("KILL:")) {
            ipcHandler.handleKill(marker.substring(5));
            return true;
        } else if (marker.equals("WAIT")) {
            ipcHandler.handleWait();
            return true; // 不阻塞
        } else if (marker.startsWith("WAITPID:")) {
            ipcHandler.handleWaitPid(marker.substring(8));
            return true;
        } else if (marker.startsWith("PAUSE:")) {
            ipcHandler.handlePause(marker.substring(6));
            return true;
        } else if (marker.startsWith("CONTINUE:")) {
            ipcHandler.handleContinue(marker.substring(9));
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
                new LinkedHashMap<>(data), new ArrayList<>(codeLines), currentLine);
        this.data = new LinkedHashMap<>();
        this.codeLines = new ArrayList<>(def.bodyLines);
        this.currentLine = 0;
        this.blockStack = new ArrayList<>();

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
        running = stateManager.isRunning();
        loadFromProcessDataInternal();
        expressionEvaluator.setData(data);
        controlFlow.setCode(codeLines, codeLoader.getBoundaryTable());
        controlFlow.setBlockStack(blockStack);
    }

    @SuppressWarnings("unchecked")
    private void loadFromProcessDataInternal() {
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
        functionManager.parseFunctions(codeLines);

        // 恢复调用栈
        functionManager.getCallStack().clear();
        for (Map<String, Object> frameData : snap.callStackData) {
            @SuppressWarnings("unchecked")
            Map<String, Object> savedData = (Map<String, Object>) frameData.get("Data");
            @SuppressWarnings("unchecked")
            List<String> savedCode = (List<String>) frameData.get("Code");
            Object lineObj = frameData.get("CodeLine");
            int savedLine = lineObj instanceof Number ? ((Number) lineObj).intValue() : 0;
            if (savedData != null && savedCode != null) {
                functionManager.saveFrame(savedData, savedCode, savedLine);
            }
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
        // 将 ProcessRunner 的运行时状态同步到 processData
        StateManager.RuntimeSnapshot snap = new StateManager.RuntimeSnapshot(
                data,
                codeLines,
                currentLine,
                blockStack,
                serializeCallStack(),
                functionManager.getPendingFuncName(),
                importManager.getImportedFiles()
        );
        stateManager.saveToFile(snap);
    }

    /**
     * 原子提交：行号与当前数据状态同时生效、同时落盘。
     * 调用前必须确保所有数据变更已应用到 {@link #data}。
     * 断电恢复时，要么读到旧行号+旧数据（指令未执行），
     * 要么读到新行号+新数据（指令已完成），不会出现中间态。
     *
     * @param newLine 新的程序计数器行号
     */
    private void commitAndPersist(int newLine) {
        this.currentLine = newLine;
        persistState();
    }

    private List<Map<String, Object>> serializeCallStack() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (FunctionManager.CallFrame frame : functionManager.getCallStack()) {
            Map<String, Object> frameData = new LinkedHashMap<>();
            frameData.put("Data", new LinkedHashMap<>(frame.savedData));
            frameData.put("Code", new ArrayList<>(frame.savedCodeLines));
            frameData.put("CodeLine", frame.savedCurrentLine);
            result.add(frameData);
        }
        return result;
    }

    private void reloadFromFile() {
        stateManager.loadFromFile();
        processData = stateManager.getProcessData();
        running = stateManager.isRunning();
        loadFromProcessDataInternal();
    }

    private void saveToFile() {
        persistState();
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

        if (e instanceof RecoverableException) {
            data.put("_warning", msg);
        } else if (e instanceof UnrecoverableException) {
            data.put("_error", msg);
            running = false;
        } else if (e instanceof RuntimeException) {
            data.put("_error", msg);
            running = false;
        } else {
            data.put("_warning", msg);
        }
        persistState();
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
}
