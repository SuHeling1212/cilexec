package com.follarce.util;

import com.follarce.Constants;
import com.follarce.log.Logger;
import com.follarce.util.UserUtil;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 虚拟文件系统 (VFS) 核心操作类。
 * 所有文件操作基于宿主机文件系统，使用元数据+正文格式。
 */
public final class FileUtil {

    private FileUtil() {}

    // ════════════════════════════════════════════
    // 文件操作 API
    // ════════════════════════════════════════════

    /**
     * 读取文件正文内容。
     */
    public static String read(String path) {
        File realFile = resolveFile(path);
        recoverProcessFile(path, realFile);
        validateFile(realFile, path);
        ReentrantLock lock = com.follarce.util.JsonUtil.lockFile(path);
        try {
            String content = readFileContent(realFile);
            return PathUtil.extractBodyContent(content);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 写入文件（保留元数据，更新正文）。
     */
    public static void write(String path, String content) {
        File realFile = resolveFile(path);
        if (!realFile.exists()) {
            throw new RuntimeException("File not found: " + path);
        }
        // 读取旧的元数据
        String existingContent = readFileContent(realFile);
        String metaJson = PathUtil.extractMetaContent(existingContent);

        if (metaJson != null) {
            // 更新 lastEditTime 和文件大小
            Map<String, Object> meta = JsonUtil.parseToMap(metaJson);
            updateTimeField(meta, "lastEditTime");
            meta.put("Size", new Object[]{content.length(), "B"});
            String newMetaJson = JsonUtil.toMetaJson(meta);
            String newContent = PathUtil.buildMetaFile(newMetaJson, content);
            writeFileContent(realFile, newContent);
        } else {
            // 没有元数据，创建默认元数据
            Map<String, Object> defaultMeta = createDefaultFileMeta();
            defaultMeta.put("Size", new Object[]{content.length(), "B"});
            String newMetaJson = JsonUtil.toMetaJson(defaultMeta);
            String newContent = PathUtil.buildMetaFile(newMetaJson, content);
            writeFileContent(realFile, newContent);
        }
    }

    /**
     * 追加内容到文件尾部。
     */
    public static void append(String path, String content) {
        String existing = read(path);
        write(path, existing + content);
    }

    /**
     * 创建文件。
     */
    public static void createFile(String dirPath, String name) {
        String fullPath = PathUtil.resolvePath(dirPath) + "/" + name;
        File realFile = resolveFile(fullPath);
        if (realFile.exists()) {
            throw new RuntimeException("File already exists: " + fullPath);
        }
        try {
            realFile.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create file: " + fullPath, e);
        }
        // 写入默认元数据
        Map<String, Object> defaultMeta = createDefaultFileMeta();
        // 将文件 Owner 设为当前用户（而非固定为 local），使创建者有权写入
        String currentUser = UserUtil.getCurrentUser();
        if (currentUser != null) {
            defaultMeta.put("Owner", currentUser);
        }
        String metaJson = JsonUtil.toMetaJson(defaultMeta);
        String content = PathUtil.buildMetaFile(metaJson, "");
        writeFileContent(realFile, content);
    }

    /**
     * 删除文件。
     */
    public static void removeFile(String path) {
        ReentrantLock lock = JsonUtil.lockFile(path);
        try {
            File realFile = resolveFile(path);
            validateFile(realFile, path);
            checkLock(path);
            if (realFile.isDirectory()) {
                throw new RuntimeException("Is a directory: " + path);
            }
            if (!realFile.delete()) {
                throw new RuntimeException("Failed to delete file: " + path);
            }
            if (path.endsWith(".proc")) {
                try {
                    Files.deleteIfExists(realFile.toPath().resolveSibling(realFile.getName() + ".tmp"));
                } catch (IOException e) {
                    Logger.warn("Failed to remove process temporary file: " + path + ".tmp");
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // ════════════════════════════════════════════
    // 目录操作 API
    // ════════════════════════════════════════════

    /**
     * 创建目录。
     */
    public static void createDirectory(String dirPath, String name) {
        String fullPath = PathUtil.resolvePath(dirPath) + "/" + name;
        File realDir = resolveFile(fullPath);
        if (realDir.exists()) {
            throw new RuntimeException("Directory already exists: " + fullPath);
        }
        if (!realDir.mkdirs()) {
            throw new RuntimeException("Failed to create directory: " + fullPath);
        }
        // 创建 .META 文件
        createDirectoryMetaData(fullPath);
    }

    /**
     * 删除空目录。
     */
    public static void removeDirectory(String path) {
        File realFile = resolveFile(path);
        validateFile(realFile, path);
        checkLock(path);
        if (!realFile.isDirectory()) {
            throw new RuntimeException("Not a directory: " + path);
        }
        String[] listing = realFile.list();
        if (listing != null && listing.length > 0) {
            // 忽略 .META 文件
            long nonMeta = Arrays.stream(listing).filter(f -> !f.equals(Constants.META_DIR_FILE)).count();
            if (nonMeta > 0) {
                throw new RuntimeException("Directory not empty: " + path);
            }
        }
        if (!deleteDirectory(realFile)) {
            throw new RuntimeException("Failed to delete directory: " + path);
        }
    }

    /**
     * 重命名文件或目录。
     */
    public static void rename(String path, String newName) {
        File realFile = resolveFile(path);
        validateFile(realFile, path);
        checkLock(path);

        String parentPath = PathUtil.getParentPath(path);
        File parentDir = resolveFile(parentPath);
        File newFile = new File(parentDir, newName);

        if (newFile.exists()) {
            throw new RuntimeException("Target already exists: " + newName);
        }
        if (!realFile.renameTo(newFile)) {
            throw new RuntimeException("Failed to rename: " + path + " to " + newName);
        }
    }

    // ════════════════════════════════════════════
    // 链接系统
    // ════════════════════════════════════════════

    /**
     * 创建链接文件。
     */
    public static void link(String dirPath, String targetPath) {
        String resolvedTarget = PathUtil.resolvePath(targetPath);
        String name = getLinkNameFromTarget(resolvedTarget);
        String fullPath = PathUtil.resolvePath(dirPath) + "/" + name;

        File realFile = resolveFile(fullPath);
        if (realFile.exists()) {
            throw new RuntimeException("Link target already exists: " + fullPath);
        }

        try {
            realFile.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create link: " + fullPath, e);
        }

        // 创建带 Link 字段的元数据
        Map<String, Object> meta = createDefaultFileMeta();
        meta.put("Link", resolvedTarget);
        String metaJson = JsonUtil.toMetaJson(meta);
        String content = PathUtil.buildMetaFile(metaJson, "");
        writeFileContent(realFile, content);
    }

    /**
     * 递归解析链接，支持链式链接。
     */
    public static String resolveLink(String path) {
        Set<String> visited = new LinkedHashSet<>();
        String current = PathUtil.resolvePath(path);
        int maxDepth = 10;

        while (maxDepth-- > 0) {
            if (!visited.add(current)) {
                throw new RuntimeException("Circular link detected: " + path);
            }
            File realFile = resolveFile(current);
            if (!realFile.exists()) return current;
            if (realFile.isDirectory()) return current;

            String content = readFileContent(realFile);
            String metaJson = PathUtil.extractMetaContent(content);
            if (metaJson == null) return current;

            Map<String, Object> meta = JsonUtil.parseToMap(metaJson);
            Object linkTarget = meta.get("Link");
            if (linkTarget instanceof String) {
                current = PathUtil.resolvePath((String) linkTarget);
            } else {
                return current;
            }
        }
        throw new RuntimeException("Link chain too deep: " + path);
    }

    // ════════════════════════════════════════════
    // 文件锁定系统
    // ════════════════════════════════════════════

    /**
     * 锁定文件。
     */
    public static void lock(String path, int pid) {
        String resolvedPath = PathUtil.resolvePath(path);
        Map<String, Object> meta = readMetaData(resolvedPath);
        Map<String, Object> locked = getOrCreateLocked(meta);
        locked.put("isLocked", true);
        locked.put("lockedBy", pid);
        writeMetaData(resolvedPath, meta);
    }

    /**
     * 解锁文件。
     */
    public static void unlock(String path, int pid, String currentUser) {
        String resolvedPath = PathUtil.resolvePath(path);
        Map<String, Object> meta = readMetaData(resolvedPath);
        Map<String, Object> locked = getLocked(meta);
        if (locked == null) return;

        Object lockedBy = locked.get("lockedBy");
        boolean isOwner = lockedBy instanceof Number && ((Number) lockedBy).intValue() == pid;
        boolean isLocal = "local".equals(currentUser);

        if (isOwner || isLocal) {
            locked.put("isLocked", false);
            locked.put("lockedBy", null);
            writeMetaData(resolvedPath, meta);
        } else {
            throw new RuntimeException("Not authorized to unlock: " + path);
        }
    }

    /**
     * 检查并自动释放崩溃进程的锁。
     */
    public static void checkAndValidateLock(String path, Set<Integer> activePids) {
        String resolvedPath = PathUtil.resolvePath(path);
        Map<String, Object> meta = readMetaData(resolvedPath);
        Map<String, Object> locked = getLocked(meta);
        if (locked == null || !Boolean.TRUE.equals(locked.get("isLocked"))) return;

        Object lockedBy = locked.get("lockedBy");
        if (lockedBy instanceof Number) {
            int pid = ((Number) lockedBy).intValue();
            if (!activePids.contains(pid)) {
                Logger.warn("Auto-releasing lock on " + path + " from crashed PID " + pid);
                locked.put("isLocked", false);
                locked.put("lockedBy", null);
                writeMetaData(resolvedPath, meta);
            }
        }
    }

    /**
     * 检查文件是否被锁定。
     */
    public static void checkLock(String path) {
        String resolvedPath = PathUtil.resolvePath(path);
        Map<String, Object> meta = readMetaData(resolvedPath);
        Map<String, Object> locked = getLocked(meta);
        if (locked != null && Boolean.TRUE.equals(locked.get("isLocked"))) {
            throw new RuntimeException("File is locked: " + path + " by PID " + locked.get("lockedBy"));
        }
    }

    // ════════════════════════════════════════════
    // 元数据操作
    // ════════════════════════════════════════════

    /**
     * 读取文件的元数据（已解析为 Map）。
     */
    public static Map<String, Object> readFileMetaData(String path) {
        String resolvedPath = PathUtil.resolvePath(path);
        return readMetaData(resolvedPath);
    }

    /**
     * 写入文件的元数据（保留正文）。
     */
    public static void writeFileMetaData(String path, Map<String, Object> metaContent) {
        String resolvedPath = PathUtil.resolvePath(path);
        writeMetaData(resolvedPath, metaContent);
    }

    /**
     * 读取目录元数据。
     */
    public static Map<String, Object> readDirectoryMetaData(String path) {
        String dirMetaPath = PathUtil.resolvePath(path) + "/" + Constants.META_DIR_FILE;
        return readFileMetaData(dirMetaPath);
    }

    /**
     * 写入目录元数据。
     */
    public static void writeDirectoryMetaData(String path, Map<String, Object> metaContent) {
        String dirMetaPath = PathUtil.resolvePath(path) + "/" + Constants.META_DIR_FILE;
        writeFileMetaData(dirMetaPath, metaContent);
    }

    /**
     * 创建目录的 .META 文件。
     */
    public static void createDirectoryMetaData(String dirPath) {
        String resolvedPath = PathUtil.resolvePath(dirPath);
        File dirFile = resolveFile(resolvedPath);
        File metaFile = new File(dirFile, Constants.META_DIR_FILE);
        if (metaFile.exists()) return;

        try {
            metaFile.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create .META for " + dirPath, e);
        }

        Map<String, Object> meta = createDefaultDirMeta();
        String metaJson = JsonUtil.toMetaJson(meta);
        String content = PathUtil.buildMetaFile(metaJson, "");
        writeFileContent(metaFile, content);
    }

    // ════════════════════════════════════════════
    // 目录列表
    // ════════════════════════════════════════════

    /**
     * 获取目录中的文件和目录列表（过滤 .META）。
     */
    public static List<Map<String, Object>> getListOfFileAndDirectory(String path) {
        String resolvedPath = PathUtil.resolvePath(path);
        File realDir = resolveFile(resolvedPath);
        if (!realDir.isDirectory()) {
            throw new RuntimeException("Not a directory: " + path);
        }

        File[] files = realDir.listFiles();
        if (files == null) return new ArrayList<>();

        List<Map<String, Object>> result = new ArrayList<>();
        for (File f : files) {
            if (f.getName().equals(Constants.META_DIR_FILE)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", f.getName());
            entry.put("isDirectory", f.isDirectory());
            entry.put("size", f.length());
            result.add(entry);
        }
        return result;
    }

    // ════════════════════════════════════════════
    // 权限检查
    // ════════════════════════════════════════════

    /**
     * 检查文件权限。
     */
    public static boolean checkFilePermission(String path, String operation, String currentUser) {
        PermissionResult result = validatePermission(path, operation, currentUser);
        return result.granted;
    }

    /**
     * 验证文件权限，返回详细信息。
     */
    public static PermissionResult validatePermission(String path, String operation, String currentUser) {
        String resolvedPath = PathUtil.resolvePath(path);
        File realFile = resolveFile(resolvedPath);

        // local 用户有全部权限
        if ("local".equals(currentUser)) {
            return new PermissionResult(true, "local user: full access");
        }

        try {
            Map<String, Object> meta;
            if (realFile.isDirectory()) {
                meta = readDirectoryMetaData(resolvedPath);
            } else {
                meta = readMetaData(resolvedPath);
            }

            if (meta == null) {
                return new PermissionResult(false, "No metadata found");
            }

            Object ownerObj = meta.get("Owner");
            String owner = ownerObj instanceof String ? (String) ownerObj : "";

            Map<String, Object> perm = getPermission(meta);
            if (perm == null) {
                return new PermissionResult(false, "No permission field");
            }

            if (owner.equals(currentUser)) {
                String ownerPerms = perm.getOrDefault("Owner", "").toString();
                if (ownerPerms.contains(operation)) {
                    return new PermissionResult(true, "Owner permission granted");
                }
            } else {
                String othersPerms = perm.getOrDefault("Others", "").toString();
                if (othersPerms.contains(operation)) {
                    return new PermissionResult(true, "Others permission granted");
                }
            }

            return new PermissionResult(false, "Permission denied: " + currentUser
                    + " needs " + operation + " on " + path);
        } catch (Exception e) {
            return new PermissionResult(false, "Permission check error: " + e.getMessage());
        }
    }

    /**
     * 权限检查结果。
     */
    public static class PermissionResult {
        public final boolean granted;
        public final String message;
        public PermissionResult(boolean granted, String message) {
            this.granted = granted;
            this.message = message;
        }
    }

    // ════════════════════════════════════════════
    // 安全校验
    // ════════════════════════════════════════════

    /**
     * 校验文件访问安全性。
     */
    public static void validateFile(File file, String originalPath) {
        if (file == null) return;

        // 检查路径是否包含 ..
        String normalized = PathUtil.resolvePath(originalPath);
        if (normalized.contains("..")) {
            throw new SecurityException("Path traversal detected: " + originalPath);
        }

        // canonical 路径检查
        try {
            if (PathUtil.getVfsRoot() != null) {
                String canonicalRoot = PathUtil.getVfsRoot().getCanonicalPath();
                String canonicalFile = file.getCanonicalPath();
                if (!canonicalFile.startsWith(canonicalRoot)) {
                    throw new SecurityException("Path escape detected: " + originalPath
                            + " -> " + canonicalFile + " outside root " + canonicalRoot);
                }
            }
        } catch (IOException e) {
            throw new SecurityException("Path validation error: " + e.getMessage());
        }
    }

    /**
     * 检查符号链接链。
     */
    public static void checkSymlinkChain(File file) {
        try {
            Set<String> visited = new LinkedHashSet<>();
            File current = file;
            int maxDepth = 10;

            while (maxDepth-- > 0) {
                if (Files.isSymbolicLink(current.toPath())) {
                    String real = current.getCanonicalPath();
                    if (!visited.add(real)) {
                        throw new SecurityException("Circular symlink detected: " + file);
                    }
                    current = new File(real);
                } else {
                    return;
                }
            }
            throw new SecurityException("Symlink chain too deep: " + file);
        } catch (IOException e) {
            // not a symlink or error, ignore
        }
    }

    // ════════════════════════════════════════════
    // 内部辅助方法
    // ════════════════════════════════════════════

    /**
     * 将 VFS 路径解析为宿主机 File 对象。
     */
    private static File resolveFile(String path) {
        String realPath = PathUtil.toRealPath(path);
        return new File(realPath);
    }

    /**
     * 读取文件全部内容为字符串。
     */
    static String readFileContent(File file) {
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * 将字符串写入文件。
     */
    static void writeFileContent(File file, String content) {
        try {
            Files.writeString(file.toPath(), content, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * 原子写入：持锁 → 写 .tmp 临时文件 → 原子重命名替换原文件 → 释放锁。
     * 锁保证写入期间无读者，重命名保证文件始终存在（旧或新），不会出现「读到一半」的窗口。
     */
    public static void writeAtomic(String path, String content) {
        File realFile = resolveFile(path);
        ReentrantLock lock = JsonUtil.lockFile(path);
        try {
            File parent = realFile.getParentFile();
            if (parent == null) parent = new File(".");
            parent.mkdirs();
            File tempFile = new File(parent, realFile.getName() + ".tmp");
            try {
                String finalContent;
                if (realFile.exists()) {
                    String existingContent = readFileContent(realFile);
                    String metaJson = PathUtil.extractMetaContent(existingContent);
                    if (metaJson != null) {
                        Map<String, Object> meta = JsonUtil.parseToMap(metaJson);
                        updateTimeField(meta, "lastEditTime");
                        meta.put("Size", new Object[]{content.length(), "B"});
                        finalContent = PathUtil.buildMetaFile(JsonUtil.toMetaJson(meta), content);
                    } else {
                        Map<String, Object> defaultMeta = createDefaultFileMeta();
                        defaultMeta.put("Size", new Object[]{content.length(), "B"});
                        finalContent = PathUtil.buildMetaFile(JsonUtil.toMetaJson(defaultMeta), content);
                    }
                } else {
                    // 新文件也通过临时文件提交，避免首次写入被中断后留下半文件。
                    Map<String, Object> defaultMeta = createDefaultFileMeta();
                    defaultMeta.put("Size", new Object[]{content.length(), "B"});
                    finalContent = PathUtil.buildMetaFile(JsonUtil.toMetaJson(defaultMeta), content);
                }

                Files.writeString(tempFile.toPath(), finalContent,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                try (FileChannel channel = FileChannel.open(tempFile.toPath(), StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                Files.move(tempFile.toPath(), realFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                forceDirectory(parent.toPath());
            } catch (IOException e) {
                // 保留临时文件，进程文件可在下次启动时验证并恢复。
                throw new RuntimeException("Failed to write file: " + path, e);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取 VFS 路径的元数据（返回 Map）。
     */
    private static Map<String, Object> readMetaData(String resolvedPath) {
        File realFile = resolveFile(resolvedPath);
        if (!realFile.exists()) return new LinkedHashMap<>();

        String content = readFileContent(realFile);
        String metaJson = PathUtil.extractMetaContent(content);

        if (metaJson == null || metaJson.isEmpty()) return new LinkedHashMap<>();
        Object parsed = JsonUtil.parseJson(metaJson);
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) parsed;
            return result;
        }
        return new LinkedHashMap<>();
    }

    /**
     * 写入元数据到 VFS 路径（保留正文）。
     */
    private static void writeMetaData(String resolvedPath, Map<String, Object> meta) {
        File realFile = resolveFile(resolvedPath);
        String existingContent = "";
        if (realFile.exists()) {
            existingContent = readFileContent(realFile);
        }
        String body = PathUtil.extractBodyContent(existingContent);
        String metaJson = JsonUtil.toMetaJson(meta);
        String newContent = PathUtil.buildMetaFile(metaJson, body);
        writeFileContent(realFile, newContent);
    }

    /**
     * 获取或创建 locked 字段。
     */
    private static Map<String, Object> getOrCreateLocked(Map<String, Object> meta) {
        @SuppressWarnings("unchecked")
        Map<String, Object> locked = (Map<String, Object>) meta.get("locked");
        if (locked == null) {
            locked = new LinkedHashMap<>();
            locked.put("isLocked", false);
            locked.put("lockedBy", null);
            meta.put("locked", locked);
        }
        return locked;
    }

    /**
     * 获取 locked 字段。
     */
    private static Map<String, Object> getLocked(Map<String, Object> meta) {
        @SuppressWarnings("unchecked")
        Map<String, Object> locked = (Map<String, Object>) meta.get("locked");
        return locked;
    }

    /**
     * 获取 Permission 字段。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getPermission(Map<String, Object> meta) {
        Object permObj = meta.get("Permission");
        if (permObj instanceof Map) {
            return (Map<String, Object>) permObj;
        }
        return null;
    }

    /**
     * 更新 Time 字段中的指定子字段为当前时间。
     */
    private static void updateTimeField(Map<String, Object> meta, String fieldName) {
        @SuppressWarnings("unchecked")
        Map<String, Object> time = (Map<String, Object>) meta.get("Time");
        if (time != null) {
            time.put(fieldName, getCurrentTimeArray());
        }
    }

    /**
     * 创建默认文件元数据。
     */
    public static Map<String, Object> createDefaultFileMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("Time", createDefaultTime());
        meta.put("Owner", Constants.DEFAULT_USER_LOCAL);

        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put(Constants.PERM_OWNER, Constants.PERM_READ + ", " + Constants.PERM_WRITE);
        perm.put(Constants.PERM_OTHERS, Constants.PERM_READ);
        meta.put("Permission", perm);

        Map<String, Object> locked = new LinkedHashMap<>();
        locked.put("isLocked", false);
        locked.put("lockedBy", null);
        meta.put("locked", locked);

        meta.put("Size", new Object[]{0, "B"});

        return meta;
    }

    /**
     * 创建默认目录元数据。
     */
    public static Map<String, Object> createDefaultDirMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("Time", createDefaultTime());
        meta.put("Owner", Constants.DEFAULT_DIR_OWNER);

        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put(Constants.PERM_OWNER, Constants.PERM_READ + ", " + Constants.PERM_WRITE);
        perm.put(Constants.PERM_OTHERS, Constants.PERM_READ);
        meta.put("Permission", perm);

        Map<String, Object> locked = new LinkedHashMap<>();
        locked.put("isLocked", false);
        locked.put("lockedBy", null);
        meta.put("locked", locked);

        return meta;
    }

    /**
     * 创建默认时间对象。
     */
    private static Map<String, Object> createDefaultTime() {
        Map<String, Object> time = new LinkedHashMap<>();
        int[] now = getCurrentTimeArray();
        time.put("createTime", now);
        time.put("lastEditTime", now);
        time.put("lastOpenTime", now);
        return time;
    }

    /**
     * 获取当前时间数组 [Year, Month, Day, Hour, Minute, Second, Millisecond]。
     */
    public static int[] getCurrentTimeArray() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        return new int[]{
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND),
                cal.get(java.util.Calendar.MILLISECOND)
        };
    }

    /**
     * 根据链接目标生成链接文件名。
     */
    private static String getLinkNameFromTarget(String targetPath) {
        String name = PathUtil.getFileName(targetPath);
        if (name.isEmpty()) name = "link";
        return "link_to_" + name;
    }

    /**
     * 递归删除目录。
     */
    private static boolean deleteDirectory(File dir) {
        File[] allContents = dir.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return dir.delete();
    }

    /**
     * 根据路径检查文件是否存在。
     */
    public static boolean exists(String path) {
        String resolvedPath = PathUtil.resolvePath(path);
        File realFile = resolveFile(resolvedPath);
        recoverProcessFile(resolvedPath, realFile);
        return realFile.exists();
    }

    private static void recoverProcessFile(String path, File realFile) {
        if (path == null || !path.endsWith(".proc")) return;
        File tempFile = new File(realFile.getParentFile(), realFile.getName() + ".tmp");
        if (!tempFile.exists()) return;

        ReentrantLock lock = JsonUtil.lockFile(path);
        try {
            boolean realValid = isValidProcessFile(realFile);
            boolean tempValid = isValidProcessFile(tempFile);
            if (!realValid && tempValid) {
                try {
                    Files.move(tempFile.toPath(), realFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    forceDirectory(realFile.getParentFile().toPath());
                    Logger.warn("Recovered interrupted process write: " + path);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to recover process file: " + path, e);
                }
            } else if (realValid) {
                tempFile.delete();
            } else {
                // Preserve both damaged snapshots for diagnosis; the scheduler will reject them.
                Logger.error("Both process snapshots are invalid: " + path + " and " + path + ".tmp");
            }
        } finally {
            lock.unlock();
        }
    }

    private static boolean isValidProcessFile(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return false;
        try {
            String body = PathUtil.extractBodyContent(Files.readString(file.toPath()));
            Map<String, Object> process = JsonUtil.parseToMapStrict(body);
            Object pid = process.get("PID");
            Object program = process.get("Program");
            if (!(pid instanceof Number) || !(program instanceof Map)) return false;
            String name = file.getName().replaceFirst("\\.proc(?:\\.tmp)?$", "");
            return Integer.toString(((Number) pid).intValue()).equals(name);
        } catch (Exception e) {
            return false;
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Atomic rename is still guaranteed; directory fsync is not available everywhere.
        }
    }
}
