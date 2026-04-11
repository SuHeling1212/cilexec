package com.follarce.basicUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import com.follarce.init.UserInit;

public class FileUtil {
    private static String VFS_ROOT = null; // Initially unknown
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger("FileUtil");

    /**
     * Check if path character is valid (whitelist validation)
     * Only allows letters, digits, underscores, hyphens, and dots
     */
    private static boolean isValidPathCharacter(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if filename is valid (cannot start with ., must pass whitelist
     * validation)
     */
    private static boolean isValidName(String name) {
        return name != null && !name.trim().isEmpty() && !name.startsWith(".") && isValidPathCharacter(name);
    }

    /**
     * Check if file is a link file, if so return target path (supports chain links)
     */
    private static String getLinkTarget(String realPath) {
        try {
            File file = new File(realPath);
            if (!file.exists() || file.isDirectory()) {
                return null;
            }

            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String[] metaResult = extractMetaContent(fullContent);

            if (metaResult[0].equals("SUCCESS")) {
                Object metaObj = JsonUtil.readJson(metaResult[1]);
                if (metaObj instanceof Map) {
                    Map<String, Object> metaMap = (Map<String, Object>) metaObj;
                    Object linkTargetObj = metaMap.get("Link");
                    if (linkTargetObj instanceof String) {
                        String linkTarget = (String) linkTargetObj;
                        if (linkTarget != null && !linkTarget.isEmpty()) {
                            // If relative path, based on current file directory
                            if (!linkTarget.startsWith("/")) {
                                String fileDir = realPath.substring(0, realPath.lastIndexOf(File.separator) + 1);
                                return fileDir + linkTarget.replace('/', File.separatorChar);
                            }
                            // If absolute path, add VFS root directory
                            return getVfsRoot() + linkTarget.replace('/', File.separatorChar);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to read link file: " + realPath + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * Recursively resolve link file (supports chain links, detects cycles)
     */
    private static String resolveLinkRecursive(String realPath, Set<String> visited) {
        if (visited.contains(realPath)) {
            LOGGER.warning("Detected cyclic link: " + realPath);
            return null; // Detected cyclic link
        }
        visited.add(realPath);

        String linkTarget = getLinkTarget(realPath);
        if (linkTarget != null) {
            return resolveLinkRecursive(linkTarget, visited);
        }
        return realPath;
    }

    /**
     * Get real path (handle link files, supports chain links)
     */
    private static String resolveLink(String realPath) {
        Set<String> visited = new HashSet<>();
        String result = resolveLinkRecursive(realPath, visited);
        return result != null ? result : realPath; // If error, return original path
    }

    /**
     * Normalize path (supports Windows backslash, handles .. and .)
     */
    static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        // Replace backslashes with forward slashes
        String unified = path.replace('\\', '/');
        String[] parts = unified.split("/");
        Stack<String> stack = new Stack<>();

        for (String part : parts) {
            if (part.equals("") || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
            } else {
                // Security check: validate each path component against whitelist
                if (!isValidPathCharacter(part)) {
                    LOGGER.warning("Invalid path component detected: " + part);
                    return "/";
                }
                stack.push(part);
            }
        }

        StringBuilder result = new StringBuilder();
        for (String part : stack) {
            result.append("/").append(part);
        }

        // Handle root path
        if (result.length() == 0) {
            return "/";
        }

        return result.toString();
    }

    /**
     * Validate file path and return File object
     *
     * @param path           Virtual path
     * @param checkParentDir Whether to check if parent directory exists
     * @param needExist      Whether file needs to exist
     * @param operation      Operation type (read/write/execute), null to skip
     *                       permission check
     * @return [File object, error info] Returns null and error code if error
     */
    private static Object[] validateFile(String path, boolean checkParentDir, boolean needExist, String operation) {
        if (path == null || path.trim().isEmpty()) {
            return new Object[] { null, new String[] { "ERROR", "INVALID_PATH" } };
        }

        // Resolve path aliases (e.g., ~ -> /user/local)
        String resolvedPath = PathUtil.resolvePath(path);

        String root = getVfsRoot();
        String normalized = normalizePath(resolvedPath);

        // Handle root path special case
        if (normalized.equals("/")) {
            File rootFile = new File(root);
            return new Object[] { rootFile, null };
        }

        // Security check: ensure normalized path doesn't escape VFS root
        if (normalized.contains("..")) {
            return new Object[] { null, new String[] { "ERROR", "INVALID_PATH" } };
        }

        String realPath = root + normalized.replace('/', File.separatorChar);

        // Double check: ensure final path is within VFS root
        File checkFile = new File(realPath);
        try {
            String canonicalPath = checkFile.getCanonicalPath();
            String canonicalRoot = new File(root).getCanonicalPath();
            if (!canonicalPath.startsWith(canonicalRoot)) {
                return new Object[] { null, new String[] { "ERROR", "PATH_TRAVERSAL_DETECTED" } };
            }
        } catch (IOException e) {
            return new Object[] { null, new String[] { "ERROR", "INVALID_PATH" } };
        }

        // Resolve link
        String targetPath = resolveLink(realPath);
        File file = new File(targetPath);

        // Check parent directory
        if (checkParentDir) {
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                return new Object[] { null, new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" } };
            }
        }

        // Check if file exists
        if (needExist && !file.exists()) {
            return new Object[] { null, new String[] { "ERROR", "FILE_DOES_NOT_EXIST" } };
        }

        // Check if it's a file (error if it's a directory)
        if (needExist && file.isDirectory()) {
            return new Object[] { null, new String[] { "ERROR", "IS_NOT_FILE" } };
        }

        // Check permission
        if (operation != null && needExist) {
            if (!UserUtil.checkFilePermission(path, operation)) {
                return new Object[] { null, new String[] { "ERROR", "INSUFFICIENT_PERMISSION" } };
            }
        }

        return new Object[] { file, null };
    }

    /**
     * Validate file path and return File object (backward compatible, no permission
     * check)
     */
    private static Object[] validateFile(String path, boolean checkParentDir, boolean needExist) {
        return validateFile(path, checkParentDir, needExist, null);
    }

    /**
     * Check if a process exists by checking if its process file exists
     */
    private static boolean isProcessExists(int pid) {
        String processPath = "/system/process/" + pid + ".json";
        String root = getVfsRoot();
        File processFile = new File(root + processPath.replace('/', File.separatorChar));
        return processFile.exists();
    }

    /**
     * Check if directory is locked, auto-unlock if locker process is dead
     */
    private static String[] checkDirectoryLock(File dir) {
        return checkAndValidateLock(dir, true);
    }

    /**
     * Create link file
     *
     * @param path       Directory path where link file is stored (ends with /)
     * @param sourcePath Source file or directory path
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] Link(String path, String sourcePath) {
        // 1. Check path
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. Check source path
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_SOURCE_PATH" };
        }

        // 3. Ensure path ends with /
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 4. Get root directory
        String root = getVfsRoot();

        // 5. Convert source path to real path, check if source file/directory exists
        String normalizedSource = normalizePath(sourcePath);
        String realSourcePath = root + normalizedSource.replace('/', File.separatorChar);
        File sourceFile = new File(realSourcePath);

        if (!sourceFile.exists()) {
            return new String[] { "ERROR", "SOURCE_FILE_DOES_NOT_EXIST" };
        }

        // 6. Extract filename from source path (link file name is same as source file
        // name)
        String sourceName = sourcePath;
        int lastSlash = sourcePath.lastIndexOf('/');
        if (lastSlash >= 0) {
            sourceName = sourcePath.substring(lastSlash + 1);
        }
        // If extracted result is empty string, source path ends with /, need to look
        // further
        if (sourceName.isEmpty()) {
            String pathWithoutTrailingSlash = sourcePath.substring(0, sourcePath.length() - 1);
            lastSlash = pathWithoutTrailingSlash.lastIndexOf('/');
            if (lastSlash >= 0) {
                sourceName = pathWithoutTrailingSlash.substring(lastSlash + 1);
            } else {
                sourceName = pathWithoutTrailingSlash;
            }
        }
        // If it's a directory and name ends with /, remove it
        if (sourceName.endsWith("/")) {
            sourceName = sourceName.substring(0, sourceName.length() - 1);
        }

        // Check if filename is valid
        if (!isValidName(sourceName)) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        // 7. Convert link directory path
        String normalized = normalizePath(path);
        String realDirPath = root + normalized.replace('/', File.separatorChar);

        // 8. Check if directory exists
        File dir = new File(realDirPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // Check if directory is locked
        String[] lockCheck = checkDirectoryLock(dir);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 9. Full link file path (name is same as source file)
        String realLinkPath = realDirPath + File.separator + sourceName;
        File linkFile = new File(realLinkPath);

        // 10. Check if link file already exists
        if (linkFile.exists()) {
            return new String[] { "ERROR", "FILE_EXIST" };
        }

        try {
            // 11. Get current time
            int[] now = TimeUtil.getTime();

            // 12. Create link file metadata
            Map<String, Object> metaMap = new HashMap<>();

            // Add link target (store original path for easy relative path handling)
            metaMap.put("Link", sourcePath);

            // Add time info
            Map<String, Object> timeMap = new HashMap<>();
            timeMap.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            timeMap.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            metaMap.put("Time", timeMap);

            // Add owner (use current user)
            String currentUser = com.follarce.init.UserInit.getCurrentUser();
            if (currentUser == null) {
                currentUser = "local";
            }
            metaMap.put("Owner", currentUser);

            // Add size
            metaMap.put("Size", new Object[] { 0, "B" });

            // Add permissions
            Map<String, String> permMap = new HashMap<>();
            permMap.put("Owner", "read, write");
            permMap.put("Others", "read");
            metaMap.put("Permission", permMap);

            // Add lock status
            Map<String, Object> lockMap = new HashMap<>();
            lockMap.put("isLocked", false);
            lockMap.put("lockedBy", null);
            metaMap.put("locked", lockMap);

            // 13. Convert to JSON
            String metaJson = JsonUtil.toJson(metaMap);

            // 14. Create link file content
            String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";

            // 15. Write file
            Files.write(linkFile.toPath(), fileContent.getBytes());

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("Failed to create link file: " + e.getMessage());
            return new String[] { "ERROR", "CREATE_LINK_FAILED" };
        }
    }

    /**
     * Rename file or directory
     *
     * @param path    Source path (file or directory)
     * @param newName New name
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] Rename(String path, String newName) {
        // 1. Check if new name is valid
        if (!isValidName(newName)) {
            return new String[] { "ERROR", "INVALID_NEW_NAME" };
        }

        // 2. Validate new name (cannot contain special characters)
        if (newName.contains("/") || newName.contains("\\") || newName.contains("..")) {
            return new String[] { "ERROR", "INVALID_NEW_NAME" };
        }

        // 3. Check path
        Object[] validateResult = validateFile(path, true, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File source = (File) validateResult[0];

        // 4. If it's a directory, check if directory is locked
        if (source.isDirectory()) {
            String[] lockCheck = checkDirectoryLock(source);
            if (lockCheck != null) {
                return lockCheck;
            }
        }

        // 5. If it's a file, check if it's locked
        if (source.isFile()) {
            String[] lockCheck = checkLock(source);
            if (lockCheck != null) {
                return lockCheck;
            }
        }

        // 6. Get parent directory and target path
        File parentDir = source.getParentFile();
        if (parentDir == null) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // Check if parent directory is locked
        String[] parentLockCheck = checkDirectoryLock(parentDir);
        if (parentLockCheck != null) {
            return parentLockCheck;
        }

        File target = new File(parentDir, newName);

        // 7. Check if target already exists
        if (target.exists()) {
            return new String[] { "ERROR", "FILE_EXIST" };
        }

        // 8. Execute rename
        boolean renamed = source.renameTo(target);
        if (renamed) {
            return new String[] { "SUCCESS", null };
        } else {
            return new String[] { "ERROR", "RENAME_FAILED" };
        }
    }

    /**
     * Check if file is locked, auto-unlock if locker process is dead
     */
    private static String[] checkLock(File file) {
        return checkAndValidateLock(file, false);
    }

    /**
     * Create directory metadata file
     */
    private static void createDirectoryMetaData(File dir) throws IOException {
        File metaFile = new File(dir, ".META");
        if (metaFile.exists()) {
            return;
        }

        int[] now = TimeUtil.getTime();

        Map<String, Object> metaMap = new HashMap<>();

        // Time info
        Map<String, Object> timeMap = new HashMap<>();
        timeMap.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
        timeMap.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
        timeMap.put("lastOpenTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
        metaMap.put("Time", timeMap);

        // Owner (use current user)
        String currentUser = com.follarce.init.UserInit.getCurrentUser();
        if (currentUser == null) {
            currentUser = "local";
        }
        metaMap.put("Owner", currentUser);

        // Permissions
        Map<String, String> permMap = new HashMap<>();
        permMap.put("Owner", "read, write");
        permMap.put("Others", "read");
        metaMap.put("Permission", permMap);

        // Lock status
        Map<String, Object> lockMap = new HashMap<>();
        lockMap.put("isLocked", false);
        lockMap.put("lockedBy", null);
        metaMap.put("locked", lockMap);

        String metaJson = JsonUtil.toJson(metaMap);
        String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";
        Files.write(metaFile.toPath(), fileContent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Delete empty directory
     *
     * @param path Directory path (ends with /)
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] removeDirectory(String path) {
        // 1. Check path
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. Ensure path ends with /
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 3. Get root directory
        String root = getVfsRoot();

        // 4. Convert to real path
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 5. Check if directory exists
        File dir = new File(realPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 6. Check if it's a directory
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 7. Check write permission on parent directory
        String parentPath = path.substring(0, path.lastIndexOf('/', path.length() - 2) + 1);
        String[] permCheck = checkAndValidatePermission(parentPath, "write");
        if (permCheck != null) {
            return permCheck;
        }

        // 8. Check if directory is locked
        String[] lockCheck = checkDirectoryLock(dir);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 8. Check if directory is empty (ignore .META file)
        String[] files = dir.list();
        if (files != null) {
            // Filter out .META file
            int fileCount = 0;
            for (String f : files) {
                if (!f.equals(".META")) {
                    fileCount++;
                }
            }
            if (fileCount > 0) {
                return new String[] { "ERROR", "DIRECTORY_IS_NOT_EMPTY" };
            }
        }

        // 9. First delete .META file (if exists)
        File metaFile = new File(dir, ".META");
        if (metaFile.exists()) {
            metaFile.delete();
        }

        // 10. Delete directory
        boolean deleted = dir.delete();
        if (deleted) {
            return new String[] { "SUCCESS", null };
        } else {
            return new String[] { "ERROR", "DELETE_FAILED" };
        }
    }

    /**
     * Create directory
     *
     * @param path Directory path (ends with /)
     * @param name Directory name
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] createDirectory(String path, String name) {
        // 1. Check path
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. Check if directory name is valid
        if (!isValidName(name)) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        // 3. Validate directory name (cannot contain special characters)
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        // 4. Ensure path ends with /
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 5. Resolve path aliases (e.g., ~ -> /user/local)
        path = PathUtil.resolvePath(path);

        // 6. Get root directory
        String root = getVfsRoot();

        // 6. Convert to real path (parent directory path)
        String normalized = normalizePath(path);
        String realParentPath = root + normalized.replace('/', File.separatorChar);

        // 7. Check if parent directory exists
        File parentDir = new File(realParentPath);
        if (!parentDir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 8. Check if parent directory is a directory
        if (!parentDir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 9. Check write permission on parent directory
        String[] permCheck = checkAndValidatePermission(path, "write");
        if (permCheck != null) {
            return permCheck;
        }

        // 10. Check if parent directory is locked
        String[] parentLockCheck = checkDirectoryLock(parentDir);
        if (parentLockCheck != null) {
            return parentLockCheck;
        }

        // 10. Full directory path
        String realDirPath = realParentPath + File.separator + name;
        File newDir = new File(realDirPath);

        // 11. Check if directory already exists
        if (newDir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_EXIST" };
        }

        // 12. Create directory
        boolean created = newDir.mkdir();
        if (created) {
            try {
                // 13. Create directory metadata file
                createDirectoryMetaData(newDir);
                return new String[] { "SUCCESS", null };
            } catch (IOException e) {
                LOGGER.warning("Failed to create directory metadata: " + e.getMessage());
                return new String[] { "SUCCESS", null };
            }
        } else {
            return new String[] { "ERROR", "CREATE_FAILED" };
        }
    }

    /**
     * Delete file
     *
     * @param path Full file path
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] removeFile(String path) {
        // 1. Validate file with write permission check
        Object[] validateResult = validateFile(path, true, true, "write");
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        // 2. Check lock status
        String[] lockCheck = checkLock(file);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 3. Check if parent directory is locked
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            String[] parentLockCheck = checkDirectoryLock(parentDir);
            if (parentLockCheck != null) {
                return parentLockCheck;
            }
        }

        // 4. Delete file
        try {
            boolean deleted = file.delete();
            if (deleted) {
                return new String[] { "SUCCESS", null };
            } else {
                return new String[] { "ERROR", "DELETE_FAILED" };
            }
        } catch (SecurityException e) {
            LOGGER.warning("Failed to delete file: " + e.getMessage());
            return new String[] { "ERROR", "DELETE_FAILED" };
        }
    }

    /**
     * Create file
     *
     * @param path Directory path (ends with /)
     * @param name File name
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] createFile(String path, String name) {
        // 1. Check path
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. Check if filename is valid
        if (!isValidName(name)) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        // 3. Validate filename (cannot contain special characters)
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        // 4. Ensure path ends with /
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 5. Resolve path aliases (e.g., ~ -> /user/local)
        path = PathUtil.resolvePath(path);

        // 6. Get root directory
        String root = getVfsRoot();

        // 7. Convert to real path (directory path)
        String normalized = normalizePath(path);
        String realDirPath = root + normalized.replace('/', File.separatorChar);

        // 8. Check if directory exists
        File dir = new File(realDirPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 8. Check if it's a directory
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 9. Check write permission on parent directory
        String[] permCheck = checkAndValidatePermission(path, "write");
        if (permCheck != null) {
            return permCheck;
        }

        // 10. Check if directory is locked
        String[] lockCheck = checkDirectoryLock(dir);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 10. Full file path
        String realFilePath = realDirPath + File.separator + name;
        File newFile = new File(realFilePath);

        // 11. Check if file already exists
        if (newFile.exists()) {
            return new String[] { "ERROR", "FILE_EXIST" };
        }

        try {
            // 12. Get current time
            int[] now = TimeUtil.getTime();

            // 13. Create default metadata
            Map<String, Object> metaMap = new HashMap<>();

            // Add time info
            Map<String, Object> timeMap = new HashMap<>();
            timeMap.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            timeMap.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            timeMap.put("lastOpenTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            metaMap.put("Time", timeMap);

            // Add owner (use current user)
            String currentUser = UserInit.getCurrentUser();
            metaMap.put("Owner", currentUser != null ? currentUser : "local");

            // Add default permissions
            Map<String, String> permMap = new HashMap<>();
            permMap.put("Owner", "read, write");
            permMap.put("Others", "read");
            metaMap.put("Permission", permMap);

            // Add lock status
            Map<String, Object> lockMap = new HashMap<>();
            lockMap.put("isLocked", false);
            lockMap.put("lockedBy", null);
            metaMap.put("locked", lockMap);

            // Add size (initially 0)
            metaMap.put("Size", new Object[] { 0, "B" });

            // 14. Convert to JSON
            String metaJson = JsonUtil.toJson(metaMap);

            // 15. Create file content (only metadata, body is empty)
            String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";

            // 16. Write file
            Files.write(newFile.toPath(), fileContent.getBytes());

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("Failed to create file: " + e.getMessage());
            return new String[] { "ERROR", "CREATE_FAILED" };
        }
    }

    /**
     * Write file metadata
     *
     * @param path    File path
     * @param content New metadata content (JSON format)
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] writeFileMetaData(String path, String content) {
        // 1. Validate file
        Object[] validateResult = validateFile(path, true, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        // 2. Check metadata content
        if (content == null) {
            content = "{}";
        }

        // 3. Validate JSON format
        if (!JsonUtil.isValidJson(content)) {
            return new String[] { "ERROR", "INVALID_JSON" };
        }

        // 4. Check if parent directory is locked
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            String[] parentLockCheck = checkDirectoryLock(parentDir);
            if (parentLockCheck != null) {
                return parentLockCheck;
            }
        }

        try {
            // 5. Read existing file content (to get body)
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String bodyContent = extractBodyContent(fullContent);

            // 6. Check lock status
            String[] lockCheck = checkLock(file);
            if (lockCheck != null) {
                return lockCheck;
            }

            // 7. Parse new metadata and ensure time field exists
            Object newMetaObj = JsonUtil.readJson(content);
            Map<String, Object> newMetaMap;

            if (newMetaObj instanceof Map) {
                newMetaMap = (Map<String, Object>) newMetaObj;
            } else {
                newMetaMap = new HashMap<>();
            }

            // 8. Update time field
            Map<String, Object> time = (Map<String, Object>) newMetaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                newMetaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // 9. If createTime exists, keep it; otherwise add it
            if (!time.containsKey("createTime")) {
                time.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            }

            // 10. Ensure locked field exists
            if (!newMetaMap.containsKey("locked")) {
                Map<String, Object> lockMap = new HashMap<>();
                lockMap.put("isLocked", false);
                lockMap.put("lockedBy", null);
                newMetaMap.put("locked", lockMap);
            }

            // 11. Reassemble file
            String newMetaJson = JsonUtil.toJson(newMetaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + bodyContent;

            // 12. Write back to file
            Files.write(file.toPath(), newFullContent.getBytes());

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("Failed to write metadata: " + e.getMessage());
            return new String[] { "ERROR", "WRITE_FAILED" };
        }
    }

    /**
     * Read file metadata
     *
     * @param path File path
     * @return String[] Array, [0] is status, [1] is metadata JSON (returns "{}" if
     *         no metadata)
     */
    public static String[] readFileMetaData(String path) {
        // 1. Validate file
        Object[] validateResult = validateFile(path, true, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        try {
            // 2. Read file content
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // 3. Extract metadata
            String[] metaResult = extractMetaContent(fullContent);

            if (metaResult[0].equals("SUCCESS")) {
                // Has metadata, return directly
                return new String[] { "SUCCESS", metaResult[1] };
            } else {
                // No metadata, return empty object
                return new String[] { "SUCCESS", "{}" };
            }

        } catch (IOException e) {
            LOGGER.warning("Failed to read metadata: " + e.getMessage());
            return new String[] { "ERROR", "READ_FAILED" };
        }
    }

    /**
     * Read directory metadata
     *
     * @param path Directory path (ends with /)
     * @return String[] Array, [0] is status, [1] is metadata JSON (returns "{}" if
     *         no metadata)
     */
    public static String[] readDirectoryMetaData(String path) {
        // 1. Check path
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. Ensure path ends with /
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 3. Get root directory
        String root = getVfsRoot();

        // 4. Convert to real path
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 5. Check if directory exists
        File dir = new File(realPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 6. Check if it's a directory
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 7. Metadata file path
        File metaFile = new File(dir, ".META");

        // 8. Check if metadata file exists
        if (!metaFile.exists()) {
            return new String[] { "ERROR", "META_DATA_FILE_DOES_NOT_EXIST" };
        }

        try {
            String fullContent = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
            String[] metaResult = extractMetaContent(fullContent);

            if (metaResult[0].equals("SUCCESS")) {
                return new String[] { "SUCCESS", metaResult[1] };
            } else {
                return new String[] { "ERROR", "INVALID_META_FORMAT" };
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to read directory metadata: " + e.getMessage());
            return new String[] { "ERROR", "READ_FAILED" };
        }
    }

    /**
     * Write directory metadata
     *
     * @param path    Directory path (ends with /)
     * @param content New metadata content (JSON format)
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] writeDirectoryMetaData(String path, String content) {
        // 1. Check path
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. Ensure path ends with /
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 3. Check metadata content
        if (content == null) {
            content = "{}";
        }

        // 4. Validate JSON format
        if (!JsonUtil.isValidJson(content)) {
            return new String[] { "ERROR", "INVALID_JSON" };
        }

        // 5. Get root directory
        String root = getVfsRoot();

        // 6. Convert to real path
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 7. Check if directory exists
        File dir = new File(realPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 8. Check if it's a directory
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 9. Check if directory is locked
        String[] lockCheck = checkDirectoryLock(dir);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 10. Metadata file path
        File metaFile = new File(dir, ".META");

        // 11. Check if metadata file exists
        if (!metaFile.exists()) {
            return new String[] { "ERROR", "META_DATA_FILE_DOES_NOT_EXIST" };
        }

        try {
            // 12. Parse new metadata and ensure time field exists
            Object newMetaObj = JsonUtil.readJson(content);
            Map<String, Object> newMetaMap;

            if (newMetaObj instanceof Map) {
                newMetaMap = (Map<String, Object>) newMetaObj;
            } else {
                newMetaMap = new HashMap<>();
            }

            // 13. Update time field
            Map<String, Object> time = (Map<String, Object>) newMetaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                newMetaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // 14. If createTime exists, keep it; otherwise add it
            if (!time.containsKey("createTime")) {
                time.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            }

            // 15. Ensure locked field exists
            if (!newMetaMap.containsKey("locked")) {
                Map<String, Object> lockMap = new HashMap<>();
                lockMap.put("isLocked", false);
                lockMap.put("lockedBy", null);
                newMetaMap.put("locked", lockMap);
            }

            // 16. Reassemble file
            String newMetaJson = JsonUtil.toJson(newMetaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n";

            // 17. Write back to file
            Files.write(metaFile.toPath(), newFullContent.getBytes(StandardCharsets.UTF_8));

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("Failed to write directory metadata: " + e.getMessage());
            return new String[] { "ERROR", "WRITE_FAILED" };
        }
    }

    /**
     * Create directory metadata file
     *
     * @param path Directory path (ends with /)
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] createDirectoryMetaData(String path) {
        // 1. Check path
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. Ensure path ends with /
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 3. Get root directory
        String root = getVfsRoot();

        // 4. Convert to real path
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 5. Check if directory exists
        File dir = new File(realPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 6. Check if it's a directory
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 7. Check if directory is locked
        String[] lockCheck = checkDirectoryLock(dir);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 8. Metadata file path
        File metaFile = new File(dir, ".META");

        // 9. Check if metadata file already exists
        if (metaFile.exists()) {
            return new String[] { "ERROR", "FILE_EXIST" };
        }

        try {
            // 10. Get current time
            int[] now = TimeUtil.getTime();

            // 11. Create metadata
            Map<String, Object> metaMap = new HashMap<>();

            // Time info
            Map<String, Object> timeMap = new HashMap<>();
            timeMap.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            timeMap.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            timeMap.put("lastOpenTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            metaMap.put("Time", timeMap);

            // Owner
            metaMap.put("Owner", "local");

            // Permissions
            Map<String, String> permMap = new HashMap<>();
            permMap.put("Owner", "read, write");
            permMap.put("Others", "read");
            metaMap.put("Permission", permMap);

            // Lock status
            Map<String, Object> lockMap = new HashMap<>();
            lockMap.put("isLocked", false);
            lockMap.put("lockedBy", null);
            metaMap.put("locked", lockMap);

            // 12. Convert to JSON
            String metaJson = JsonUtil.toJson(metaMap);

            // 13. Create file content
            String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";

            // 14. Write file
            Files.write(metaFile.toPath(), fileContent.getBytes(StandardCharsets.UTF_8));

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("Failed to create directory metadata: " + e.getMessage());
            return new String[] { "ERROR", "CREATE_FAILED" };
        }
    }

    /**
     * Get list of files and directories under directory
     *
     * @param path Directory path (ends with /)
     * @return String[] Array, [0] is status, [1...] are filenames/directory names
     */
    public static String[] getListOfFileAndDirectory(String path) {
        // 1. Check path
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. Ensure path ends with /
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 3. Get root directory
        String root = getVfsRoot();

        // 4. Convert to real path
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 5. Check if directory exists
        File dir = new File(realPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 6. Check if it's a directory
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 7. Get all files under directory
        File[] files = dir.listFiles();
        if (files == null) {
            return new String[] { "SUCCESS" }; // Empty directory
        }

        // 8. Collect filenames and directory names (filter out .META file)
        java.util.List<String> items = new java.util.ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            // Don't show .META file
            if (name.equals(".META")) {
                continue;
            }
            if (f.isDirectory()) {
                items.add(name + "/"); // Add / after directory
            } else {
                items.add(name);
            }
        }

        // 9. Sort alphabetically
        java.util.Collections.sort(items);

        // 10. Construct return array
        String[] result = new String[items.size() + 1];
        result[0] = "SUCCESS";
        for (int i = 0; i < items.size(); i++) {
            result[i + 1] = items.get(i);
        }

        return result;
    }

    /**
     * Write file with character-level incremental update
     * Only writes the parts that actually changed to extend disk life
     *
     * @param path    File path
     * @param content Content to write
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    //May be changed to a more performance-efficient approach in the future
    public static String[] write(String path, String content) {
        // 1. Validate file with write permission check
        Object[] validateResult = validateFile(path, true, true, "write");
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        // 2. Check if parent directory is locked
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            String[] parentLockCheck = checkDirectoryLock(parentDir);
            if (parentLockCheck != null) {
                return parentLockCheck;
            }
        }

        try {
            // 3. Read existing file content (to get metadata)
            String fullContent = "";
            if (file.exists()) {
                fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

                // 4. Check lock status
                String[] lockCheck = checkLock(file);
                if (lockCheck != null) {
                    return lockCheck;
                }
            }

            // 5. Extract metadata
            String metaJson = "{}";
            if (!fullContent.isEmpty()) {
                String[] metaResult = extractMetaContent(fullContent);
                if (metaResult[0].equals("SUCCESS")) {
                    metaJson = metaResult[1];
                }
            }

            // 6. Parse metadata
            Object metaObj = JsonUtil.readJson(metaJson);
            Map<String, Object> metaMap;
            if (metaObj instanceof Map) {
                metaMap = (Map<String, Object>) metaObj;
            } else {
                metaMap = new HashMap<>();
            }

            // 7. Ensure locked field exists
            Object lockedObj = metaMap.get("locked");
            if (!(lockedObj instanceof Map)) {
                Map<String, Object> lockMap = new HashMap<>();
                lockMap.put("isLocked", false);
                lockMap.put("lockedBy", null);
                metaMap.put("locked", lockMap);
            }

            // 8. Update time in metadata
            Map<String, Object> time = (Map<String, Object>) metaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                metaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // 9. Ensure create time exists
            if (!time.containsKey("createTime")) {
                time.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            }

            // 10. Reassemble file
            String newMetaJson = JsonUtil.toJson(metaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + content;

            // 11. Write back to file
            Files.write(file.toPath(), newFullContent.getBytes());

            // 12. Update file size metadata
            updateFileSize(file, metaMap);

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("Failed to write file: " + e.getMessage());
            return new String[] { "ERROR", "WRITE_FAILED" };
        }
    }

    /**
     * Append content to file (add new line)
     *
     * @param path    File path
     * @param content Content to append
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] append(String path, String content) {
        // 1. Validate file with write permission check
        Object[] validateResult = validateFile(path, true, true, "write");
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        // 2. Check if parent directory is locked
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            String[] parentLockCheck = checkDirectoryLock(parentDir);
            if (parentLockCheck != null) {
                return parentLockCheck;
            }
        }

        try {
            // 3. Read existing file content
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // 4. Check lock status
            String[] lockCheck = checkLock(file);
            if (lockCheck != null) {
                return lockCheck;
            }

            // 5. Extract metadata and body
            String metaJson = "{}";
            String bodyContent = "";
            String[] metaResult = extractMetaContent(fullContent);
            if (metaResult[0].equals("SUCCESS")) {
                metaJson = metaResult[1];
                bodyContent = extractBodyContent(fullContent);
            }

            // 6. Parse metadata
            Object metaObj = JsonUtil.readJson(metaJson);
            Map<String, Object> metaMap;

            if (metaObj instanceof Map) {
                metaMap = (Map<String, Object>) metaObj;
            } else {
                metaMap = new HashMap<>();
            }

            // 7. Ensure locked field exists
            Object lockedObj = metaMap.get("locked");
            if (!(lockedObj instanceof Map)) {
                Map<String, Object> lockMap = new HashMap<>();
                lockMap.put("isLocked", false);
                lockMap.put("lockedBy", null);
                metaMap.put("locked", lockMap);
            }

            // 8. Update time in metadata
            Map<String, Object> time = (Map<String, Object>) metaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                metaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // 9. Ensure create time exists
            if (!time.containsKey("createTime")) {
                time.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            }

            // 10. Append content to body (add newline if body not empty)
            String newBodyContent;
            if (bodyContent.isEmpty()) {
                newBodyContent = content;
            } else {
                newBodyContent = bodyContent + "\n" + content;
            }

            // 11. Reassemble file
            String newMetaJson = JsonUtil.toJson(metaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + newBodyContent;

            // 12. Write back to file
            Files.write(file.toPath(), newFullContent.getBytes());

            // 13. Update file size metadata
            updateFileSize(file, metaMap);

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("Failed to append to file: " + e.getMessage());
            return new String[] { "ERROR", "APPEND_FAILED" };
        }
    }

    /**
     * Lock file
     *
     * @param path File path
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] lock(String path) {
        // 1. Validate file
        Object[] validateResult = validateFile(path, false, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        // 2. Check if parent directory is locked
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            String[] parentLockCheck = checkDirectoryLock(parentDir);
            if (parentLockCheck != null) {
                return parentLockCheck;
            }
        }

        try {
            // 3. Read full file content
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // 4. Separate metadata and body
            String[] metaResult = extractMetaContent(fullContent);
            String metaJson;
            String bodyContent;

            if (metaResult[0].equals("SUCCESS")) {
                // Has metadata
                metaJson = metaResult[1];
                bodyContent = extractBodyContent(fullContent);
            } else {
                // No metadata, create default
                metaJson = "{}";
                bodyContent = fullContent;
            }

            // 5. Parse metadata
            Object metaObj = JsonUtil.readJson(metaJson);
            Map<String, Object> metaMap;

            if (metaObj instanceof Map) {
                metaMap = (Map<String, Object>) metaObj;
            } else {
                metaMap = new HashMap<>();
            }

            // 6. Get or create locked field
            Object lockedObj = metaMap.get("locked");
            Map<String, Object> locked;
            if (lockedObj instanceof Map) {
                locked = (Map<String, Object>) lockedObj;
            } else {
                locked = new HashMap<>();
                metaMap.put("locked", locked);
            }

            // 7. Check if already locked
            Boolean isLocked = (Boolean) locked.get("isLocked");
            if (isLocked != null && isLocked) {
                return new String[] { "ERROR", "FILE_IS_LOCKED" };
            }

            // 8. Lock file (use current process ID)
            locked.put("isLocked", true);
            int currentPid = com.follarce.process.ProcessFunc.getPID();
            locked.put("lockedBy", currentPid);

            // 9. Update time in metadata
            Map<String, Object> time = (Map<String, Object>) metaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                metaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // 10. Reassemble file
            String newMetaJson = JsonUtil.toJson(metaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + bodyContent;

            // 11. Write back to file
            Files.write(file.toPath(), newFullContent.getBytes());

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("Failed to lock file: " + e.getMessage());
            return new String[] { "ERROR", "LOCK_FAILED" };
        }
    }

    /**
     * Unlock file
     *
     * @param path File path
     * @return String[] Array, [0] is status, [1] is error code (if any)
     */
    public static String[] unlock(String path) {
        // 1. Validate file
        Object[] validateResult = validateFile(path, false, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        // 2. Check if parent directory is locked
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            String[] parentLockCheck = checkDirectoryLock(parentDir);
            if (parentLockCheck != null) {
                return parentLockCheck;
            }
        }

        try {
            // 3. Read full file content
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // 4. Separate metadata and body
            String[] metaResult = extractMetaContent(fullContent);
            String metaJson;
            String bodyContent;

            if (metaResult[0].equals("SUCCESS")) {
                metaJson = metaResult[1];
                bodyContent = extractBodyContent(fullContent);
            } else {
                return new String[] { "ERROR", "FILE_IS_NOT_LOCKED" }; // No metadata, definitely not locked
            }

            // 5. Parse metadata
            Object metaObj = JsonUtil.readJson(metaJson);
            if (!(metaObj instanceof Map)) {
                return new String[] { "ERROR", "FILE_IS_NOT_LOCKED" };
            }

            Map<String, Object> metaMap = (Map<String, Object>) metaObj;

            // 6. Get locked field
            Object lockedObj = metaMap.get("locked");
            if (!(lockedObj instanceof Map)) {
                return new String[] { "ERROR", "FILE_IS_NOT_LOCKED" };
            }
            Map<String, Object> locked = (Map<String, Object>) lockedObj;

            // 7. Check if already locked
            Boolean isLocked = (Boolean) locked.get("isLocked");
            if (isLocked == null || !isLocked) {
                return new String[] { "ERROR", "FILE_IS_NOT_LOCKED" };
            }

            // 8. Verify lock holder (only locker can unlock, or local user)
            Object lockedBy = locked.get("lockedBy");
            int currentPid = com.follarce.process.ProcessFunc.getPID();
            boolean isLocal = com.follarce.init.UserInit.isLocal();

            if (!isLocal) {
                // Non-local user needs to verify holder
                if (lockedBy instanceof Number) {
                    int lockHolderPid = ((Number) lockedBy).intValue();
                    if (lockHolderPid != currentPid) {
                        return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
                    }
                } else {
                    return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
                }
            }

            // 9. Unlock file
            locked.put("isLocked", false);
            locked.put("lockedBy", null);

            // 9. Update time in metadata
            Map<String, Object> time = (Map<String, Object>) metaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                metaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // 10. Reassemble file
            String newMetaJson = JsonUtil.toJson(metaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + bodyContent;

            // 11. Write back to file
            Files.write(file.toPath(), newFullContent.getBytes());

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("Failed to unlock file: " + e.getMessage());
            return new String[] { "ERROR", "UNLOCK_FAILED" };
        }
    }

    /**
     * Read file
     *
     * @param path File path
     * @return String[] Array, [0] is status, [1] is content or error code
     */
    public static String[] read(String path) {
        // 1. Check path
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. Get root directory
        String root = getVfsRoot();

        // 3. Convert to real path
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 4. Check if it's a link file, if so operate on target file
        String targetPath = resolveLink(realPath);

        // 5. Check if parent directory exists
        File file = new File(targetPath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 6. Check if file exists
        if (!file.exists()) {
            return new String[] { "ERROR", "FILE_DOES_NOT_EXIST" };
        }

        // 7. Check if it's a file
        if (file.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_FILE" };
        }

        // 8. Check read permission
        String[] permCheck = checkAndValidatePermission(path, "read");
        if (permCheck != null) {
            return permCheck;
        }

        // 9. Read file
        try {
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String bodyContent = extractBodyContent(fullContent);

            // 9. Update lastOpenTime and file size in metadata
            updateReadMetadata(file, fullContent);

            return new String[] { "SUCCESS", bodyContent };
        } catch (IOException e) {
            LOGGER.warning("Failed to read file: " + e.getMessage());
            return new String[] { "ERROR", "READ_FAILED" };
        }
    }

    /**
     * Update metadata when file is read (lastOpenTime and file size)
     *
     * @param file        The file being read
     * @param fullContent The full content of the file (including metadata)
     */
    private static void updateReadMetadata(File file, String fullContent) {
        try {
            String[] metaResult = extractMetaContent(fullContent);
            if (!metaResult[0].equals("SUCCESS")) {
                return; // No metadata to update
            }

            Object metaObj = JsonUtil.readJson(metaResult[1]);
            if (!(metaObj instanceof Map)) {
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> metaMap = (Map<String, Object>) metaObj;

            // Update lastOpenTime
            Map<String, Object> time = (Map<String, Object>) metaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                metaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastOpenTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // Update file size
            String bodyContent = extractBodyContent(fullContent);
            int sizeInBytes = bodyContent.getBytes(StandardCharsets.UTF_8).length;
            Object[] sizeInfo = formatSize(sizeInBytes);
            metaMap.put("Size", sizeInfo);

            // Reassemble and write back
            String newMetaJson = JsonUtil.toJson(metaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + bodyContent;
            Files.write(file.toPath(), newFullContent.getBytes(StandardCharsets.UTF_8));

        } catch (IOException e) {
            LOGGER.warning("Failed to update read metadata: " + e.getMessage());
        }
    }

    /**
     * Extract metadata content
     */
    public static String[] extractMetaContent(String fullContent) {
        if (fullContent == null || fullContent.isEmpty()) {
            return new String[] { "ERROR", "NO_META" };
        }

        String metaStart = "#<META>";
        String metaEnd = "<META>#";

        int startIndex = fullContent.indexOf(metaStart);
        if (startIndex == -1) {
            return new String[] { "ERROR", "NO_META" };
        }

        int endIndex = fullContent.indexOf(metaEnd, startIndex + metaStart.length());
        if (endIndex == -1) {
            return new String[] { "ERROR", "META_NOT_CLOSED" };
        }

        String metaJson = fullContent.substring(
                startIndex + metaStart.length(),
                endIndex).trim();

        return new String[] { "SUCCESS", metaJson };
    }

    /**
     * Extract body content
     */
    private static String extractBodyContent(String fullContent) {
        if (fullContent == null) {
            return "";
        }

        String metaEnd = "<META>#";
        int endIndex = fullContent.indexOf(metaEnd);
        if (endIndex == -1) {
            return fullContent; // No metadata, return all content
        }
        // Return content after metadata ends
        return fullContent.substring(endIndex + metaEnd.length()).trim();
    }

    /**
     * Get VFS root directory (read from config file)
     */
    public static String getVfsRoot() {
        if (VFS_ROOT != null) {
            return VFS_ROOT; // Already retrieved
        }

        try {
            // First find JAR directory
            String jarDir = getJarDirectory();

            // Read init.json
            String initPath = jarDir + File.separator + "cilexec_root" +
                    File.separator + "system" + File.separator +
                    "config" + File.separator + "init.json";

            File initFile = new File(initPath);
            if (!initFile.exists()) {
                // If not found, use default path
                VFS_ROOT = jarDir + File.separator + "cilexec_root";
                LOGGER.info("Using default VFS root: " + VFS_ROOT);
                return VFS_ROOT;
            }

            String content = new String(Files.readAllBytes(initFile.toPath()), StandardCharsets.UTF_8);

            // Strip metadata header if present
            content = extractBodyContent(content);

            Object obj = JsonUtil.readJson(content);

            if (obj instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) obj;
                Object rootObj = map.get("root");
                if (rootObj instanceof String) {
                    String root = (String) rootObj;
                    if (root != null && !root.isEmpty()) {
                        VFS_ROOT = root;
                        LOGGER.info("Read VFS root from config file: " + VFS_ROOT);
                        return VFS_ROOT;
                    }
                }
            }

            // Parse failed, use default path
            VFS_ROOT = jarDir + File.separator + "cilexec_root";
            LOGGER.info("Parse failed, using default VFS root: " + VFS_ROOT);
            return VFS_ROOT;

        } catch (IOException e) {
            VFS_ROOT = getJarDirectory() + File.separator + "cilexec_root";
            LOGGER.warning("Error reading config file: " + e.getMessage() + ", using default VFS root: " + VFS_ROOT);
            return VFS_ROOT;
        }
    }

    /**
     * Get JAR directory
     */
    private static String getJarDirectory() {
        try {
            String path = FileUtil.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .getPath();
            File jarFile = new File(path);
            return jarFile.getParent();
        } catch (java.net.URISyntaxException e) {
            LOGGER.warning("Failed to get JAR directory: " + e.getMessage());
            return System.getProperty("user.dir");
        } catch (Exception e) {
            LOGGER.warning("Failed to get JAR directory: " + e.getMessage());
            return System.getProperty("user.dir");
        }
    }

    /**
     * Dispatch function calls from script engine
     */
    public static Object call(String name, Object[] args) {
        switch (name) {
            case "read":
                if (args.length < 1)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return read((String) args[0]);

            case "write":
                if (args.length < 2)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return write((String) args[0], (String) args[1]);

            case "listdir":
                if (args.length < 1)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return getListOfFileAndDirectory((String) args[0]);

            case "readMeta":
                if (args.length < 1)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return readFileMetaData((String) args[0]);

            case "writeMeta":
                if (args.length < 2)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return writeFileMetaData((String) args[0], (String) args[1]);

            case "createFile":
                if (args.length < 2)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return createFile((String) args[0], (String) args[1]);

            case "removeFile":
                if (args.length < 1)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return removeFile((String) args[0]);

            case "createDir":
                if (args.length < 2)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return createDirectory((String) args[0], (String) args[1]);

            case "removeDir":
                if (args.length < 1)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return removeDirectory((String) args[0]);

            case "rename":
                if (args.length < 2)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return Rename((String) args[0], (String) args[1]);

            case "link":
                if (args.length < 2)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return Link((String) args[0], (String) args[1]);

            case "lock":
                if (args.length < 1)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return lock((String) args[0]);

            case "unlock":
                if (args.length < 1)
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                return unlock((String) args[0]);

            default:
                return null;
        }
    }

    /**
     * Update file size metadata
     * Calculates the actual file content size (excluding metadata) and updates the
     * metadata
     *
     * @param file    The file to update
     * @param metaMap The metadata map to update
     */
    private static void updateFileSize(File file, Map<String, Object> metaMap) {
        try {
            // Read the full file content
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // Extract body content (actual file content without metadata)
            String bodyContent = extractBodyContent(fullContent);

            // Calculate size in bytes
            int sizeInBytes = bodyContent.getBytes(StandardCharsets.UTF_8).length;

            // Format size with unit
            Object[] sizeInfo = formatSize(sizeInBytes);

            // Update metadata
            metaMap.put("Size", sizeInfo);

            // Also update lastEditTime and lastOpenTime
            Map<String, Object> time = (Map<String, Object>) metaMap.get("Time");
            if (time != null) {
                int[] now = TimeUtil.getTime();
                time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
                time.put("lastOpenTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            }

            // Reassemble and write back
            String newMetaJson = JsonUtil.toJson(metaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + bodyContent;
            Files.write(file.toPath(), newFullContent.getBytes(StandardCharsets.UTF_8));

        } catch (IOException e) {
            LOGGER.warning("Failed to update file size: " + e.getMessage());
        }
    }

    /**
     * Format size with appropriate unit
     *
     * @param bytes Size in bytes
     * @return Object array [size, unit]
     */
    private static Object[] formatSize(int bytes) {
        if (bytes < Constants.SIZE_UNIT_KB) {
            return new Object[] { bytes, "B" };
        } else if (bytes < Constants.SIZE_UNIT_MB) {
            return new Object[] { bytes / Constants.SIZE_UNIT_KB, "KB" };
        } else if (bytes < Constants.SIZE_UNIT_GB) {
            return new Object[] { bytes / Constants.SIZE_UNIT_MB, "MB" };
        } else {
            return new Object[] { bytes / Constants.SIZE_UNIT_GB, "GB" };
        }
    }

    /**
     * Check and validate file/directory permission
     * 
     * @param path      File or directory path
     * @param operation Operation type (read/write/execute)
     * @return null if permission granted, error array if denied
     */
    private static String[] checkAndValidatePermission(String path, String operation) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }

        if (!UserUtil.checkFilePermission(path, operation)) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        return null;
    }

    /**
     * Check and validate lock status for file or directory
     * Auto-unlock if locker process is dead
     * 
     * @param file        File or directory to check
     * @param isDirectory true if checking directory, false if checking file
     * @return null if not locked or lock released, error array if locked
     */
    private static String[] checkAndValidateLock(File file, boolean isDirectory) {
        try {
            File metaFile;
            String fullContent;

            if (isDirectory) {
                metaFile = new File(file, ".META");
                if (!metaFile.exists()) {
                    return null;
                }
                fullContent = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
            } else {
                if (!file.exists()) {
                    return null;
                }
                fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }

            String[] metaResult = extractMetaContent(fullContent);

            if (metaResult[0].equals("SUCCESS")) {
                Object metaObj = JsonUtil.readJson(metaResult[1]);
                if (metaObj instanceof Map) {
                    Map<String, Object> metaMap = (Map<String, Object>) metaObj;
                    Object lockedObj = metaMap.get("locked");
                    if (lockedObj instanceof Map) {
                        Map<String, Object> locked = (Map<String, Object>) lockedObj;
                        Boolean isLocked = (Boolean) locked.get("isLocked");
                        if (isLocked != null && isLocked) {
                            Object lockedBy = locked.get("lockedBy");
                            if (lockedBy instanceof Number) {
                                int lockerPid = ((Number) lockedBy).intValue();
                                int currentPid = com.follarce.process.ProcessFunc.getPID();
                                if (lockerPid == currentPid) {
                                    return null;
                                }
                                if (!isProcessExists(lockerPid)) {
                                    String entityType = isDirectory ? "directory" : "file";
                                    LOGGER.info("Auto-unlocking " + entityType + " " + file.getPath() +
                                            " (locker PID " + lockerPid + " is dead)");

                                    locked.put("isLocked", false);
                                    locked.put("lockedBy", null);

                                    Map<String, Object> updates = new HashMap<>();
                                    updates.put("Time.lastEditTime", new int[] { 0, 0, 0, 0, 0, 0, 0 });
                                    updateMetadata(file, metaMap, updates, isDirectory);

                                    return null;
                                }
                            }
                            return new String[] { "ERROR", isDirectory ? "DIRECTORY_IS_LOCKED" : "FILE_IS_LOCKED" };
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to check lock status: " + e.getMessage());
            return new String[] { "ERROR", "CHECK_LOCK_FAILED" };
        }
        return null;
    }

    /**
     * Update metadata for file or directory
     * 
     * @param file        File or directory to update
     * @param metaMap     Existing metadata map
     * @param updates     Map of updates to apply (supports nested keys like
     *                    "Time.lastEditTime")
     * @param isDirectory true if updating directory metadata, false if updating
     *                    file metadata
     * @throws IOException if update fails
     */
    private static void updateMetadata(File file, Map<String, Object> metaMap,
            Map<String, Object> updates, boolean isDirectory) throws IOException {
        int[] now = TimeUtil.getTime();

        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (key.contains(".")) {
                String[] parts = key.split("\\.", 2);
                String parentKey = parts[0];
                String childKey = parts[1];

                Map<String, Object> parentMap = (Map<String, Object>) metaMap.get(parentKey);
                if (parentMap == null) {
                    parentMap = new HashMap<>();
                    metaMap.put(parentKey, parentMap);
                }

                if (value instanceof int[] && ((int[]) value).length == 7 &&
                        ((int[]) value)[0] == 0) {
                    parentMap.put(childKey, new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
                } else {
                    parentMap.put(childKey, value);
                }
            } else {
                if (value instanceof int[] && ((int[]) value).length == 7 &&
                        ((int[]) value)[0] == 0) {
                    metaMap.put(key, new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
                } else {
                    metaMap.put(key, value);
                }
            }
        }

        String newMetaJson = JsonUtil.toJson(metaMap);

        if (isDirectory) {
            File metaFile = new File(file, ".META");
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n";
            Files.write(metaFile.toPath(), newFullContent.getBytes(StandardCharsets.UTF_8));
        } else {
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String bodyContent = extractBodyContent(fullContent);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + bodyContent;
            Files.write(file.toPath(), newFullContent.getBytes(StandardCharsets.UTF_8));
        }
    }

}