package com.follarce;

import com.follarce.function.*;
import com.follarce.init.FileInit;
import com.follarce.init.ProcessInit;
import com.follarce.log.Logger;
import com.follarce.process.ProcessRunner;
import com.follarce.process.Scheduler;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;

import java.io.File;

/**
 * Cilexec 模拟操作系统入口。
 * <p>
 * 启动流程：
 * 1. 初始化日志
 * 2. 初始化 VFS 文件系统
 * 3. 注册所有插件函数
 * 4. 初始化进程系统
 * 5. 启动调度器
 * 6. 注册 shutdown hook
 */
public class Main {

    private static Scheduler scheduler;

    public static void main(String[] args) {
        // 1. 初始化日志
        Logger.init("cilexec.log");
        Logger.logStartup();

        try {
            // 2. 确定 VFS 根目录
            File vfsRoot = determineVfsRoot();
            Logger.info("VFS root: " + vfsRoot.getAbsolutePath());

            // 3. 初始化 VFS 文件系统
            FileInit.init(vfsRoot);
            Logger.info("File system initialized");

            // 4. 注册所有插件函数
            registerFunctionProviders();
            Logger.info("Function providers registered");

            // 5. 初始化进程系统
            ProcessInit.init();
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
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Logger.logShutdown();
                if (scheduler != null) {
                    scheduler.shutdownScheduler();
                }
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
        System.out.println("=== Cilexec system closed ===");
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
     * 注册所有 10 个函数提供者。
     */
    private static void registerFunctionProviders() {
        FunctionRegistry.registerProvider(new FileFunctionProvider());
        FunctionRegistry.registerProvider(new ProcessFunctionProvider());
        FunctionRegistry.registerProvider(new SwapFunctionProvider());
        FunctionRegistry.registerProvider(new UserFunctionProvider());
        FunctionRegistry.registerProvider(new UtilFunctionProvider());
        FunctionRegistry.registerProvider(new NetworkFunctionProvider());
        FunctionRegistry.registerProvider(new SocketFunctionProvider());
        FunctionRegistry.registerProvider(new MathFunctionProvider());
        FunctionRegistry.registerProvider(new PathFunctionProvider());
        FunctionRegistry.registerProvider(new IOFunctionProvider());
        FunctionRegistry.registerProvider(new PrivilegedFunctionProvider());
    }

    /**
     * 启动 INIT 进程（PID 1），返回 ProcessRunner。
     */
    private static ProcessRunner startInitProcess() {
        String initProcessPath = com.follarce.util.PathUtil.getProcessFilePath(Constants.PID_INIT);
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
