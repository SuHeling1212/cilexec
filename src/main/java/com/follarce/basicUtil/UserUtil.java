package com.follarce.basicUtil;

import java.io.IOException;
import java.util.*;

public class UserUtil {

    /**
     * 获取当前进程的用户
     * 优先从 ProcessRunner 设置的 ThreadLocal 获取
     * 如果未设置，从进程文件读取
     */
    private static ThreadLocal<String> currentProcessUser = new ThreadLocal<>();

    /**
     * 设置当前线程对应的进程用户
     * 由 ProcessRunner.executeLine() 在每次执行前调用
     */
    public static void setCurrentProcessUser(String user) {
        currentProcessUser.set(user);
    }

    /**
     * 获取当前进程的用户
     */
    public static String getCurrentUser() {
        // 1. 优先从 ThreadLocal 获取（由 ProcessRunner 设置）
        String user = currentProcessUser.get();
        if (user != null) {
            return user;
        }

        // 2. Fallback: 尝试从进程文件读取
        try {
            int pid = com.follarce.process.ProcessFunc.getPID();
            if (pid > 0) {
                String processOwner = getProcessOwner(pid);
                if (processOwner != null) {
                    return processOwner;
                }
            }
        } catch (Exception e) {
            // 忽略，返回默认值
        }

        // 3. 最终 fallback
        return "local";
    }

    /**
     * 从进程文件读取 Owner
     */
    private static String getProcessOwner(int pid) {
        String root = FileUtil.getVfsRoot();
        String processPath = root + "/system/process/" + pid + ".json";
        java.io.File file = new java.io.File(processPath);

        if (!file.exists()) {
            return null;
        }

        try {
            String content = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            // 提取文件体（跳过元数据）
            String[] metaResult = FileUtil.extractMetaContent(content);
            String body = metaResult.length > 2 ? metaResult[2] : content;

            Object obj = JsonUtil.readJson(body);
            if (obj instanceof Map) {
                Map<String, Object> process = (Map<String, Object>) obj;
                return (String) process.get("Owner");
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }

    /**
     * 清除当前线程的进程用户（线程结束时调用）
     */
    public static void clearCurrentProcessUser() {
        currentProcessUser.remove();
    }

    public static boolean isLocal() {
        return "local".equals(getCurrentUser());
    }

    public static class PermissionResult {
        private final boolean success;
        private final String errorMessage;
        private final String errorContext;

        public PermissionResult(boolean success, String errorMessage, String errorContext) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.errorContext = errorContext;
        }

        public static PermissionResult success() {
            return new PermissionResult(true, null, null);
        }

        public static PermissionResult failure(String errorMessage, String errorContext) {
            return new PermissionResult(false, errorMessage, errorContext);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getErrorContext() {
            return errorContext;
        }

        @Override
        public String toString() {
            if (success) {
                return "PermissionResult[SUCCESS]";
            }
            return String.format("PermissionResult[FAILED: %s, Context: %s]", errorMessage, errorContext);
        }
    }

    /**
     * Normalize path (handle .. and .)
     */
    private static String normalizePath(String path) {
        return FileUtil.normalizePath(path);
    }

    /**
     * Extract meta content from file
     */
    private static String[] extractMetaContent(String fullContent) {
        return FileUtil.extractMetaContent(fullContent);
    }

    /**
     * Validate file permission with detailed error information
     */
    public static PermissionResult validatePermission(String path, String operation) {
        String currentUserStr = getCurrentUser();
        String context = String.format("Path: %s, Operation: %s, User: %s", path, operation, currentUserStr);

        // local 用户拥有所有权限
        if (isLocal()) {
            return PermissionResult.success();
        }

        String root = FileUtil.getVfsRoot();
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', java.io.File.separatorChar);
        java.io.File file = new java.io.File(realPath);

        if (!file.exists()) {
            return PermissionResult.failure("File does not exist", context + ", RealPath: " + realPath);
        }

        try {
            String content = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] metaResult = extractMetaContent(content);
            if (!metaResult[0].equals("SUCCESS")) {
                return PermissionResult.failure("Failed to extract metadata", context);
            }

            Map<String, Object> meta = (Map<String, Object>) JsonUtil.readJson(metaResult[1]);
            String owner = (String) meta.get("Owner");

            Map<String, String> perm = (Map<String, String>) meta.get("Permission");
            if (perm == null) {
                return PermissionResult.failure("Permission metadata missing", context);
            }

            // 检查是否是所有者
            if (owner != null && owner.equals(currentUserStr)) {
                String ownerPerm = perm.get("Owner");
                if (ownerPerm != null && ownerPerm.contains(operation)) {
                    return PermissionResult.success();
                }
                return PermissionResult.failure(
                        "Owner permission denied",
                        context + String.format(", Owner: %s, OwnerPermission: %s", owner, ownerPerm));
            }

            // 检查其他人权限
            String othersPerm = perm.get("Others");
            if (othersPerm != null && othersPerm.contains(operation)) {
                return PermissionResult.success();
            }

            return PermissionResult.failure(
                    "Others permission denied",
                    context + String.format(", OthersPermission: %s", othersPerm));
        } catch (IOException e) {
            return PermissionResult.failure("Permission check IO error: " + e.getMessage(), context);
        } catch (ClassCastException e) {
            return PermissionResult.failure("Permission check error: invalid metadata format", context);
        }
    }

    /**
     * Check file permission
     */
    public static boolean checkFilePermission(String path, String operation) {
        return validatePermission(path, operation).isSuccess();
    }

    /**
     * Validate process permission with detailed error information
     */
    public static PermissionResult validateProcessPermission(int pid) {
        String currentUserStr = getCurrentUser();
        String context = String.format("PID: %d, User: %s", pid, currentUserStr);

        if (isLocal()) {
            return PermissionResult.success();
        }

        String processPath = "/system/process/" + pid + ".json";
        String[] readResult = FileUtil.read(processPath);
        if (!readResult[0].equals("SUCCESS")) {
            return PermissionResult.failure("Failed to read process file", context + ", Path: " + processPath);
        }

        try {
            Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
            String owner = (String) process.get("Owner");

            if (owner == null) {
                return PermissionResult.failure("Process owner information missing", context);
            }

            if (!owner.equals(currentUserStr)) {
                return PermissionResult.failure(
                        "Process ownership mismatch",
                        context + String.format(", Owner: %s", owner));
            }

            return PermissionResult.success();
        } catch (ClassCastException e) {
            return PermissionResult.failure("Process permission check error: invalid JSON structure", context);
        }
    }

    /**
     * Check process permission
     */
    public static boolean checkProcessPermission(int pid) {
        return validateProcessPermission(pid).isSuccess();
    }
}