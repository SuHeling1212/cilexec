package com.follarce.basicUtil;

import java.io.IOException;
import java.util.*;

public class UserUtil {

    private static ThreadLocal<String> currentUser = ThreadLocal.withInitial(() -> "local");

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

    public static void setCurrentUser(String user) {
        currentUser.set(user);
    }

    public static String getCurrentUser() {
        return currentUser.get();
    }

    public static boolean isLocal() {
        return "local".equals(currentUser.get());
    }
    
    /**
     * Normalize path (handle .. and .)
     * Delegates to FileUtil to avoid code duplication
     */
    private static String normalizePath(String path) {
        return FileUtil.normalizePath(path);
    }
    
    /**
     * Validate file permission with detailed error information
     */
    public static PermissionResult validatePermission(String path, String operation) {
        String context = String.format("Path: %s, Operation: %s, User: %s", path, operation, getCurrentUser());

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
            String currentUserStr = getCurrentUser();

            Map<String, String> perm = (Map<String, String>) meta.get("Permission");
            if (perm == null) {
                return PermissionResult.failure("Permission metadata missing", context);
            }

            if (owner != null && owner.equals(currentUserStr)) {
                String ownerPerm = perm.get("Owner");
                if (ownerPerm != null && ownerPerm.contains(operation)) {
                    return PermissionResult.success();
                }
                return PermissionResult.failure(
                    "Owner permission denied",
                    context + String.format(", Owner: %s, OwnerPermission: %s", owner, ownerPerm)
                );
            }

            String othersPerm = perm.get("Others");
            if (othersPerm != null && othersPerm.contains(operation)) {
                return PermissionResult.success();
            }

            return PermissionResult.failure(
                "Others permission denied",
                context + String.format(", OthersPermission: %s", othersPerm)
            );
        } catch (IOException e) {
            return PermissionResult.failure("Permission check IO error: " + e.getMessage(), context);
        } catch (ClassCastException e) {
            return PermissionResult.failure("Permission check error: invalid metadata format", context);
        }
    }

    /**
     * Check file permission (direct file read, no recursion)
     */
    public static boolean checkFilePermission(String path, String operation) {
        return validatePermission(path, operation).isSuccess();
    }
    
    /**
     * Extract meta content from file
     * Delegates to FileUtil to avoid code duplication
     */
    private static String[] extractMetaContent(String fullContent) {
        return FileUtil.extractMetaContent(fullContent);
    }
    
    /**
     * Validate process permission with detailed error information
     */
    public static PermissionResult validateProcessPermission(int pid) {
        String context = String.format("PID: %d, User: %s", pid, getCurrentUser());

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
            String currentUserStr = getCurrentUser();

            if (owner == null) {
                return PermissionResult.failure("Process owner information missing", context);
            }

            if (!owner.equals(currentUserStr)) {
                return PermissionResult.failure(
                    "Process ownership mismatch",
                    context + String.format(", Owner: %s", owner)
                );
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