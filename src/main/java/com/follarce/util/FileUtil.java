package com.follarce.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

public class FileUtil {
    private static String VFS_ROOT = null; // 一开始不知道
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger("FileUtil");

    /**
     * 检查文件名是否合法（不能以.开头）
     */
    private static boolean isValidName(String name) {
        return name != null && !name.trim().isEmpty() && !name.startsWith(".");
    }

    /**
     * 检查文件是否是链接文件，如果是则返回目标路径（支持链式链接）
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
                    String linkTarget = (String) metaMap.get("Link");
                    if (linkTarget != null && !linkTarget.isEmpty()) {
                        // 如果是相对路径，基于当前文件所在目录
                        if (!linkTarget.startsWith("/")) {
                            String fileDir = realPath.substring(0, realPath.lastIndexOf(File.separator) + 1);
                            return fileDir + linkTarget.replace('/', File.separatorChar);
                        }
                        // 如果是绝对路径，加上 VFS 根目录
                        return getVfsRoot() + linkTarget.replace('/', File.separatorChar);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warning("读取链接文件失败: " + realPath + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * 递归解析链接文件（支持链式链接，检测循环）
     */
    private static String resolveLinkRecursive(String realPath, Set<String> visited) {
        if (visited.contains(realPath)) {
            LOGGER.warning("检测到循环链接: " + realPath);
            return null; // 检测到循环链接
        }
        visited.add(realPath);

        String linkTarget = getLinkTarget(realPath);
        if (linkTarget != null) {
            return resolveLinkRecursive(linkTarget, visited);
        }
        return realPath;
    }

    /**
     * 获取真实路径（处理链接文件，支持链式链接）
     */
    private static String resolveLink(String realPath) {
        Set<String> visited = new HashSet<>();
        String result = resolveLinkRecursive(realPath, visited);
        return result != null ? result : realPath; // 如果出错，返回原路径
    }

    /**
     * 规范化路径（支持 Windows 反斜杠，处理 .. 和 .）
     */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        // 统一替换反斜杠为正斜杠
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
                stack.push(part);
            }
        }

        StringBuilder result = new StringBuilder();
        for (String part : stack) {
            result.append("/").append(part);
        }

        // 处理根路径
        if (result.length() == 0) {
            return "/";
        }

        return result.toString();
    }

    /**
     * 验证文件路径并返回 File 对象
     * 
     * @param path           虚拟路径
     * @param checkParentDir 是否检查父目录存在
     * @param needExist      文件是否需要存在
     * @return [File对象, 错误信息] 如果出错返回null和错误码
     */
    private static Object[] validateFile(String path, boolean checkParentDir, boolean needExist) {
        if (path == null || path.trim().isEmpty()) {
            return new Object[] { null, new String[] { "ERROR", "INVALID_PATH" } };
        }

        String root = getVfsRoot();
        String normalized = normalizePath(path);

        // 处理根路径特殊情况
        if (normalized.equals("/")) {
            File rootFile = new File(root);
            return new Object[] { rootFile, null };
        }

        String realPath = root + normalized.replace('/', File.separatorChar);

        // 解析链接
        String targetPath = resolveLink(realPath);
        File file = new File(targetPath);

        // 检查父目录
        if (checkParentDir) {
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                return new Object[] { null, new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" } };
            }
        }

        // 检查文件是否存在
        if (needExist && !file.exists()) {
            return new Object[] { null, new String[] { "ERROR", "FILE_DOES_NOT_EXIST" } };
        }

        // 检查是否是文件（如果是目录则报错）
        if (needExist && file.isDirectory()) {
            return new Object[] { null, new String[] { "ERROR", "IS_NOT_FILE" } };
        }

        return new Object[] { file, null };
    }

    /**
     * 检查目录是否被锁定
     */
    private static String[] checkDirectoryLock(File dir) {
        File metaFile = new File(dir, ".META");
        if (!metaFile.exists()) {
            return null;
        }

        try {
            String fullContent = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
            String[] metaResult = extractMetaContent(fullContent);

            if (metaResult[0].equals("SUCCESS")) {
                Object metaObj = JsonUtil.readJson(metaResult[1]);
                if (metaObj instanceof Map) {
                    Map<String, Object> metaMap = (Map<String, Object>) metaObj;
                    Map<String, Object> locked = (Map<String, Object>) metaMap.get("locked");
                    if (locked != null) {
                        Boolean isLocked = (Boolean) locked.get("isLocked");
                        if (isLocked != null && isLocked) {
                            return new String[] { "ERROR", "DIRECTORY_IS_LOCKED" };
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warning("检查目录锁状态失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 创建链接文件
     * 
     * @param path       链接文件存放的目录路径（结尾是/）
     * @param sourcePath 源文件或目录路径
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] Link(String path, String sourcePath) {
        // 1. 检查路径
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. 检查源路径
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_SOURCE_PATH" };
        }

        // 3. 确保路径以/结尾
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 4. 获取根目录
        String root = getVfsRoot();

        // 5. 转换源路径为真实路径，检查源文件/目录是否存在
        String normalizedSource = normalizePath(sourcePath);
        String realSourcePath = root + normalizedSource.replace('/', File.separatorChar);
        File sourceFile = new File(realSourcePath);

        if (!sourceFile.exists()) {
            return new String[] { "ERROR", "SOURCE_FILE_DOES_NOT_EXIST" };
        }

        // 6. 从源路径提取文件名（链接文件的名字就是源文件的名字）
        String sourceName = sourcePath;
        int lastSlash = sourcePath.lastIndexOf('/');
        if (lastSlash >= 0) {
            sourceName = sourcePath.substring(lastSlash + 1);
        }
        // 如果提取的结果是空字符串，说明源路径以/结尾，需要再往前找
        if (sourceName.isEmpty()) {
            String pathWithoutTrailingSlash = sourcePath.substring(0, sourcePath.length() - 1);
            lastSlash = pathWithoutTrailingSlash.lastIndexOf('/');
            if (lastSlash >= 0) {
                sourceName = pathWithoutTrailingSlash.substring(lastSlash + 1);
            } else {
                sourceName = pathWithoutTrailingSlash;
            }
        }
        // 如果是目录且名字末尾有/，去掉
        if (sourceName.endsWith("/")) {
            sourceName = sourceName.substring(0, sourceName.length() - 1);
        }

        // 检查文件名是否合法
        if (!isValidName(sourceName)) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        // 7. 转换链接目录路径
        String normalized = normalizePath(path);
        String realDirPath = root + normalized.replace('/', File.separatorChar);

        // 8. 检查目录是否存在
        File dir = new File(realDirPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 检查目录是否被锁定
        String[] lockCheck = checkDirectoryLock(dir);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 9. 完整的链接文件路径（名字与源文件相同）
        String realLinkPath = realDirPath + File.separator + sourceName;
        File linkFile = new File(realLinkPath);

        // 10. 检查链接文件是否已存在
        if (linkFile.exists()) {
            return new String[] { "ERROR", "FILE_EXIST" };
        }

        try {
            // 11. 获取当前时间
            int[] now = TimeUtil.getTime();

            // 12. 创建链接文件的元数据
            Map<String, Object> metaMap = new HashMap<>();

            // 添加链接目标（存储原始路径，方便相对路径处理）
            metaMap.put("Link", sourcePath);

            // 添加时间信息
            Map<String, Object> timeMap = new HashMap<>();
            timeMap.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            timeMap.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            metaMap.put("Time", timeMap);

            // 添加所有者
            metaMap.put("Owner", "local");

            // 添加大小
            metaMap.put("Size", new Object[] { 0, "B" });

            // 添加权限
            Map<String, String> permMap = new HashMap<>();
            permMap.put("Owner", "read, write");
            permMap.put("Others", "read");
            metaMap.put("Permission", permMap);

            // 添加锁状态
            Map<String, Object> lockMap = new HashMap<>();
            lockMap.put("isLocked", false);
            lockMap.put("lockedBy", null);
            metaMap.put("locked", lockMap);

            // 13. 转换为JSON
            String metaJson = JsonUtil.toJson(metaMap);

            // 14. 创建链接文件内容
            String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";

            // 15. 写入文件
            Files.write(linkFile.toPath(), fileContent.getBytes());

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("创建链接文件失败: " + e.getMessage());
            return new String[] { "ERROR", "CREATE_LINK_FAILED" };
        }
    }

    /**
     * 重命名文件或目录
     * 
     * @param path    源路径（文件或目录）
     * @param newName 新名称
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] Rename(String path, String newName) {
        // 1. 检查新名称是否合法
        if (!isValidName(newName)) {
            return new String[] { "ERROR", "INVALID_NEW_NAME" };
        }

        // 2. 验证新名称是否合法（不能包含特殊字符）
        if (newName.contains("/") || newName.contains("\\") || newName.contains("..")) {
            return new String[] { "ERROR", "INVALID_NEW_NAME" };
        }

        // 3. 检查路径
        Object[] validateResult = validateFile(path, true, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File source = (File) validateResult[0];

        // 4. 如果是目录，检查目录是否被锁定
        if (source.isDirectory()) {
            String[] lockCheck = checkDirectoryLock(source);
            if (lockCheck != null) {
                return lockCheck;
            }
        }

        // 5. 如果是文件，检查是否被锁定
        if (source.isFile()) {
            String[] lockCheck = checkLock(source);
            if (lockCheck != null) {
                return lockCheck;
            }
        }

        // 6. 获取父目录和目标路径
        File parentDir = source.getParentFile();
        if (parentDir == null) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 检查父目录是否被锁定
        String[] parentLockCheck = checkDirectoryLock(parentDir);
        if (parentLockCheck != null) {
            return parentLockCheck;
        }

        File target = new File(parentDir, newName);

        // 7. 检查目标是否已存在
        if (target.exists()) {
            return new String[] { "ERROR", "FILE_EXIST" };
        }

        // 8. 执行重命名
        boolean renamed = source.renameTo(target);
        if (renamed) {
            return new String[] { "SUCCESS", null };
        } else {
            return new String[] { "ERROR", "RENAME_FAILED" };
        }
    }

    /**
     * 检查文件是否被锁定
     */
    private static String[] checkLock(File file) {
        try {
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String[] metaResult = extractMetaContent(fullContent);

            if (metaResult[0].equals("SUCCESS")) {
                Object metaObj = JsonUtil.readJson(metaResult[1]);
                if (metaObj instanceof Map) {
                    Map<String, Object> metaMap = (Map<String, Object>) metaObj;
                    Map<String, Object> locked = (Map<String, Object>) metaMap.get("locked");
                    if (locked != null) {
                        Boolean isLocked = (Boolean) locked.get("isLocked");
                        if (isLocked != null && isLocked) {
                            return new String[] { "ERROR", "FILE_IS_LOCKED" };
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warning("检查锁状态失败: " + e.getMessage());
            return new String[] { "ERROR", "CHECK_LOCK_FAILED" };
        }
        return null;
    }

    /**
     * 创建目录元数据文件
     */
    private static void createDirectoryMetaData(File dir) throws IOException {
        File metaFile = new File(dir, ".META");
        if (metaFile.exists()) {
            return;
        }

        int[] now = TimeUtil.getTime();

        Map<String, Object> metaMap = new HashMap<>();

        // 时间信息
        Map<String, Object> timeMap = new HashMap<>();
        timeMap.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
        timeMap.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
        timeMap.put("lastOpenTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
        metaMap.put("Time", timeMap);

        // 所有者
        metaMap.put("Owner", "local");

        // 权限
        Map<String, String> permMap = new HashMap<>();
        permMap.put("Owner", "read, write");
        permMap.put("Others", "read");
        metaMap.put("Permission", permMap);

        // 锁状态
        Map<String, Object> lockMap = new HashMap<>();
        lockMap.put("isLocked", false);
        lockMap.put("lockedBy", null);
        metaMap.put("locked", lockMap);

        String metaJson = JsonUtil.toJson(metaMap);
        String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";
        Files.write(metaFile.toPath(), fileContent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 删除空目录
     * 
     * @param path 目录路径（结尾是/）
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] removeDirectory(String path) {
        // 1. 检查路径
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. 确保路径以/结尾
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 3. 获取根目录
        String root = getVfsRoot();

        // 4. 转换为真实路径
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 5. 检查目录是否存在
        File dir = new File(realPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 6. 检查是否是目录
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 7. 检查目录是否被锁定
        String[] lockCheck = checkDirectoryLock(dir);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 8. 检查目录是否为空（忽略 .META 文件）
        String[] files = dir.list();
        if (files != null) {
            // 过滤掉 .META 文件
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

        // 9. 先删除 .META 文件（如果存在）
        File metaFile = new File(dir, ".META");
        if (metaFile.exists()) {
            metaFile.delete();
        }

        // 10. 删除目录
        boolean deleted = dir.delete();
        if (deleted) {
            return new String[] { "SUCCESS", null };
        } else {
            return new String[] { "ERROR", "DELETE_FAILED" };
        }
    }

    /**
     * 创建目录
     * 
     * @param path 目录路径（结尾是/）
     * @param name 目录名称
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] createDirectory(String path, String name) {
        // 1. 检查路径
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. 检查目录名是否合法
        if (!isValidName(name)) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        // 3. 验证目录名是否合法（不能包含特殊字符）
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        // 4. 确保路径以/结尾
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 5. 获取根目录
        String root = getVfsRoot();

        // 6. 转换为真实路径（父目录路径）
        String normalized = normalizePath(path);
        String realParentPath = root + normalized.replace('/', File.separatorChar);

        // 7. 检查父目录是否存在
        File parentDir = new File(realParentPath);
        if (!parentDir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 8. 检查父目录是否是目录
        if (!parentDir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 9. 检查父目录是否被锁定
        String[] parentLockCheck = checkDirectoryLock(parentDir);
        if (parentLockCheck != null) {
            return parentLockCheck;
        }

        // 10. 完整的目录路径
        String realDirPath = realParentPath + File.separator + name;
        File newDir = new File(realDirPath);

        // 11. 检查目录是否已存在
        if (newDir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_EXIST" };
        }

        // 12. 创建目录
        boolean created = newDir.mkdir();
        if (created) {
            try {
                // 13. 创建目录元数据文件
                createDirectoryMetaData(newDir);
                return new String[] { "SUCCESS", null };
            } catch (IOException e) {
                LOGGER.warning("创建目录元数据失败: " + e.getMessage());
                return new String[] { "SUCCESS", null };
            }
        } else {
            return new String[] { "ERROR", "CREATE_FAILED" };
        }
    }

    /**
     * 删除文件
     * 
     * @param path 文件完整路径
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] removeFile(String path) {
        // 1. 验证文件
        Object[] validateResult = validateFile(path, true, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        // 2. 检查锁状态
        String[] lockCheck = checkLock(file);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 3. 检查父目录是否被锁定
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            String[] parentLockCheck = checkDirectoryLock(parentDir);
            if (parentLockCheck != null) {
                return parentLockCheck;
            }
        }

        // 4. 删除文件
        try {
            boolean deleted = file.delete();
            if (deleted) {
                return new String[] { "SUCCESS", null };
            } else {
                return new String[] { "ERROR", "DELETE_FAILED" };
            }
        } catch (Exception e) {
            LOGGER.warning("删除文件失败: " + e.getMessage());
            return new String[] { "ERROR", "DELETE_FAILED" };
        }
    }

    /**
     * 创建文件
     * 
     * @param path 目录路径（结尾是/）
     * @param name 文件名称
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] createFile(String path, String name) {
        // 1. 检查路径
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. 检查文件名是否合法
        if (!isValidName(name)) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        // 3. 验证文件名是否合法（不能包含特殊字符）
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        // 4. 确保路径以/结尾
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 5. 获取根目录
        String root = getVfsRoot();

        // 6. 转换为真实路径（目录路径）
        String normalized = normalizePath(path);
        String realDirPath = root + normalized.replace('/', File.separatorChar);

        // 7. 检查目录是否存在
        File dir = new File(realDirPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 8. 检查是否是目录
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 9. 检查目录是否被锁定
        String[] lockCheck = checkDirectoryLock(dir);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 10. 完整的文件路径
        String realFilePath = realDirPath + File.separator + name;
        File newFile = new File(realFilePath);

        // 11. 检查文件是否已存在
        if (newFile.exists()) {
            return new String[] { "ERROR", "FILE_EXIST" };
        }

        try {
            // 12. 获取当前时间
            int[] now = TimeUtil.getTime();

            // 13. 创建默认元数据
            Map<String, Object> metaMap = new HashMap<>();

            // 添加时间信息
            Map<String, Object> timeMap = new HashMap<>();
            timeMap.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            timeMap.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            timeMap.put("lastOpenTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            metaMap.put("Time", timeMap);

            // 添加所有者（暂时用local）
            metaMap.put("Owner", "local");

            // 添加默认权限
            Map<String, String> permMap = new HashMap<>();
            permMap.put("Owner", "read, write");
            permMap.put("Others", "read");
            metaMap.put("Permission", permMap);

            // 添加锁状态
            Map<String, Object> lockMap = new HashMap<>();
            lockMap.put("isLocked", false);
            lockMap.put("lockedBy", null);
            metaMap.put("locked", lockMap);

            // 添加大小（初始为0）
            metaMap.put("Size", new Object[] { 0, "B" });

            // 14. 转换为JSON
            String metaJson = JsonUtil.toJson(metaMap);

            // 15. 创建文件内容（只有元数据，正文为空）
            String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";

            // 16. 写入文件
            Files.write(newFile.toPath(), fileContent.getBytes());

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("创建文件失败: " + e.getMessage());
            return new String[] { "ERROR", "CREATE_FAILED" };
        }
    }

    /**
     * 写入文件元信息
     * 
     * @param path    文件路径
     * @param content 新的元信息内容（JSON格式）
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] writeFileMetaData(String path, String content) {
        // 1. 验证文件
        Object[] validateResult = validateFile(path, true, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        // 2. 检查元数据内容
        if (content == null) {
            content = "{}";
        }

        // 3. 验证JSON格式
        if (!JsonUtil.isValidJson(content)) {
            return new String[] { "ERROR", "INVALID_JSON" };
        }

        // 4. 检查父目录是否被锁定
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            String[] parentLockCheck = checkDirectoryLock(parentDir);
            if (parentLockCheck != null) {
                return parentLockCheck;
            }
        }

        try {
            // 5. 读取文件现有内容（为了获取正文）
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String bodyContent = extractBodyContent(fullContent);

            // 6. 检查锁状态
            String[] lockCheck = checkLock(file);
            if (lockCheck != null) {
                return lockCheck;
            }

            // 7. 解析新的元数据，并确保有时间字段
            Object newMetaObj = JsonUtil.readJson(content);
            Map<String, Object> newMetaMap;

            if (newMetaObj instanceof Map) {
                newMetaMap = (Map<String, Object>) newMetaObj;
            } else {
                newMetaMap = new HashMap<>();
            }

            // 8. 更新时间字段
            Map<String, Object> time = (Map<String, Object>) newMetaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                newMetaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // 9. 如果有createTime，保留；没有则添加
            if (!time.containsKey("createTime")) {
                time.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            }

            // 10. 确保有locked字段
            if (!newMetaMap.containsKey("locked")) {
                Map<String, Object> lockMap = new HashMap<>();
                lockMap.put("isLocked", false);
                lockMap.put("lockedBy", null);
                newMetaMap.put("locked", lockMap);
            }

            // 11. 重新组装文件
            String newMetaJson = JsonUtil.toJson(newMetaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + bodyContent;

            // 12. 写回文件
            Files.write(file.toPath(), newFullContent.getBytes());

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("写入元数据失败: " + e.getMessage());
            return new String[] { "ERROR", "WRITE_FAILED" };
        }
    }

    /**
     * 读取文件元信息
     * 
     * @param path 文件路径
     * @return String[] 数组，[0]是状态，[1]是元信息JSON（如果没有元数据则返回"{}"）
     */
    public static String[] readFileMetaData(String path) {
        // 1. 验证文件
        Object[] validateResult = validateFile(path, true, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        try {
            // 2. 读取文件内容
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // 3. 提取元数据
            String[] metaResult = extractMetaContent(fullContent);

            if (metaResult[0].equals("SUCCESS")) {
                // 有元数据，直接返回
                return new String[] { "SUCCESS", metaResult[1] };
            } else {
                // 没有元数据，返回空对象
                return new String[] { "SUCCESS", "{}" };
            }

        } catch (IOException e) {
            LOGGER.warning("读取元数据失败: " + e.getMessage());
            return new String[] { "ERROR", "READ_FAILED" };
        }
    }

    /**
     * 读取目录元信息
     * 
     * @param path 目录路径（结尾是/）
     * @return String[] 数组，[0]是状态，[1]是元信息JSON（如果没有元数据则返回"{}"）
     */
    public static String[] readDirectoryMetaData(String path) {
        // 1. 检查路径
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. 确保路径以/结尾
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 3. 获取根目录
        String root = getVfsRoot();

        // 4. 转换为真实路径
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 5. 检查目录是否存在
        File dir = new File(realPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 6. 检查是否是目录
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 7. 元数据文件路径
        File metaFile = new File(dir, ".META");

        // 8. 检查元数据文件是否存在
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
            LOGGER.warning("读取目录元数据失败: " + e.getMessage());
            return new String[] { "ERROR", "READ_FAILED" };
        }
    }

    /**
     * 写入目录元信息
     * 
     * @param path    目录路径（结尾是/）
     * @param content 新的元信息内容（JSON格式）
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] writeDirectoryMetaData(String path, String content) {
        // 1. 检查路径
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. 确保路径以/结尾
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 3. 检查元数据内容
        if (content == null) {
            content = "{}";
        }

        // 4. 验证JSON格式
        if (!JsonUtil.isValidJson(content)) {
            return new String[] { "ERROR", "INVALID_JSON" };
        }

        // 5. 获取根目录
        String root = getVfsRoot();

        // 6. 转换为真实路径
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 7. 检查目录是否存在
        File dir = new File(realPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 8. 检查是否是目录
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 9. 检查目录是否被锁定
        String[] lockCheck = checkDirectoryLock(dir);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 10. 元数据文件路径
        File metaFile = new File(dir, ".META");

        // 11. 检查元数据文件是否存在
        if (!metaFile.exists()) {
            return new String[] { "ERROR", "META_DATA_FILE_DOES_NOT_EXIST" };
        }

        try {
            // 12. 解析新的元数据，并确保有时间字段
            Object newMetaObj = JsonUtil.readJson(content);
            Map<String, Object> newMetaMap;

            if (newMetaObj instanceof Map) {
                newMetaMap = (Map<String, Object>) newMetaObj;
            } else {
                newMetaMap = new HashMap<>();
            }

            // 13. 更新时间字段
            Map<String, Object> time = (Map<String, Object>) newMetaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                newMetaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // 14. 如果有createTime，保留；没有则添加
            if (!time.containsKey("createTime")) {
                time.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            }

            // 15. 确保有locked字段
            if (!newMetaMap.containsKey("locked")) {
                Map<String, Object> lockMap = new HashMap<>();
                lockMap.put("isLocked", false);
                lockMap.put("lockedBy", null);
                newMetaMap.put("locked", lockMap);
            }

            // 16. 重新组装文件
            String newMetaJson = JsonUtil.toJson(newMetaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n";

            // 17. 写回文件
            Files.write(metaFile.toPath(), newFullContent.getBytes(StandardCharsets.UTF_8));

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("写入目录元数据失败: " + e.getMessage());
            return new String[] { "ERROR", "WRITE_FAILED" };
        }
    }

    /**
     * 创建目录元信息文件
     * 
     * @param path 目录路径（结尾是/）
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] createDirectoryMetaData(String path) {
        // 1. 检查路径
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. 确保路径以/结尾
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 3. 获取根目录
        String root = getVfsRoot();

        // 4. 转换为真实路径
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 5. 检查目录是否存在
        File dir = new File(realPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 6. 检查是否是目录
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 7. 检查目录是否被锁定
        String[] lockCheck = checkDirectoryLock(dir);
        if (lockCheck != null) {
            return lockCheck;
        }

        // 8. 元数据文件路径
        File metaFile = new File(dir, ".META");

        // 9. 检查元数据文件是否已存在
        if (metaFile.exists()) {
            return new String[] { "ERROR", "FILE_EXIST" };
        }

        try {
            // 10. 获取当前时间
            int[] now = TimeUtil.getTime();

            // 11. 创建元数据
            Map<String, Object> metaMap = new HashMap<>();

            // 时间信息
            Map<String, Object> timeMap = new HashMap<>();
            timeMap.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            timeMap.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            timeMap.put("lastOpenTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            metaMap.put("Time", timeMap);

            // 所有者
            metaMap.put("Owner", "local");

            // 权限
            Map<String, String> permMap = new HashMap<>();
            permMap.put("Owner", "read, write");
            permMap.put("Others", "read");
            metaMap.put("Permission", permMap);

            // 锁状态
            Map<String, Object> lockMap = new HashMap<>();
            lockMap.put("isLocked", false);
            lockMap.put("lockedBy", null);
            metaMap.put("locked", lockMap);

            // 12. 转换为JSON
            String metaJson = JsonUtil.toJson(metaMap);

            // 13. 创建文件内容
            String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";

            // 14. 写入文件
            Files.write(metaFile.toPath(), fileContent.getBytes(StandardCharsets.UTF_8));

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("创建目录元数据失败: " + e.getMessage());
            return new String[] { "ERROR", "CREATE_FAILED" };
        }
    }

    /**
     * 获取目录下的文件和目录列表
     * 
     * @param path 目录路径（结尾是/）
     * @return String[] 数组，[0]是状态，[1...]是文件名/目录名
     */
    public static String[] getListOfFileAndDirectory(String path) {
        // 1. 检查路径
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. 确保路径以/结尾
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        // 3. 获取根目录
        String root = getVfsRoot();

        // 4. 转换为真实路径
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 5. 检查目录是否存在
        File dir = new File(realPath);
        if (!dir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 6. 检查是否是目录
        if (!dir.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_DIRECTORY" };
        }

        // 7. 获取目录下的所有文件
        File[] files = dir.listFiles();
        if (files == null) {
            return new String[] { "SUCCESS" }; // 空目录
        }

        // 8. 收集文件名和目录名（过滤掉 .META 文件）
        java.util.List<String> items = new java.util.ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            // 不显示 .META 文件
            if (name.equals(".META")) {
                continue;
            }
            if (f.isDirectory()) {
                items.add(name + "/"); // 目录后面加/
            } else {
                items.add(name);
            }
        }

        // 9. 按字母顺序排序
        java.util.Collections.sort(items);

        // 10. 构造返回数组
        String[] result = new String[items.size() + 1];
        result[0] = "SUCCESS";
        for (int i = 0; i < items.size(); i++) {
            result[i + 1] = items.get(i);
        }

        return result;
    }

    /**
     * 写入文件
     * 
     * @param path    文件路径
     * @param content 要写入的内容
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] write(String path, String content) {
        // 1. 验证文件
        Object[] validateResult = validateFile(path, true, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        // 2. 检查父目录是否被锁定
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            String[] parentLockCheck = checkDirectoryLock(parentDir);
            if (parentLockCheck != null) {
                return parentLockCheck;
            }
        }

        try {
            // 3. 读取文件现有内容（为了获取元数据）
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // 4. 检查锁状态
            String[] lockCheck = checkLock(file);
            if (lockCheck != null) {
                return lockCheck;
            }

            // 5. 提取元数据
            String metaJson = "{}";
            String[] metaResult = extractMetaContent(fullContent);
            if (metaResult[0].equals("SUCCESS")) {
                metaJson = metaResult[1];
            }

            // 6. 解析元数据
            Object metaObj = JsonUtil.readJson(metaJson);
            Map<String, Object> metaMap;

            if (metaObj instanceof Map) {
                metaMap = (Map<String, Object>) metaObj;
            } else {
                metaMap = new HashMap<>();
            }

            // 7. 确保有locked字段
            if (!metaMap.containsKey("locked")) {
                Map<String, Object> lockMap = new HashMap<>();
                lockMap.put("isLocked", false);
                lockMap.put("lockedBy", null);
                metaMap.put("locked", lockMap);
            }

            // 8. 更新元数据中的时间
            Map<String, Object> time = (Map<String, Object>) metaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                metaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // 9. 确保有创建时间
            if (!time.containsKey("createTime")) {
                time.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            }

            // 10. 重新组装文件（保留元数据，更新正文）
            String newMetaJson = JsonUtil.toJson(metaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + content;

            // 11. 写回文件
            Files.write(file.toPath(), newFullContent.getBytes());

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("写入文件失败: " + e.getMessage());
            return new String[] { "ERROR", "WRITE_FAILED" };
        }
    }

    /**
     * 锁定文件
     * 
     * @param path 文件路径
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] lock(String path) {
        // 1. 验证文件
        Object[] validateResult = validateFile(path, false, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        // 2. 检查父目录是否被锁定
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            String[] parentLockCheck = checkDirectoryLock(parentDir);
            if (parentLockCheck != null) {
                return parentLockCheck;
            }
        }

        try {
            // 3. 读取文件全部内容
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // 4. 分离元数据和正文
            String[] metaResult = extractMetaContent(fullContent);
            String metaJson;
            String bodyContent;

            if (metaResult[0].equals("SUCCESS")) {
                // 有元数据
                metaJson = metaResult[1];
                bodyContent = extractBodyContent(fullContent);
            } else {
                // 没有元数据，创建默认的
                metaJson = "{}";
                bodyContent = fullContent;
            }

            // 5. 解析元数据
            Object metaObj = JsonUtil.readJson(metaJson);
            Map<String, Object> metaMap;

            if (metaObj instanceof Map) {
                metaMap = (Map<String, Object>) metaObj;
            } else {
                metaMap = new HashMap<>();
            }

            // 6. 获取或创建 locked 字段
            Map<String, Object> locked = (Map<String, Object>) metaMap.get("locked");
            if (locked == null) {
                locked = new HashMap<>();
                metaMap.put("locked", locked);
            }

            // 7. 检查是否已锁定
            Boolean isLocked = (Boolean) locked.get("isLocked");
            if (isLocked != null && isLocked) {
                return new String[] { "ERROR", "FILE_IS_LOCKED" };
            }

            // 8. 锁定文件
            locked.put("isLocked", true);
            locked.put("lockedBy", "current_process"); // 暂时写死，后面会改成真实进程ID

            // 9. 更新元数据中的时间
            Map<String, Object> time = (Map<String, Object>) metaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                metaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // 10. 重新组装文件
            String newMetaJson = JsonUtil.toJson(metaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + bodyContent;

            // 11. 写回文件
            Files.write(file.toPath(), newFullContent.getBytes());

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("锁定文件失败: " + e.getMessage());
            return new String[] { "ERROR", "LOCK_FAILED" };
        }
    }

    /**
     * 解锁文件
     * 
     * @param path 文件路径
     * @return String[] 数组，[0]是状态，[1]是错误码（如果有）
     */
    public static String[] unlock(String path) {
        // 1. 验证文件
        Object[] validateResult = validateFile(path, false, true);
        if (validateResult[1] != null) {
            return (String[]) validateResult[1];
        }
        File file = (File) validateResult[0];

        // 2. 检查父目录是否被锁定
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            String[] parentLockCheck = checkDirectoryLock(parentDir);
            if (parentLockCheck != null) {
                return parentLockCheck;
            }
        }

        try {
            // 3. 读取文件全部内容
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // 4. 分离元数据和正文
            String[] metaResult = extractMetaContent(fullContent);
            String metaJson;
            String bodyContent;

            if (metaResult[0].equals("SUCCESS")) {
                metaJson = metaResult[1];
                bodyContent = extractBodyContent(fullContent);
            } else {
                return new String[] { "ERROR", "FILE_IS_NOT_LOCKED" }; // 没有元数据，肯定没锁
            }

            // 5. 解析元数据
            Object metaObj = JsonUtil.readJson(metaJson);
            if (!(metaObj instanceof Map)) {
                return new String[] { "ERROR", "FILE_IS_NOT_LOCKED" };
            }

            Map<String, Object> metaMap = (Map<String, Object>) metaObj;

            // 6. 获取 locked 字段
            Map<String, Object> locked = (Map<String, Object>) metaMap.get("locked");
            if (locked == null) {
                return new String[] { "ERROR", "FILE_IS_NOT_LOCKED" };
            }

            // 7. 检查是否已锁定
            Boolean isLocked = (Boolean) locked.get("isLocked");
            if (isLocked == null || !isLocked) {
                return new String[] { "ERROR", "FILE_IS_NOT_LOCKED" };
            }

            // 8. 解锁文件
            locked.put("isLocked", false);
            locked.put("lockedBy", null);

            // 9. 更新元数据中的时间
            Map<String, Object> time = (Map<String, Object>) metaMap.get("Time");
            if (time == null) {
                time = new HashMap<>();
                metaMap.put("Time", time);
            }
            int[] now = TimeUtil.getTime();
            time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

            // 10. 重新组装文件
            String newMetaJson = JsonUtil.toJson(metaMap);
            String newFullContent = "#<META>\n" + newMetaJson + "\n<META>#\n" + bodyContent;

            // 11. 写回文件
            Files.write(file.toPath(), newFullContent.getBytes());

            return new String[] { "SUCCESS", null };

        } catch (IOException e) {
            LOGGER.warning("解锁文件失败: " + e.getMessage());
            return new String[] { "ERROR", "UNLOCK_FAILED" };
        }
    }

    /**
     * 读取文件
     * 
     * @param path 文件路径
     * @return String[] 数组，[0]是状态，[1]是内容或错误码
     */
    public static String[] read(String path) {
        // 1. 检查路径
        if (path == null || path.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PATH" };
        }

        // 2. 获取根目录
        String root = getVfsRoot();

        // 3. 转换为真实路径
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', File.separatorChar);

        // 4. 检查是否是链接文件，如果是则操作目标文件
        String targetPath = resolveLink(realPath);

        // 5. 检查父目录是否存在
        File file = new File(targetPath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            return new String[] { "ERROR", "DIRECTORY_DOES_NOT_EXIST" };
        }

        // 6. 检查文件是否存在
        if (!file.exists()) {
            return new String[] { "ERROR", "FILE_DOES_NOT_EXIST" };
        }

        // 7. 检查是否是文件
        if (file.isDirectory()) {
            return new String[] { "ERROR", "IS_NOT_FILE" };
        }

        // 8. 读取文件
        try {
            String fullContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String bodyContent = extractBodyContent(fullContent);
            return new String[] { "SUCCESS", bodyContent };
        } catch (IOException e) {
            LOGGER.warning("读取文件失败: " + e.getMessage());
            return new String[] { "ERROR", "READ_FAILED" };
        }
    }

    /**
     * 提取元数据内容
     */
    private static String[] extractMetaContent(String fullContent) {
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
     * 提取正文内容
     */
    private static String extractBodyContent(String fullContent) {
        if (fullContent == null) {
            return "";
        }

        String metaEnd = "<META>#";
        int endIndex = fullContent.indexOf(metaEnd);
        if (endIndex == -1) {
            return fullContent; // 没有元数据，返回全部内容
        }
        // 返回元数据结束之后的内容
        return fullContent.substring(endIndex + metaEnd.length()).trim();
    }

    /**
     * 获取VFS根目录（从配置文件读）
     */
    public static String getVfsRoot() {
        if (VFS_ROOT != null) {
            return VFS_ROOT; // 已经获取过了
        }

        try {
            // 先要找到JAR所在目录
            String jarDir = getJarDirectory();

            // 读取 init.json
            String initPath = jarDir + File.separator + "cilexec_root" +
                    File.separator + "system" + File.separator +
                    "config" + File.separator + "init.json";

            File initFile = new File(initPath);
            if (!initFile.exists()) {
                // 如果找不到，就用默认路径
                VFS_ROOT = jarDir + File.separator + "cilexec_root";
                LOGGER.info("使用默认VFS根目录: " + VFS_ROOT);
                return VFS_ROOT;
            }

            String content = new String(Files.readAllBytes(initFile.toPath()), StandardCharsets.UTF_8);
            Object obj = JsonUtil.readJson(content);

            if (obj instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) obj;
                String root = (String) map.get("root");
                if (root != null && !root.isEmpty()) {
                    VFS_ROOT = root;
                    LOGGER.info("从配置文件读取VFS根目录: " + VFS_ROOT);
                    return VFS_ROOT;
                }
            }

            // 解析失败，用默认路径
            VFS_ROOT = jarDir + File.separator + "cilexec_root";
            LOGGER.info("解析失败，使用默认VFS根目录: " + VFS_ROOT);
            return VFS_ROOT;

        } catch (Exception e) {
            // 出错时用默认路径
            VFS_ROOT = getJarDirectory() + File.separator + "cilexec_root";
            LOGGER.warning("读取配置文件出错: " + e.getMessage() + "，使用默认VFS根目录: " + VFS_ROOT);
            return VFS_ROOT;
        }
    }

    /**
     * 获取JAR所在目录
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
        } catch (Exception e) {
            LOGGER.warning("获取JAR目录失败: " + e.getMessage());
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

}