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
import com.follarce.kernel.terminal.HostTerminal;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.shell.ConsoleShell;
import com.follarce.shell.SystemControlService;

import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cilexec 模拟操作系统入口。
 * <p>
 * 启动流程：
 * 1. 确定 VFS 根目录并在其中初始化日志
 * 2. 初始化 VFS、内置函数、包和进程系统
 * 3. 启动调度器
 * 4. 注册 shutdown hook
 * 5. 在主线程进入宿主 Shell
 */
public class Main {

    private static Scheduler scheduler;
    private static FileChannel instanceLockChannel;
    private static FileLock instanceLock;
    private static final AtomicBoolean shutdownStarted = new AtomicBoolean();

    public static void main(String[] args) {
        try {
            // 容器只暴露宿主 Shell；任何启动恢复逻辑都不能读取它的命令流。
            HostTerminal.claimForShell();

            // 1. 确定 VFS 根目录，日志也必须保存在此边界内
            File vfsRoot = determineVfsRoot();
            Logger.init(new File(vfsRoot, "cilexec.log").getAbsolutePath());
            Logger.logStartup();
            acquireInstanceLock(vfsRoot);
            Logger.info("VFS root: " + vfsRoot.getAbsolutePath());

            // 2. 初始化 VFS 文件系统
            FileInit.init(vfsRoot);
            Logger.info("File system initialized");

            // 3. 装配编译进单一二进制文件的内置扩展
            int providerCount = BuiltinProviderIndex.install();
            Logger.info("Built-in function providers installed: " + providerCount);

            // 4. 初始化包系统（hook 恢复需要函数提供者已注册）
            PackageInit.init();
            Logger.info("Package system initialized");

            // 5. 初始化进程系统
            ProcessInit.init();
            RecoveryManager.recoverAll();
            Logger.info("Process system initialized");

            // 6. 创建调度器
            scheduler = new Scheduler();

            // 7. 手动启动 INIT 进程并注册到调度器
            ProcessRunner initRunner = startInitProcess();
            if (initRunner != null) {
                initRunner.init();
                scheduler.addProcess(initRunner);
            }

            // 8. 启动调度器
            scheduler.start();
            Logger.info("Scheduler started");

            // 9. 注册 shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(Main::shutdownSystem, "CilexecShutdown"));

            Logger.info("=== Cilexec system ready ===");
            new ConsoleShell(
                    new InputStreamReader(System.in),
                    new PrintWriter(System.out, true),
                    new SystemControlService(),
                    Main::shutdownSystem
            ).run();

        } catch (Exception e) {
            if (Logger.isInitialized()) Logger.logException("System startup failed", e);
            System.err.println("FATAL: System startup failed: " + e.getMessage());
            e.printStackTrace();
            shutdownSystem();
            System.exit(1);
        }
    }

    private static void shutdownSystem() {
        if (!shutdownStarted.compareAndSet(false, true)) return;
        if (Logger.isInitialized()) Logger.logShutdown();
        if (scheduler != null) {
            scheduler.shutdownScheduler();
            scheduler.interrupt();
            if (Thread.currentThread() != scheduler) {
                try {
                    scheduler.join(1_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        HostTerminal.releaseFromShell();
        releaseInstanceLock();
        Logger.close();
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
            vfsRoot.mkdirs();
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
