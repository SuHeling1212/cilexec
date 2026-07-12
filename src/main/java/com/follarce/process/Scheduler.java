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
    private final Map<Integer, ProcessRunner> allProcesses = new LinkedHashMap<>();

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
                toRemove.add(pid);
            }
        }
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
