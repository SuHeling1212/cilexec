package com.follarce.init;

import com.follarce.Constants;
import com.follarce.log.Logger;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件系统初始化 —— 创建 VFS 目录树、配置文件、复制 INIT.fcl。
 */
public final class FileInit {

    private FileInit() {}

    /**
     * 初始化 VFS 文件系统。
     */
    public static void init(File vfsRoot) {
        Logger.info("FileInit: Initializing VFS at " + vfsRoot.getAbsolutePath());

        // 设置 VFS 根目录
        PathUtil.setVfsRoot(vfsRoot);

        // 创建目录结构
        createDirectories();

        // 创建配置文件
        createFiles();

        // 复制 INIT.fcl
        copyInitFile();

        // 复制测试脚本
        copyTestFiles();

        Logger.info("FileInit: VFS initialized successfully");
    }

    /**
     * 创建 VFS 目录树并写入 .META 元数据。
     */
    public static void createDirectories() {
        for (String dirPath : Constants.VFS_ROOT_DIRS) {
            String realPath = PathUtil.toRealPath(dirPath);
            File dir = new File(realPath);
            if (!dir.exists()) {
                if (dir.mkdirs()) {
                    Logger.info("Created directory: " + dirPath);
                } else {
                    Logger.error("Failed to create directory: " + dirPath);
                    continue;
                }
            }
            // 创建 .META 文件
            FileUtil.createDirectoryMetaData(dirPath);
        }
    }

    /**
     * 创建关键配置文件。
     */
    public static void createFiles() {
        // init.json (VFS 根路径配置)
        createInitJson();

        // local.json (local 用户信息)
        createLocalJson();

        // users.json (用户列表)
        createUsersJson();

        // env.json (环境变量 + 路径别名)
        createEnvJson();
    }

    /**
     * 创建 init.json。
     */
    private static void createInitJson() {
        String path = Constants.SYSTEM_CONFIG_PATH + Constants.CONFIG_INIT_JSON;
        if (!FileUtil.exists(path)) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("root", PathUtil.getVfsRoot() != null ?
                    PathUtil.getVfsRoot().getAbsolutePath() : "");
            FileUtil.createFile(Constants.SYSTEM_CONFIG_PATH, Constants.CONFIG_INIT_JSON);
            FileUtil.write(path, JsonUtil.toMetaJson(config));
            Logger.info("Created init.json");
        }
    }

    /**
     * 创建 local.json。
     */
    private static void createLocalJson() {
        String path = Constants.USER_LOCAL_PATH + Constants.CONFIG_LOCAL_JSON;
        if (!FileUtil.exists(path)) {
            Map<String, Object> localInfo = new LinkedHashMap<>();
            localInfo.put("username", Constants.DEFAULT_USER_LOCAL);
            localInfo.put("home", Constants.USER_LOCAL_PATH);
            localInfo.put("created", FileUtil.getCurrentTimeArray());
            FileUtil.createFile(Constants.USER_LOCAL_PATH, Constants.CONFIG_LOCAL_JSON);
            FileUtil.write(path, JsonUtil.toMetaJson(localInfo));
            Logger.info("Created local.json");
        }
    }

    /**
     * 创建 users.json（含 local 用户）。
     */
    private static void createUsersJson() {
        String path = Constants.SYSTEM_CONFIG_PATH + Constants.CONFIG_USERS_JSON;
        if (!FileUtil.exists(path)) {
            Map<String, Object> config = UserUtil.createDefaultUsersConfig();
            FileUtil.createFile(Constants.SYSTEM_CONFIG_PATH, Constants.CONFIG_USERS_JSON);
            FileUtil.write(path, JsonUtil.toMetaJson(config));
            Logger.info("Created users.json");
        }
    }

    /**
     * 创建 env.json（环境变量和路径别名）。
     */
    private static void createEnvJson() {
        String path = Constants.SYSTEM_CONFIG_PATH + Constants.CONFIG_ENV_JSON;
        if (!FileUtil.exists(path)) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("HOME", Constants.USER_LOCAL_PATH);
            env.put("SYSTEM", "/system");
            env.put("PATH", "/system/app:/user/local/app");

            Map<String, Object> aliases = new LinkedHashMap<>();
            aliases.put("home", Constants.USER_LOCAL_PATH);
            aliases.put("system", "/system");
            aliases.put("temp", "/system/swap");
            env.put("aliases", aliases);

            FileUtil.createFile(Constants.SYSTEM_CONFIG_PATH, Constants.CONFIG_ENV_JSON);
            FileUtil.write(path, JsonUtil.toMetaJson(env));
            Logger.info("Created env.json");
        }
    }

    /**
     * 从 classpath 复制测试脚本到 VFS。
     */
    public static void copyTestFiles() {
        String[] testFiles = {"tests/test_all.fcl", "tests/lib1.fcl", "tests/lib2.fcl", "tests/lib_unique.fcl"};
        for (String resourcePath : testFiles) {
            String destDir = Constants.SYSTEM_APP_PATH;
            String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            String destPath = destDir + fileName;
            if (FileUtil.exists(destPath)) continue;

            try {
                InputStream in = FileInit.class.getClassLoader().getResourceAsStream(resourcePath);
                if (in != null) {
                    String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    in.close();
                    if (!FileUtil.exists(destDir)) {
                        FileUtil.createDirectory("/system", "app");
                    }
                    FileUtil.createFile(destDir, fileName);
                    FileUtil.write(destPath, content);
                    Logger.info("Copied test script: " + resourcePath + " -> " + destPath);
                } else {
                    Logger.warn("Test resource not found in classpath: " + resourcePath);
                }
            } catch (IOException e) {
                Logger.error("Failed to copy test file: " + resourcePath + " - " + e.getMessage());
            }
        }
    }

    /**
     * 从 classpath 复制 INIT.fcl 到 VFS。
     */
    public static void copyInitFile() {
        String destPath = Constants.SYSTEM_CONFIG_PATH + Constants.INIT_FCL;
        if (FileUtil.exists(destPath)) return;

        try {
            // 尝试从 classpath 加载
            InputStream in = FileInit.class.getClassLoader().getResourceAsStream(Constants.INIT_FCL);
            if (in != null) {
                String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                in.close();

                // 创建文件并写入
                FileUtil.createFile(Constants.SYSTEM_CONFIG_PATH, Constants.INIT_FCL);

                if (content.trim().isEmpty()) {
                    // 空文件时的 fallback
                    content = "// CilExec INIT process\n"
                            + "// Default: idle loop\n"
                            + "print(\"INIT process started\")\n"
                            + "while true {\n"
                            + "    // idle\n"
                            + "}";
                }
                FileUtil.write(destPath, content);
                Logger.info("Copied INIT.fcl from classpath");
            } else {
                // 没有资源文件，创建默认 INIT.fcl
                Logger.warn("INIT.fcl not found in classpath, creating default");
                String defaultInit = "// CilExec INIT process\n"
                        + "print(\"INIT process started\")\n"
                        + "while true {\n"
                        + "    // idle\n"
                        + "}";
                FileUtil.createFile(Constants.SYSTEM_CONFIG_PATH, Constants.INIT_FCL);
                FileUtil.write(destPath, defaultInit);
            }
        } catch (IOException e) {
            Logger.error("Failed to copy INIT.fcl: " + e.getMessage());
        }
    }

    // 为了解决编译依赖，引入 UserUtil
    private static class UserUtil {
        static Map<String, Object> createDefaultUsersConfig() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("currentUser", Constants.DEFAULT_USER_LOCAL);

            Map<String, Object> users = new LinkedHashMap<>();
            Map<String, Object> localUser = new LinkedHashMap<>();
            localUser.put("password", Constants.DEFAULT_PASSWORD_LOCAL);
            localUser.put("isLocal", true);
            localUser.put("home", Constants.USER_LOCAL_PATH);
            localUser.put("created", FileUtil.getCurrentTimeArray());
            users.put(Constants.DEFAULT_USER_LOCAL, localUser);

            config.put("users", users);
            return config;
        }
    }
}
