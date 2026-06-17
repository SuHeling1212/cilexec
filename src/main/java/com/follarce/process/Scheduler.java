package com.follarce.process;

import com.follarce.Constants;
import com.follarce.log.Logger;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程调度器 —— 基于目录扫描的守护线程。
 * 每 100ms 扫描 /system/process/ 目录，管理进程生命周期。
 */
public class Scheduler extends Thread {

    private static final Map<Integer, ProcessRunner> runners = new ConcurrentHashMap<>();
    private static volatile boolean running = true;

    public Scheduler() {
        super("ProcessScheduler");
        setDaemon(true);
    }

    @Override
    public void run() {
        Logger.info("Scheduler started (tick=" + Constants.SCHEDULER_SLEEP_MS + "ms)");

        // 首次扫描：为已有进程创建 runner
        initialScan();

        while (running) {
            try {
                tick();
                Thread.sleep(Constants.SCHEDULER_SLEEP_MS);
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

    /**
     * 注册一个已有的 ProcessRunner（不被 initialScan 重复启动）。
     */
    public static void registerRunner(int pid, ProcessRunner runner) {
        runners.put(pid, runner);
    }

    /**
     * 停止调度器。
     */
    public static void shutdown() {
        running = false;
        // 停止所有进程 runner
        for (Map.Entry<Integer, ProcessRunner> entry : runners.entrySet()) {
            entry.getValue().stopProcess();
        }
        runners.clear();
    }

    /**
     * 获取指定 PID 的 runner。
     */
    public static ProcessRunner getRunner(int pid) {
        return runners.get(pid);
    }

    /**
     * 检查进程是否活跃。
     */
    public static boolean isProcessAlive(int pid) {
        ProcessRunner runner = runners.get(pid);
        return runner != null && runner.isRunning();
    }

    /**
     * 获取所有活跃 PID 集合。
     */
    public static Set<Integer> getActivePids() {
        Set<Integer> active = new LinkedHashSet<>();
        for (Map.Entry<Integer, ProcessRunner> entry : runners.entrySet()) {
            if (entry.getValue().isRunning()) {
                active.add(entry.getKey());
            }
        }
        return active;
    }

    /**
     * 首次扫描：为系统中已有的进程创建 runner。
     */
    private void initialScan() {
        Map<Integer, ProcessRunner> existing = scanForProcesses();
        for (Map.Entry<Integer, ProcessRunner> entry : existing.entrySet()) {
            int pid = entry.getKey();
            if (pid == Constants.PID_INIT) {
                // INIT 进程需要手动启动，由 Main.java 的 registerRunner 处理
                Logger.info("Found existing INIT process (PID=1)");
                continue;
            }
            if (!runners.containsKey(pid)) {
                ProcessRunner runner = entry.getValue();
                runners.put(pid, runner);
                runner.start();
                Logger.info("Started runner for PID " + pid);
            }
        }
    }

    /**
     * 调度器单次 tick：扫描新进程、移除已删除的进程。
     */
    private void tick() {
        // 扫描当前进程文件
        Map<Integer, ProcessRunner> currentProcesses = scanForProcesses();

        // 新进程：启动 runner
        for (Map.Entry<Integer, ProcessRunner> entry : currentProcesses.entrySet()) {
            int pid = entry.getKey();
            if (!runners.containsKey(pid)) {
                ProcessRunner runner = entry.getValue();
                runners.put(pid, runner);
                runner.start();
                Logger.info("New process detected: PID=" + pid + " (" + runner.getProcessName() + ")");
            }
        }

        // 已删除的进程：停止 runner
        Set<Integer> toRemove = new LinkedHashSet<>();
        for (int pid : runners.keySet()) {
            if (!currentProcesses.containsKey(pid)) {
                toRemove.add(pid);
            }
        }
        for (int pid : toRemove) {
            ProcessRunner runner = runners.remove(pid);
            if (runner != null) {
                runner.stopProcess();
                Logger.info("Process removed: PID=" + pid);
            }
        }
    }

    /**
     * 扫描 /system/process/ 目录，返回 PID → ProcessRunner 映射。
     */
    private Map<Integer, ProcessRunner> scanForProcesses() {
        Map<Integer, ProcessRunner> result = new LinkedHashMap<>();
        String processDir = PathUtil.toRealPath(Constants.SYSTEM_PROCESS_PATH);
        File dir = new File(processDir);

        if (!dir.exists() || !dir.isDirectory()) return result;

        File[] files = dir.listFiles((d, name) -> name.endsWith(".pres"));
        if (files == null) return result;

        for (File file : files) {
            try {
                String name = file.getName();

                // 读取进程文件
                String content = FileUtil.read(Constants.SYSTEM_PROCESS_PATH + name);
                if (content == null || content.trim().isEmpty()) continue;

                Map<String, Object> processData = JsonUtil.parseToMap(content);
                Object pidObj = processData.get("PID");
                if (!(pidObj instanceof Number)) continue;
                int pid = ((Number) pidObj).intValue();

                ProcessRunner runner = new ProcessRunner(pid, processData);
                result.put(pid, runner);
            } catch (Exception e) {
                Logger.warn("Failed to load process file: " + file.getName() + " - " + e.getMessage());
            }
        }

        return result;
    }
}
