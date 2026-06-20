package com.follarce.process;

import com.follarce.Constants;
import com.follarce.log.Logger;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;

import java.io.File;
import java.util.*;

import static com.follarce.Constants.USE_VIRTUAL_THREADS;

/**
 * 进程调度器 —— 基于优先级的轮转调度。
 * <p>
 * 调度策略：
 * <ol>
 *   <li>三级优先级队列：HIGH → NORMAL → LOW，高优先级始终优先</li>
 *   <li>同优先级内轮转（Round-Robin），每个进程每次获得 QUANTUM 行执行机会</li>
 *   <li>阻塞进程移入阻塞队列，每 tick 检查唤醒条件</li>
 *   <li>每轮调度结束后休眠 SCHEDULER_TICK_MS</li>
 * </ol>
 * <p>
 * 状态转换：
 * <pre>
 * NEW → init() → READY (入队) → step() → RUNNING → COMPLETED → READY (回队尾)
 *                                                     → BLOCKED → 阻塞队列 → checkWakeup() → READY
 *                                                     → TERMINATED → cleanup
 * </pre>
 */
public class Scheduler extends Thread {

    // 三级优先级队列
    private final Queue<ProcessRunner> highPriorityQueue = new LinkedList<>();
    private final Queue<ProcessRunner> normalPriorityQueue = new LinkedList<>();
    private final Queue<ProcessRunner> lowPriorityQueue = new LinkedList<>();

    // 阻塞队列（等待 wait/waitPid 条件满足）
    private final Map<Integer, ProcessRunner> blockedProcesses = new LinkedHashMap<>();

    // 所有已知进程（用于快速查找）
    private final Map<Integer, ProcessRunner> allProcesses = new LinkedHashMap<>();

    private volatile boolean running = true;

    public Scheduler() {
        super("ProcessScheduler");
        setDaemon(false); // 非守护线程，确保 INIT 完成后 JVM 不退出
    }

    // ════════════════════════════════════════════
    // 公共 API
    // ════════════════════════════════════════════

    /**
     * 添加一个进程到调度器（已调用了 init() 的进程）。
     */
    public void addProcess(ProcessRunner runner) {
        allProcesses.put(runner.getPid(), runner);
        enqueueByPriority(runner);
        Logger.info("Scheduler: PID " + runner.getPid() + " (" + runner.getProcessName()
                + ") added to ready queue (priority=" + runner.getPriority() + ")");

        // 虚拟线程模式：为每个添加的进程启动虚拟线程
        if (USE_VIRTUAL_THREADS) {
            Thread vt = Thread.ofVirtual()
                    .name("vt-process-" + runner.getPid())
                    .start(runner::virtualThreadRun);
            Logger.info("Virtual thread started for PID " + runner.getPid());
        }
    }

    /**
     * 获取指定 PID 的进程运行器。
     */
    public static ProcessRunner getRunner(int pid) {
        // 通过实例方法访问，为保持向后兼容提供静态包装
        // 实际调用方应持有 Scheduler 引用
        return null; // 由实例方法 getProcess(pid) 替代
    }

    /**
     * 获取指定 PID 的进程（实例方法）。
     */
    public ProcessRunner getProcess(int pid) {
        return allProcesses.get(pid);
    }

    /**
     * 检查进程是否活跃。
     */
    public boolean isProcessAlive(int pid) {
        ProcessRunner runner = allProcesses.get(pid);
        return runner != null && runner.isRunning();
    }

    /**
     * 获取所有活跃 PID。
     */
    public Set<Integer> getActivePids() {
        Set<Integer> active = new LinkedHashSet<>();
        for (Map.Entry<Integer, ProcessRunner> entry : allProcesses.entrySet()) {
            if (entry.getValue().isRunning()) {
                active.add(entry.getKey());
            }
        }
        return active;
    }

    /**
     * 停止调度器及所有进程。
     */
    public static void shutdown() {
        // 由 Scheduler 的实例处理
        // Main 调用时需要访问实例
    }

    /**
     * 停止调度器。
     */
    public void shutdownScheduler() {
        running = false;
        Logger.info("Scheduler shutting down...");
        // 停止所有进程
        for (ProcessRunner runner : allProcesses.values()) {
            runner.stopProcess();
        }
        // 虚拟线程模式：中断所有虚拟线程
        if (USE_VIRTUAL_THREADS) {
            for (ProcessRunner runner : allProcesses.values()) {
                ProcessRunner.unparkProcess(runner.getPid());
            }
        }
        allProcesses.clear();
        highPriorityQueue.clear();
        normalPriorityQueue.clear();
        lowPriorityQueue.clear();
        blockedProcesses.clear();
    }

    // ════════════════════════════════════════════
    // 调度主循环
    // ════════════════════════════════════════════

    @Override
    public void run() {
        if (USE_VIRTUAL_THREADS) {
            virtualThreadSchedulerLoop();
        } else {
            legacySchedulerLoop();
        }
        Logger.info("Scheduler stopped");
    }

    /**
     * 传统单线程调度循环（USE_VIRTUAL_THREADS = false）。
     */
    private void legacySchedulerLoop() {
        Logger.info("Scheduler started (legacy mode, tick=" + Constants.SCHEDULER_TICK_MS + "ms)");

        initialScan();

        while (running) {
            try {
                scanForNewProcesses();
                checkBlockedProcesses();
                dispatchNext();
                Thread.sleep(Constants.SCHEDULER_TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Logger.warn("Scheduler interrupted");
                break;
            } catch (Exception e) {
                Logger.error("Scheduler error: " + e.getMessage());
            }
        }
    }

    /**
     * 虚拟线程调度循环（USE_VIRTUAL_THREADS = true）。
     * <p>
     * 职责大幅简化，仅保留：
     * <ol>
     *   <li>进程发现 — 发现新 .proc 文件 → 创建 ProcessRunner → 启动虚拟线程</li>
     *   <li>进程终止检测 — 虚拟线程自然结束，扫描检查已结束进程</li>
     * </ol>
     * 不再需要就绪队列、阻塞队列和 dispatch 逻辑 —— JVM 虚拟线程调度器承担。
     */
    private void virtualThreadSchedulerLoop() {
        Logger.info("Virtual thread scheduler started (tick=" + Constants.SCHEDULER_TICK_MS + "ms)");

        initialScan();

        while (running) {
            try {
                scanForNewProcesses();
                Thread.sleep(Constants.SCHEDULER_TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Logger.warn("Scheduler interrupted");
                break;
            } catch (Exception e) {
                Logger.error("Scheduler error: " + e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════
    // 进程发现
    // ════════════════════════════════════════════

    /**
     * 首次扫描：为系统中已有的进程创建 runner（不含 INIT，由 Main 手动注册）。
     */
    private void initialScan() {
        Map<Integer, Map<String, Object>> existing = scanProcessFiles();
        for (Map.Entry<Integer, Map<String, Object>> entry : existing.entrySet()) {
            int pid = entry.getKey();
            if (pid == Constants.PID_INIT) {
                Logger.info("Found existing INIT process (PID=1)");
                continue;
            }

            // 跳过已终止的进程文件（Status=false）
            Object statusObj = entry.getValue().get("Status");
            if (statusObj instanceof Boolean && !(Boolean) statusObj) {
                continue;
            }

            if (!allProcesses.containsKey(pid)) {
                ProcessRunner runner = new ProcessRunner(pid, entry.getValue());
                runner.init();
                addProcess(runner);
                Logger.info("Restored existing process: PID=" + pid + " (" + runner.getProcessName() + ")");
            }
        }
    }

    /**
     * 扫描 /system/process/ 目录下的新进程文件。
     */
    private void scanForNewProcesses() {
        Map<Integer, Map<String, Object>> current = scanProcessFiles();

        for (Map.Entry<Integer, Map<String, Object>> entry : current.entrySet()) {
            int pid = entry.getKey();

            // 跳过已终止的进程（Status=false），避免无限循环套娃
            Object statusObj = entry.getValue().get("Status");
            if (statusObj instanceof Boolean && !(Boolean) statusObj) {
                continue;
            }

            if (!allProcesses.containsKey(pid)) {
                ProcessRunner runner = new ProcessRunner(pid, entry.getValue());
                runner.init();
                addProcess(runner);
                Logger.info("New process detected: PID=" + pid + " (" + runner.getProcessName() + ")");
            }
        }

        if (USE_VIRTUAL_THREADS) {
            // 虚拟线程模式：仅清理已删除的进程记录
            cleanupRemovedProcesses(current);
        } else {
            // 传统模式：清理 + 全终止检测
            cleanupRemovedProcesses(current);
            checkAllProcessesTerminated();
        }
    }

    /**
     * 从 allProcesses 中移除磁盘上已删除的进程。
     */
    private void cleanupRemovedProcesses(Map<Integer, Map<String, Object>> current) {
        Set<Integer> toRemove = new LinkedHashSet<>();
        for (int pid : allProcesses.keySet()) {
            if (!current.containsKey(pid)) {
                toRemove.add(pid);
            }
        }
        for (int pid : toRemove) {
            ProcessRunner runner = allProcesses.remove(pid);
            if (runner != null) {
                runner.stopProcess();
                if (!USE_VIRTUAL_THREADS) {
                    removeFromQueues(runner);
                    blockedProcesses.remove(pid);
                }
                Logger.info("Process removed: PID=" + pid);
            }
        }
    }

    /**
     * 检查是否所有进程已完成（仅传统模式）。
     */
    private void checkAllProcessesTerminated() {
        if (allProcesses.isEmpty()) return;
        boolean anyAlive = false;
        for (ProcessRunner r : allProcesses.values()) {
            if (r.isRunning()) {
                anyAlive = true;
                break;
            }
        }
        if (!anyAlive) {
            Logger.info("All processes completed, scheduler shutting down");
            running = false;
        }
    }

    // ════════════════════════════════════════════
    // 阻塞进程管理
    // ════════════════════════════════════════════

    /**
     * 检查阻塞队列中的进程是否可唤醒。
     */
    private void checkBlockedProcesses() {
        if (blockedProcesses.isEmpty()) return;

        Iterator<Map.Entry<Integer, ProcessRunner>> it = blockedProcesses.entrySet().iterator();
        while (it.hasNext()) {
            ProcessRunner runner = it.next().getValue();
            if (!runner.isRunning()) {
                it.remove();
                continue;
            }

            if (runner.checkWakeup()) {
                it.remove();
                enqueueByPriority(runner);
                Logger.info("Scheduler: PID " + runner.getPid()
                        + " woken, moved to ready queue");
            }
        }
    }

    // ════════════════════════════════════════════
    // 调度执行
    // ════════════════════════════════════════════

    /**
     * 从就绪队列中选取下一个进程并执行一个时间片。
     * 优先级：HIGH → NORMAL → LOW
     */
    private void dispatchNext() {
        ProcessRunner next = dequeueHighestPriority();
        if (next == null) return;

        // 每次 step() 前设置用户上下文（进程可能在上次执行后切换了用户）
        String owner = getProcessOwnerFromFile(next.getPid());
        if (owner != null) {
            com.follarce.util.UserUtil.setCurrentUser(owner);
        }

        ProcessRunner.StepResult result = next.step();

        if (result == ProcessRunner.StepResult.TERMINATED) {
            Logger.info("Scheduler: PID " + next.getPid() + " terminated");
            blockedProcesses.remove(next.getPid());
            return;
        }

        if (result == ProcessRunner.StepResult.BLOCKED) {
            blockedProcesses.put(next.getPid(), next);
            Logger.info("Scheduler: PID " + next.getPid() + " blocked");
            return;
        }

        // 进程仍在运行 → 回就绪队列队尾
        if (next.isRunning()) {
            enqueueByPriority(next);
        }
    }

    // ════════════════════════════════════════════
    // 队列操作
    // ════════════════════════════════════════════

    /**
     * 根据优先级将进程加入对应队列。
     */
    private void enqueueByPriority(ProcessRunner runner) {
        switch (runner.getPriority()) {
            case Constants.PRIORITY_HIGH:
                highPriorityQueue.offer(runner);
                break;
            case Constants.PRIORITY_LOW:
                lowPriorityQueue.offer(runner);
                break;
            default:
                normalPriorityQueue.offer(runner);
                break;
        }
    }

    /**
     * 从最高优先级非空队列取出队首进程。
     */
    private ProcessRunner dequeueHighestPriority() {
        if (!highPriorityQueue.isEmpty()) {
            return highPriorityQueue.poll();
        }
        if (!normalPriorityQueue.isEmpty()) {
            return normalPriorityQueue.poll();
        }
        if (!lowPriorityQueue.isEmpty()) {
            return lowPriorityQueue.poll();
        }
        return null;
    }

    /**
     * 从所有就绪队列中移除指定进程。
     */
    private void removeFromQueues(ProcessRunner runner) {
        highPriorityQueue.remove(runner);
        normalPriorityQueue.remove(runner);
        lowPriorityQueue.remove(runner);
    }

    // ════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════

    /**
     * 从进程文件读取 Owner 字段。
     */
    private String getProcessOwnerFromFile(int pid) {
        String path = PathUtil.findProcessFilePathByPid(pid);
        if (path == null || !FileUtil.exists(path)) return null;
        try {
            String content = FileUtil.read(path);
            Map<String, Object> data = JsonUtil.parseToMap(content);
            Object owner = data.get("Owner");
            return owner instanceof String ? (String) owner : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 扫描进程目录，返回 PID → processData 的映射。
     */
    private Map<Integer, Map<String, Object>> scanProcessFiles() {
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        String processDir = PathUtil.toRealPath(Constants.SYSTEM_PROCESS_PATH);
        File dir = new File(processDir);

        if (!dir.exists() || !dir.isDirectory()) return result;

        File[] files = dir.listFiles((d, name) -> name.endsWith(".proc"));
        if (files == null) return result;

        for (File file : files) {
            try {
                String name = file.getName();
                String vfsPath = Constants.SYSTEM_PROCESS_PATH + name;

                // 跳过正在写入的临时文件
                if (name.endsWith(".tmp")) continue;

                String content = FileUtil.read(vfsPath);
                if (content == null || content.trim().isEmpty()) continue;

                Map<String, Object> processData = JsonUtil.parseToMap(content);
                Object pidObj = processData.get("PID");
                if (!(pidObj instanceof Number)) continue;
                int pid = ((Number) pidObj).intValue();

                result.put(pid, processData);
            } catch (Exception e) {
                Logger.warn("Failed to load process file: " + file.getName() + " - " + e.getMessage());
            }
        }

        return result;
    }
}
