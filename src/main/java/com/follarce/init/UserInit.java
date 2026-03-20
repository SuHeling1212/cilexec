package com.follarce.init;

import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.TimeUtil;

import java.util.*;

public class UserInit {

    private static final String USERS_FILE = "/system/config/users.json";

    /**
     * 获取用户列表
     *
     * @return Map<String, Object> 用户名 -> 用户信息
     */
    public static Map<String, Object> getListOfUsers() {
        String[] readResult = FileUtil.read(USERS_FILE);
        if (!readResult[0].equals("SUCCESS")) {
            return new HashMap<>();
        }

        Object obj = JsonUtil.readJson(readResult[1]);
        if (!(obj instanceof Map)) {
            return new HashMap<>();
        }

        Map<String, Object> data = (Map<String, Object>) obj;
        Object usersObj = data.get("users");
        if (!(usersObj instanceof Map)) {
            return new HashMap<>();
        }

        return (Map<String, Object>) usersObj;
    }

    /**
     * 创建用户
     *
     * @param username 用户名
     * @param password 密码
     * @param local    是否为 local 用户
     * @return String[] [状态, 错误码]
     */
    public static String[] createUser(String username, String password, boolean local) {
        if (username == null || username.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_USERNAME"};
        }

        if (password == null || password.isEmpty()) {
            return new String[]{"ERROR", "INVALID_PASSWORD"};
        }

        // 检查用户是否已存在
        if (userExists(username)) {
            return new String[]{"ERROR", "USER_EXISTS"};
        }

        // 读取现有用户列表
        Map<String, Object> users = getListOfUsers();

        // 创建用户信息
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("password", password);
        userInfo.put("isLocal", local);
        userInfo.put("home", "/user/" + username);
        userInfo.put("created", TimeUtil.getTime());

        // 添加到用户列表
        users.put(username, userInfo);

        // 保存
        Map<String, Object> data = new HashMap<>();
        data.put("users", users);

        String[] writeResult = FileUtil.write(USERS_FILE, JsonUtil.toJson(data));
        if (!writeResult[0].equals("SUCCESS")) {
            return new String[]{"ERROR", "SAVE_FAILED"};
        }

        // 创建用户 home 目录
        FileUtil.createDirectory("/user/", username);
        FileUtil.createDirectory("/user/" + username + "/", "app");

        return new String[]{"SUCCESS", null};
    }

    /**
     * 删除用户
     *
     * @param username 用户名
     * @param password 密码（验证）
     * @return String[] [状态, 错误码]
     */
    public static String[] removeUser(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_USERNAME"};
        }

        // 检查用户是否存在
        Map<String, Object> users = getListOfUsers();
        Object userObj = users.get(username);
        if (!(userObj instanceof Map)) {
            return new String[]{"ERROR", "USER_NOT_EXISTS"};
        }

        Map<String, Object> userInfo = (Map<String, Object>) userObj;

        // 验证密码
        String storedPassword = (String) userInfo.get("password");
        if (storedPassword == null || !storedPassword.equals(password)) {
            return new String[]{"ERROR", "INVALID_PASSWORD"};
        }

        // 不能删除 local 用户
        Boolean isLocal = (Boolean) userInfo.get("isLocal");
        if (isLocal != null && isLocal) {
            return new String[]{"ERROR", "CANNOT_REMOVE_LOCAL"};
        }

        // 从列表中移除
        users.remove(username);

        // 保存
        Map<String, Object> data = new HashMap<>();
        data.put("users", users);

        String[] writeResult = FileUtil.write(USERS_FILE, JsonUtil.toJson(data));
        if (!writeResult[0].equals("SUCCESS")) {
            return new String[]{"ERROR", "SAVE_FAILED"};
        }

        return new String[]{"SUCCESS", null};
    }

    /**
     * 检查用户是否存在
     *
     * @param username 用户名
     * @return boolean 是否存在
     */
    public static boolean userExists(String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }

        Map<String, Object> users = getListOfUsers();
        return users.containsKey(username);
    }

    /**
     * 验证用户密码
     *
     * @param username 用户名
     * @param password 密码
     * @return boolean 是否验证通过
     */
    public static boolean validateUser(String username, String password) {
        if (username == null || password == null) {
            return false;
        }

        Map<String, Object> users = getListOfUsers();
        Object userObj = users.get(username);
        if (!(userObj instanceof Map)) {
            return false;
        }

        Map<String, Object> userInfo = (Map<String, Object>) userObj;
        String storedPassword = (String) userInfo.get("password");

        return storedPassword != null && storedPassword.equals(password);
    }

    /**
     * 获取用户信息
     *
     * @param username 用户名
     * @return Map<String, Object> 用户信息，不存在返回 null
     */
    public static Map<String, Object> getUserInfo(String username) {
        Map<String, Object> users = getListOfUsers();
        Object userObj = users.get(username);
        if (userObj instanceof Map) {
            return (Map<String, Object>) userObj;
        }
        return null;
    }

    /**
     * 获取当前登录用户
     *
     * @return String 当前用户名，失败返回 null
     */
    public static String getCurrentUser() {
        String[] readResult = FileUtil.read(USERS_FILE);
        if (!readResult[0].equals("SUCCESS")) {
            return null;
        }

        Object obj = JsonUtil.readJson(readResult[1]);
        if (!(obj instanceof Map)) {
            return null;
        }

        Map<String, Object> data = (Map<String, Object>) obj;
        Object currentUser = data.get("currentUser");
        if (currentUser instanceof String) {
            return (String) currentUser;
        }
        return null;
    }

    /**
     * 切换当前用户
     *
     * @param username 用户名
     * @param password 密码
     * @return String[] [状态, 错误码]
     */
    public static String[] switchUser(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_USERNAME"};
        }

        // 验证用户存在
        if (!userExists(username)) {
            return new String[]{"ERROR", "USER_NOT_EXISTS"};
        }

        // 验证密码
        if (!validateUser(username, password)) {
            return new String[]{"ERROR", "INVALID_PASSWORD"};
        }

        // 读取完整数据
        String[] readResult = FileUtil.read(USERS_FILE);
        if (!readResult[0].equals("SUCCESS")) {
            return new String[]{"ERROR", "READ_FAILED"};
        }

        Object obj = JsonUtil.readJson(readResult[1]);
        if (!(obj instanceof Map)) {
            return new String[]{"ERROR", "INVALID_USER_DATA"};
        }

        Map<String, Object> data = (Map<String, Object>) obj;

        // 更新当前用户
        data.put("currentUser", username);

        // 保存
        String[] writeResult = FileUtil.write(USERS_FILE, JsonUtil.toJson(data));
        if (!writeResult[0].equals("SUCCESS")) {
            return new String[]{"ERROR", "SAVE_FAILED"};
        }

        return new String[]{"SUCCESS", null};
    }

    /**
     * 检查当前用户是否是 local
     *
     * @return boolean 是否是 local 用户
     */
    public static boolean isLocal() {
        String currentUser = getCurrentUser();
        if (currentUser == null) {
            return false;
        }

        Map<String, Object> userInfo = getUserInfo(currentUser);
        if (userInfo == null) {
            return false;
        }

        Boolean isLocal = (Boolean) userInfo.get("isLocal");
        return isLocal != null && isLocal;
    }
}
