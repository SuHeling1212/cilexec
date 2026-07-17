package com.follarce.process;

import com.follarce.Constants;
import com.follarce.log.Logger;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;

import java.io.File;
import java.util.*;

/**
 * 进程调度器 —— 虚拟线程驱动。
 * <p>
 * 每个进程在自己的虚拟线程中执行，调度器仅负责：
 * <ol>
 *   <li>进程发现 — 扫描 .proc 文件，创建 ProcessRunner 并启动虚拟线程</li>
 *   <li>进程终止检测 — 清理已结束的进程，所有进程结束时调度器退出</li>
 * </ol>
 */
public class Scheduler extends Thread {

    // 所有已知进程（用于快速查找）
    private final Map<Integer, ProcessRunner> allProcesses = new java.util.concurrent.ConcurrentHashMap<>();

    // 进程文件连续缺失计数（PID → 连续未扫描到的次数）
    // 用于容忍 writeAtomic 原子重命名期间的瞬时读取失败
    private final Map<Integer, Integer> missingCounts = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile boolean running = true;

    public Scheduler() {
        super("ProcessScheduler");
        setDaemon(false);
    }

    // ════════════════════════════════════════════
    // 公共 API
    // ════════════════════════════════════════════

    /**
     * 添加一个进程到调度器并启动其虚拟线程。
     */
    public void addProcess(ProcessRunner runner) {
        allProcesses.put(runner.getPid(), runner);
        Logger.info("Scheduler: PID " + runner.getPid() + " (" + runner.getProcessName()
                + ") registered (priority=" + runner.getPriority() + ")");

        Thread vt = Thread.ofVirtual()
                .name("vt-process-" + runner.getPid())
                .start(runner::virtualThreadRun);
        Logger.info("Virtual thread started for PID " + runner.getPid());
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
    public void shutdownScheduler() {
        running = false;
        Logger.info("Scheduler shutting down...");
        for (ProcessRunner runner : allProcesses.values()) {
            runner.stopProcess();
        }
        for (ProcessRunner runner : allProcesses.values()) {
            ProcessRunner.unparkProcess(runner.getPid());
        }
        allProcesses.clear();
    }

    // ════════════════════════════════════════════
    // 调度主循环
    // ════════════════════════════════════════════

    @Override
    public void run() {
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
        Logger.info("Scheduler stopped");
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

            // 终态文件用于诊断，不应重新创建执行线程。
            if (isTerminal(entry.getValue())) {
                if (entry.getValue().get("LifecycleCleanup") instanceof Map
                        || entry.getValue().get("TerminationCleanup") instanceof Map) {
                    ProcessRunner.reconcileLifecycle(pid);
                }
                continue;
            }

            ProcessRunner known = allProcesses.get(pid);
            if (known != null && !sameGeneration(known, entry.getValue())) {
                known.stopProcess();
                allProcesses.remove(pid);
                known = null;
            }
            if (known != null && !known.isRunning()) {
                allProcesses.remove(pid);
                missingCounts.remove(pid);
                known = null;
            }
            if (known == null) {
                try {
                    ProcessRunner runner = new ProcessRunner(pid, entry.getValue());
                    runner.init();
                    addProcess(runner);
                    Logger.info("Restored existing process: PID=" + pid + " (" + runner.getProcessName() + ")");
                } catch (RuntimeException e) {
                    Logger.warn("Failed to restore PID " + pid + ": " + e.getMessage());
                }
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

            // Main owns INIT registration; discovery must never create a second PID 1 runner.
            if (pid == Constants.PID_INIT) continue;

            if (isTerminal(entry.getValue())) {
                if (entry.getValue().get("LifecycleCleanup") instanceof Map
                        || entry.getValue().get("TerminationCleanup") instanceof Map) {
                    ProcessRunner.reconcileLifecycle(pid);
                }
                continue;
            }

            ProcessRunner known = allProcesses.get(pid);
            if (known != null && !sameGeneration(known, entry.getValue())) {
                known.stopProcess();
                allProcesses.remove(pid);
                missingCounts.remove(pid);
                known = null;
            }
            if (known != null && !known.isRunning()) {
                allProcesses.remove(pid);
                missingCounts.remove(pid);
                known = null;
            }
            if (known == null) {
                try {
                    ProcessRunner runner = new ProcessRunner(pid, entry.getValue());
                    runner.init();
                    addProcess(runner);
                    Logger.info("New process detected: PID=" + pid + " (" + runner.getProcessName() + ")");
                } catch (RuntimeException e) {
                    Logger.warn("Failed to initialize PID " + pid + ": " + e.getMessage());
                }
            }
        }

        cleanupRemovedProcesses(current);
        checkAllProcessesTerminated();
    }

    /**
     * 从 allProcesses 中移除磁盘上已删除的进程。
     */
    private void cleanupRemovedProcesses(Map<Integer, Map<String, Object>> current) {
        Set<Integer> toRemove = new LinkedHashSet<>();
        for (int pid : allProcesses.keySet()) {
            if (!current.containsKey(pid)) {
                // 确认 .proc 文件确实不在磁盘上，而非因 writeAtomic 瞬时读取失败
                String procPath = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
                if (!com.follarce.util.FileUtil.exists(procPath)) {
                    int misses = missingCounts.getOrDefault(pid, 0) + 1;
                    missingCounts.put(pid, misses);
                    // 连续 3 次（~150ms）文件确实不存在才确认死亡
                    if (misses >= 3) {
                        toRemove.add(pid);
                        missingCounts.remove(pid);
                    }
                }
                // 文件存在但读取失败 → 保留进程，不计入缺失
            } else {
                missingCounts.remove(pid);  // 重新扫描到，重置计数
            }
        }
        // 清理已不追踪的 PID 的计数
        missingCounts.keySet().removeIf(pid -> !allProcesses.containsKey(pid));

        for (int pid : toRemove) {
            ProcessRunner runner = allProcesses.remove(pid);
            if (runner != null) {
                runner.stopProcess();
                Logger.info("Process removed: PID=" + pid);
            }
        }
    }

    /**
     * 检查是否所有进程已完成，是则停止调度器。
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
    // 辅助
    // ════════════════════════════════════════════

    /**
     * 扫描进程目录，返回 PID → processData 的映射。
     */
    private Map<Integer, Map<String, Object>> scanProcessFiles() {
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        String processDir = PathUtil.toRealPath(Constants.SYSTEM_PROCESS_PATH);
        File dir = new File(processDir);

        if (!dir.exists() || !dir.isDirectory()) return result;

        File[] files = dir.listFiles((d, name) -> name.matches("\\d+\\.proc(?:\\.tmp)?"));
        if (files == null) return result;

        Set<String> processNames = new LinkedHashSet<>();
        for (File file : files) {
            processNames.add(file.getName().replaceFirst("\\.tmp$", ""));
        }

        for (String name : processNames) {
            try {
                String vfsPath = Constants.SYSTEM_PROCESS_PATH + name;
                // exists() promotes a valid tmp-only snapshot before it is read.
                if (!FileUtil.exists(vfsPath)) continue;

                String content = FileUtil.read(vfsPath);
                if (content == null || content.trim().isEmpty()) continue;

                Object fileOwner = FileUtil.readFileMetaData(vfsPath).get("Owner");
                if (!Constants.DEFAULT_USER_LOCAL.equals(fileOwner)) {
                    Logger.warn("Rejected non-system process snapshot: " + name);
                    continue;
                }

                Map<String, Object> processData = JsonUtil.parseToMapStrict(content);
                if (Boolean.TRUE.equals(processData.get("Reservation"))) continue;
                Object pidObj = processData.get("PID");
                if (!(pidObj instanceof Number)) continue;
                int pid = ((Number) pidObj).intValue();
                int filePid = Integer.parseInt(name.substring(0, name.indexOf('.')));
                if (pid != filePid) {
                    Logger.warn("Rejected process file with mismatched PID: " + name);
                    continue;
                }

                result.put(pid, processData);
            } catch (Exception e) {
                Logger.warn("Failed to load process file: " + name + " - " + e.getMessage());
            }
        }

        return result;
    }

    private boolean isTerminal(Map<String, Object> processData) {
        return ProcessState.restore(processData.get("ProcessState"), processData.get("Status")).isTerminal();
    }

    private boolean sameGeneration(ProcessRunner runner, Map<String, Object> processData) {
        Object generation = processData.get("ProcessGeneration");
        return generation instanceof String && generation.equals(runner.getProcessGeneration());
    }
}
