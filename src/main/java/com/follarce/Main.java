package com.follarce;

import com.follarce.bootstrap.BuiltinProviderIndex;
import com.follarce.bootstrap.init.FileInit;
import com.follarce.bootstrap.init.PackageInit;
import com.follarce.bootstrap.init.ProcessInit;
import com.follarce.kernel.Constants;
import com.follarce.kernel.log.Logger;
import com.follarce.kernel.process.ProcessRunner;
import com.follarce.kernel.process.RecoveryManager;
import com.follarce.kernel.process.Scheduler;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;

/**
 * Cilexec 模拟操作系统入口。
 * <p>
 * 启动流程：
 * 1. 初始化日志
 * 2. 初始化 VFS 文件系统
 * 3. 装配所有编译时内置函数
 * 4. 初始化进程系统
 * 5. 启动调度器
 * 6. 注册 shutdown hook
 */
public class Main {

    private static Scheduler scheduler;
    private static FileChannel instanceLockChannel;
    private static FileLock instanceLock;

    public static void main(String[] args) {
        // 1. 初始化日志
        Logger.init("cilexec.log");
        Logger.logStartup();

        try {
            // 2. 确定 VFS 根目录
            File vfsRoot = determineVfsRoot();
            acquireInstanceLock(vfsRoot);
            Logger.info("VFS root: " + vfsRoot.getAbsolutePath());

            // 3. 初始化 VFS 文件系统
            FileInit.init(vfsRoot);
            Logger.info("File system initialized");

            // 4. 装配编译进单一二进制文件的内置扩展
            int providerCount = BuiltinProviderIndex.install();
            Logger.info("Built-in function providers installed: " + providerCount);

            // 5. 初始化包系统（hook 恢复需要函数提供者已注册）
            PackageInit.init();
            Logger.info("Package system initialized");

            // 6. 初始化进程系统
            ProcessInit.init();
            RecoveryManager.recoverAll();
            Logger.info("Process system initialized");

            // 7. 创建调度器
            scheduler = new Scheduler();

            // 8. 手动启动 INIT 进程并注册到调度器
            ProcessRunner initRunner = startInitProcess();
            if (initRunner != null) {
                initRunner.init();
                scheduler.addProcess(initRunner);
            }

            // 9. 启动调度器
            scheduler.start();
            Logger.info("Scheduler started");

            // 10. 注册 shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Logger.logShutdown();
                if (scheduler != null) {
                    scheduler.shutdownScheduler();
                }
                releaseInstanceLock();
                Logger.close();
            }));

            Logger.info("=== Cilexec system ready ===");
            System.out.println("Cilexec (CilExec) system started. PID 1 (INIT) running.");
            System.out.println("Type Ctrl+C to shutdown.");

        } catch (Exception e) {
            Logger.logException("System startup failed", e);
            System.err.println("FATAL: System startup failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void acquireInstanceLock(File root) throws Exception {
        var lockPath = root.toPath().resolve(".cilexec.instance.lock");
        instanceLockChannel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        instanceLock = instanceLockChannel.tryLock();
        if (instanceLock == null) {
            instanceLockChannel.close();
            throw new IllegalStateException("Another Cilexec instance owns this VFS root");
        }
    }

    private static void releaseInstanceLock() {
        try {
            if (instanceLock != null && instanceLock.isValid()) instanceLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (instanceLockChannel != null) instanceLockChannel.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 确定 VFS 根目录（JAR 所在目录下的 cilexec_root）。
     */
    private static File determineVfsRoot() {
        // 尝试从 init.json 读取已配置的根路径
        String userDir = System.getProperty("user.dir");
        File vfsRoot = new File(userDir, "cilexec_root");

        if (!vfsRoot.exists()) {
            if (vfsRoot.mkdirs()) {
                Logger.info("Created VFS root directory: " + vfsRoot.getAbsolutePath());
            }
        }

        return vfsRoot;
    }

    /**
     * 启动 INIT 进程（PID 1），返回 ProcessRunner。
     */
    private static ProcessRunner startInitProcess() {
        String initProcessPath = com.follarce.kernel.vfs.PathUtil.getProcessFilePath(Constants.PID_INIT);
        if (!FileUtil.exists(initProcessPath)) {
            Logger.warn("INIT process file not found, creating...");
            ProcessInit.createInitProcess();
        }
        String content = FileUtil.read(initProcessPath);
        if (content != null && !content.trim().isEmpty()) {
            var processData = JsonUtil.parseToMap(content);
            ProcessRunner initRunner = new ProcessRunner(Constants.PID_INIT, processData);
            Logger.info("Started INIT process (PID=1)");
            return initRunner;
        }
        Logger.error("Failed to start INIT process");
        return null;
    }
}
