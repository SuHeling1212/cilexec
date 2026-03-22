package com.follarce.basicUtil;

import java.util.*;

public class UserUtil {

    private static ThreadLocal<String> currentUser = ThreadLocal.withInitial(() -> "local");

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
     */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        
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
        
        return result.length() > 0 ? result.toString() : "/";
    }
    
    /**
     * Check file permission (direct file read, no recursion)
     */
    public static boolean checkFilePermission(String path, String operation) {
        // Local user can do anything
        if (isLocal()) {
            return true;
        }

        // Get real path
        String root = FileUtil.getVfsRoot();
        String normalized = normalizePath(path);
        String realPath = root + normalized.replace('/', java.io.File.separatorChar);
        java.io.File file = new java.io.File(realPath);

        if (!file.exists()) {
            return false;
        }

        // Parse metadata from file content
        try {
            String content = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                        java.nio.charset.StandardCharsets.UTF_8);
            String[] metaResult = extractMetaContent(content);
            if (!metaResult[0].equals("SUCCESS")) {
                return false;
            }

            Map<String, Object> meta = (Map<String, Object>) JsonUtil.readJson(metaResult[1]);
            String owner = (String) meta.get("Owner");
            String currentUserStr = getCurrentUser();

            // Get permission map
            Map<String, String> perm = (Map<String, String>) meta.get("Permission");
            if (perm == null) {
                return false;
            }

            // Check owner permission
            if (owner != null && owner.equals(currentUserStr)) {
                String ownerPerm = perm.get("Owner");
                if (ownerPerm != null && ownerPerm.contains(operation)) {
                    return true;
                }
                return false; // Owner exists but doesn't have permission
            }

            // Check others permission
            String othersPerm = perm.get("Others");
            if (othersPerm != null && othersPerm.contains(operation)) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }

        return false;
    }
    
    /**
     * Extract meta content from file
     * Delegates to FileUtil to avoid code duplication
     */
    private static String[] extractMetaContent(String fullContent) {
        return FileUtil.extractMetaContent(fullContent);
    }
    
    /**
     * Check process permission
     */
    public static boolean checkProcessPermission(int pid) {
        if (isLocal()) {
            return true;
        }
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            return false;
        }
        
        try {
            Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
            String owner = (String) process.get("Owner");
            return owner != null && owner.equals(currentUser);
        } catch (Exception e) {
            return false;
        }
    }
}