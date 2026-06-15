package com.follarce.util;

import com.follarce.Constants;
import com.follarce.log.Logger;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户系统工具类 —— 管理用户配置、身份认证、ThreadLocal 用户上下文。
 */
public final class UserUtil {

    private UserUtil() {}

    private static final ThreadLocal<String> currentProcessUser = new ThreadLocal<>();

    // ── 用户配置路径 ──

    private static String getUsersConfigPath() {
        return Constants.SYSTEM_CONFIG_PATH + Constants.CONFIG_USERS_JSON;
    }

    // ── ThreadLocal 用户管理 ──

    /**
     * 设置当前线程（进程）的用户。
     */
    public static void setCurrentUser(String username) {
        currentProcessUser.set(username);
    }

    /**
     * 获取当前线程（进程）的用户。
     */
    public static String getCurrentUser() {
        String user = currentProcessUser.get();
        return user != null ? user : Constants.DEFAULT_USER_LOCAL;
    }

    /**
     * 清除当前线程的用户设置。
     */
    public static void clearCurrentUser() {
        currentProcessUser.remove();
    }

    // ── 用户 CRUD ──

    /**
     * 从 users.json 读取用户配置。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readUsersConfig() {
        String path = getUsersConfigPath();
        if (!FileUtil.exists(path)) {
            // 创建默认用户配置
            Map<String, Object> config = createDefaultUsersConfig();
            saveUsersConfig(config);
            return config;
        }
        String content = FileUtil.read(path);
        if (content == null || content.trim().isEmpty()) {
            return createDefaultUsersConfig();
        }
        Object parsed = JsonUtil.parseJson(content);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        return createDefaultUsersConfig();
    }

    /**
     * 保存用户配置到 users.json。
     */
    public static void saveUsersConfig(Map<String, Object> config) {
        String json = JsonUtil.toMetaJson(config);
        FileUtil.write(getUsersConfigPath(), json);
    }

    /**
     * 验证用户密码。
     */
    @SuppressWarnings("unchecked")
    public static boolean validateUser(String username, String password) {
        Map<String, Object> config = readUsersConfig();
        Map<String, Object> users = (Map<String, Object>) config.get("users");
        if (users == null) return false;

        Map<String, Object> userData = (Map<String, Object>) users.get(username);
        if (userData == null) return false;

        String storedPassword = (String) userData.get("password");
        return password != null && password.equals(storedPassword);
    }

    /**
     * 创建用户（自动创建 home 目录）。
     */
    @SuppressWarnings("unchecked")
    public static String createUser(String username, String password, boolean isLocal) {
        if (username == null || username.trim().isEmpty()) {
            return errorResult("Username cannot be empty");
        }
        if (password == null || password.trim().isEmpty()) {
            return errorResult("Password cannot be empty");
        }

        Map<String, Object> config = readUsersConfig();
        Map<String, Object> users = (Map<String, Object>) config.get("users");
        if (users == null) {
            users = new LinkedHashMap<>();
            config.put("users", users);
        }
        if (users.containsKey(username)) {
            return errorResult("User already exists: " + username);
        }

        Map<String, Object> newUser = new LinkedHashMap<>();
        newUser.put("password", password);
        newUser.put("isLocal", isLocal);
        newUser.put("home", Constants.USER_HOME_PREFIX + username);
        newUser.put("created", FileUtil.getCurrentTimeArray());

        users.put(username, newUser);
        saveUsersConfig(config);

        // 创建 home 目录
        String homePath = Constants.USER_HOME_PREFIX + username;
        FileUtil.createDirectory(Constants.USER_HOME_PREFIX, username);
        // 将 home 目录的 Owner 改为用户自己（否则 Owner 是 local，用户无法写入）
        Map<String, Object> homeMeta = FileUtil.readDirectoryMetaData(homePath);
        if (homeMeta != null) {
            homeMeta.put("Owner", username);
            FileUtil.writeDirectoryMetaData(homePath, homeMeta);
        }
        // 创建 home 下的 app 目录（同样修改 Owner）
        String appPath = homePath + "/app";
        FileUtil.createDirectory(homePath, "app");
        Map<String, Object> appMeta = FileUtil.readDirectoryMetaData(appPath);
        if (appMeta != null) {
            appMeta.put("Owner", username);
            FileUtil.writeDirectoryMetaData(appPath, appMeta);
        }

        return "User created: " + username;
    }

    /**
     * 删除用户（不能删除 local）。
     */
    @SuppressWarnings("unchecked")
    public static String removeUser(String username, String password) {
        if (Constants.DEFAULT_USER_LOCAL.equals(username)) {
            return errorResult("Cannot remove local user");
        }
        if (!validateUser(username, password)) {
            return errorResult("Invalid credentials");
        }

        Map<String, Object> config = readUsersConfig();
        Map<String, Object> users = (Map<String, Object>) config.get("users");
        if (users == null || !users.containsKey(username)) {
            return errorResult("User not found: " + username);
        }

        users.remove(username);
        saveUsersConfig(config);

        // 删除 home 目录
        String homePath = Constants.USER_HOME_PREFIX + username;
        try {
            FileUtil.removeDirectory(homePath);
        } catch (Exception e) {
            Logger.warn("Failed to remove home directory for " + username + ": " + e.getMessage());
        }

        return "User removed: " + username;
    }

    /**
     * 切换当前用户。
     */
    public static String switchUser(String username, String password) {
        if (validateUser(username, password)) {
            setCurrentUser(username);
            return "Switched to user: " + username;
        }
        return errorResult("Invalid credentials for user: " + username);
    }

    /**
     * 检查当前用户是否为 local 用户。
     */
    public static boolean isLocal() {
        return Constants.DEFAULT_USER_LOCAL.equals(getCurrentUser());
    }

    /**
     * 从 users.json 获取当前用户。
     */
    @SuppressWarnings("unchecked")
    public static String getCurrentUserFromFile() {
        Map<String, Object> config = readUsersConfig();
        Object currentUser = config.get("currentUser");
        if (currentUser instanceof String) {
            return (String) currentUser;
        }
        return Constants.DEFAULT_USER_LOCAL;
    }

    /**
     * 获取所有用户的列表。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getListOfUsers() {
        Map<String, Object> config = readUsersConfig();
        Map<String, Object> users = (Map<String, Object>) config.get("users");
        if (users == null) return new LinkedHashMap<>();
        return users;
    }

    // ── 进程所有权 ──

    /**
     * 检查当前用户是否为进程的 Owner。
     * 直接读取进程文件（绕过 VFS API 以避免循环依赖）。
     */
    public static boolean checkProcessPermission(int pid) {
        String processPath = Constants.SYSTEM_PROCESS_PATH + pid + ".json";
        if (!FileUtil.exists(processPath)) return false;

        String content = FileUtil.read(processPath);
        if (content == null || content.trim().isEmpty()) return false;

        Map<String, Object> processData = JsonUtil.parseToMap(content);
        Object owner = processData.get("Owner");
        String currentUser = getCurrentUser();

        if (Constants.DEFAULT_USER_LOCAL.equals(currentUser)) return true;
        return owner instanceof String && owner.equals(currentUser);
    }

    // ── 默认配置 ──

    /**
     * 创建默认用户配置。
     */
    public static Map<String, Object> createDefaultUsersConfig() {
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

    // ── 辅助 ──

    private static String errorResult(String message) {
        return "ERROR: " + message;
    }
}
