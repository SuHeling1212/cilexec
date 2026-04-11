package com.follarce.init;

import com.follarce.basicUtil.FileUtil;
import com.follarce.basicUtil.JsonUtil;
import com.follarce.basicUtil.TimeUtil;

import java.util.*;

public class UserInit {

    private static final String USERS_FILE = "/system/config/users.json";

    /**
     * Get user list
     *
     * @return Map<String, Object> username -> user info
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
     * Create user
     *
     * @param username Username
     * @param password Password
     * @param local    Whether it is a local user
     * @return String[] [status, error code]
     */
    public static String[] createUser(String username, String password, boolean local) {
        if (username == null || username.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_USERNAME" };
        }

        if (password == null || password.isEmpty()) {
            return new String[] { "ERROR", "INVALID_PASSWORD" };
        }

        // Check if user already exists
        if (userExists(username)) {
            return new String[] { "ERROR", "USER_EXISTS" };
        }

        // Read existing user list
        Map<String, Object> users = getListOfUsers();

        // Create user info
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("password", password);
        userInfo.put("isLocal", local);
        userInfo.put("home", "/user/" + username);
        userInfo.put("created", TimeUtil.getTime());

        // Add to user list
        users.put(username, userInfo);

        // Save
        Map<String, Object> data = new HashMap<>();
        data.put("users", users);

        String[] writeResult = FileUtil.write(USERS_FILE, JsonUtil.toJson(data));
        if (!writeResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SAVE_FAILED" };
        }

        // Create user home directory
        FileUtil.createDirectory("/user/", username);
        FileUtil.createDirectory("/user/" + username + "/", "app");

        return new String[] { "SUCCESS", null };
    }

    /**
     * Remove user
     *
     * @param username Username
     * @param password Password (for verification)
     * @return String[] [status, error code]
     */
    public static String[] removeUser(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_USERNAME" };
        }

        // Check if user exists
        Map<String, Object> users = getListOfUsers();
        Object userObj = users.get(username);
        if (!(userObj instanceof Map)) {
            return new String[] { "ERROR", "USER_NOT_EXISTS" };
        }

        Map<String, Object> userInfo = (Map<String, Object>) userObj;

        // Verify password
        String storedPassword = (String) userInfo.get("password");
        if (storedPassword == null || !storedPassword.equals(password)) {
            return new String[] { "ERROR", "INVALID_PASSWORD" };
        }

        // Cannot delete local user
        Boolean isLocal = (Boolean) userInfo.get("isLocal");
        if (isLocal != null && isLocal) {
            return new String[] { "ERROR", "CANNOT_REMOVE_LOCAL" };
        }

        // Remove from list
        users.remove(username);

        // Save
        Map<String, Object> data = new HashMap<>();
        data.put("users", users);

        String[] writeResult = FileUtil.write(USERS_FILE, JsonUtil.toJson(data));
        if (!writeResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SAVE_FAILED" };
        }

        return new String[] { "SUCCESS", null };
    }

    /**
     * Check if user exists
     *
     * @param username Username
     * @return boolean Whether exists
     */
    public static boolean userExists(String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }

        Map<String, Object> users = getListOfUsers();
        return users.containsKey(username);
    }

    /**
     * Validate user password
     *
     * @param username Username
     * @param password Password
     * @return boolean Whether validation passed
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
     * Get user info
     *
     * @param username Username
     * @return Map<String, Object> User info, returns null if not exists
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
     * Get current logged-in user
     *
     * @return String Current username
     */
    public static String getCurrentUser() {
        return getCurrentUserFromFile();
    }

    public static String getCurrentUserFromFile() {
        String[] readResult = FileUtil.read(USERS_FILE);
        if (!readResult[0].equals("SUCCESS")) {
            return "local";
        }

        Object obj = JsonUtil.readJson(readResult[1]);
        if (!(obj instanceof Map)) {
            return "local";
        }

        Map<String, Object> data = (Map<String, Object>) obj;
        Object currentUserObj = data.get("currentUser");
        if (currentUserObj instanceof String) {
            return (String) currentUserObj;
        }
        return "local";
    }

    public static String[] switchUser(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_USERNAME" };
        }

        if (password == null || password.isEmpty()) {
            return new String[] { "ERROR", "INVALID_PASSWORD" };
        }

        if (!userExists(username)) {
            return new String[] { "ERROR", "USER_NOT_EXISTS" };
        }

        if (!validateUser(username, password)) {
            return new String[] { "ERROR", "INVALID_PASSWORD" };
        }

        String[] readResult = FileUtil.read(USERS_FILE);
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "FILE_READ_FAILED" };
        }

        Object obj = JsonUtil.readJson(readResult[1]);
        if (!(obj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_FILE_FORMAT" };
        }

        Map<String, Object> data = (Map<String, Object>) obj;
        data.put("currentUser", username);

        String[] writeResult = FileUtil.write(USERS_FILE, JsonUtil.toJson(data));
        if (!writeResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SAVE_FAILED" };
        }

        return new String[] { "SUCCESS", null };
    }

    /**
     * Check if current user is local
     *
     * @return boolean Whether is local user
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

    /**
     * Dispatch function calls from script engine
     */
    public static Object call(String name, Object[] args) {
        switch (name) {
            // User creation and management
            case "createUser":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "USERNAME_MUST_BE_STRING" };
                }
                if (args.length < 2 || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "PASSWORD_MUST_BE_STRING" };
                }
                if (args.length < 3 || !(args[2] instanceof Boolean)) {
                    return new String[] { "ERROR", "ISLOCAL_MUST_BE_BOOLEAN" };
                }
                if (args.length > 3) {
                    return new String[] { "ERROR", "TOO_MANY_ARGUMENTS" };
                }
                return createUser((String) args[0], (String) args[1], (Boolean) args[2]);

            case "removeUser":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "USERNAME_MUST_BE_STRING" };
                }
                if (args.length < 2 || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "PASSWORD_MUST_BE_STRING" };
                }
                if (args.length > 2) {
                    return new String[] { "ERROR", "TOO_MANY_ARGUMENTS" };
                }
                return removeUser((String) args[0], (String) args[1]);

            case "userExists":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "USERNAME_MUST_BE_STRING" };
                }
                if (args.length > 1) {
                    return new String[] { "ERROR", "TOO_MANY_ARGUMENTS" };
                }
                return userExists((String) args[0]);

            case "validateUser":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "USERNAME_MUST_BE_STRING" };
                }
                if (args.length < 2 || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "PASSWORD_MUST_BE_STRING" };
                }
                if (args.length > 2) {
                    return new String[] { "ERROR", "TOO_MANY_ARGUMENTS" };
                }
                return validateUser((String) args[0], (String) args[1]);

            case "switchUser":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "USERNAME_MUST_BE_STRING" };
                }
                if (args.length < 2 || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "PASSWORD_MUST_BE_STRING" };
                }
                if (args.length > 2) {
                    return new String[] { "ERROR", "TOO_MANY_ARGUMENTS" };
                }
                return switchUser((String) args[0], (String) args[1]);

            // User info
            case "getCurrentUser":
                if (args.length > 0) {
                    return new String[] { "ERROR", "TOO_MANY_ARGUMENTS" };
                }
                return getCurrentUser();

            case "isLocal":
                if (args.length > 0) {
                    return new String[] { "ERROR", "TOO_MANY_ARGUMENTS" };
                }
                return isLocal();

            case "getListOfUsers":
                if (args.length > 0) {
                    return new String[] { "ERROR", "TOO_MANY_ARGUMENTS" };
                }
                return getListOfUsers();

            default:
                return new String[] { "ERROR", "UNKNOWN_FUNCTION" };
        }
    }
}
