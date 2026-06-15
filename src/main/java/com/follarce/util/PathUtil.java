package com.follarce.util;

import com.follarce.Constants;

import java.io.File;
import java.util.*;

/**
 * 路径工具类 —— 路径解析、标准化、别名替换。
 */
public final class PathUtil {

    private static File vfsRoot;
    private static Map<String, String> envAliases = new LinkedHashMap<>();

    private PathUtil() {}

    /**
     * 设置 VFS 根目录。
     */
    public static void setVfsRoot(File root) {
        vfsRoot = root;
    }

    /**
     * 获取 VFS 根目录。
     */
    public static File getVfsRoot() {
        return vfsRoot;
    }

    /**
     * 设置环境变量别名。
     */
    public static void setEnvAliases(Map<String, String> aliases) {
        if (aliases != null) {
            envAliases = new LinkedHashMap<>(aliases);
        }
    }

    /**
     * 获取完整的环境变量别名映射。
     */
    public static Map<String, String> getEnvAliases() {
        return envAliases;
    }

    /**
     * 替换路径中的别名和特殊符号。
     * ~, $HOME → /user/local
     * $SYSTEM → /system
     * 其他 $VAR → envAliases 中查找
     */
    public static String resolvePath(String path) {
        if (path == null || path.isEmpty()) return "/";

        String resolved = path;

        // 替换 ~
        if (resolved.startsWith("~")) {
            resolved = "/user/local" + resolved.substring(1);
        }

        // 替换 $HOME
        if (resolved.startsWith("$HOME")) {
            resolved = "/user/local" + resolved.substring(5);
        }

        // 替换 $SYSTEM
        if (resolved.startsWith("$SYSTEM")) {
            resolved = "/system" + resolved.substring(7);
        }

        // 替换其他 $ 环境变量
        for (Map.Entry<String, String> entry : envAliases.entrySet()) {
            String varRef = "$" + entry.getKey();
            if (resolved.contains(varRef)) {
                resolved = resolved.replace(varRef, entry.getValue());
            }
        }

        return normalizePath(resolved);
    }

    /**
     * 标准化路径：处理 . 和 ..，统一斜杠，校验组件名。
     */
    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";

        // 统一斜杠
        String normalized = path.replace("\\", "/");

        // 确保以 / 开头
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        // 分割组件
        String[] parts = normalized.split("/");
        Deque<String> stack = new ArrayDeque<>();

        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!stack.isEmpty() && !stack.peek().equals("..")) {
                    stack.pop();
                } else {
                    // 不允许超出根
                }
                continue;
            }

            // 白名单校验：仅允许字母/数字/_-.
            if (!isValidPathComponent(part)) {
                // 拒绝非法组件，继续但跳过
                continue;
            }

            // 拒绝以 . 开头的名称（隐藏文件）
            if (part.startsWith(".") && !part.equals(com.follarce.Constants.META_DIR_FILE)) {
                continue;
            }

            stack.push(part);
        }

        // 重建路径
        StringBuilder result = new StringBuilder();
        List<String> reversed = new ArrayList<>(stack);
        Collections.reverse(reversed);
        for (String part : reversed) {
            result.append("/").append(part);
        }

        if (result.length() == 0) return "/";
        return result.toString();
    }

    /**
     * 校验路径组件是否只包含合法字符。
     */
    public static boolean isValidPathComponent(String name) {
        if (name == null || name.isEmpty()) return false;
        // 允许 Unicode 字符（含中文）、字母、数字、下划线、点、连字符、空格
        return name.matches("^[\\w.\\- \\p{L}]+$");
    }

    /**
     * 将 VFS 虚拟路径转换为宿主机真实路径。
     */
    public static String toRealPath(String vfsPath) {
        if (vfsRoot == null) throw new IllegalStateException("VFS root not set");
        String normalized = resolvePath(vfsPath);
        // 去掉开头的 /
        String relative = normalized.startsWith("/") ? normalized.substring(1) : normalized;
        return new File(vfsRoot, relative).getAbsolutePath();
    }

    /**
     * 从 VFS 路径提取文件名。
     */
    public static String getFileName(String path) {
        String normalized = resolvePath(path);
        if (normalized.equals("/")) return "";
        int lastSlash = normalized.lastIndexOf('/');
        return normalized.substring(lastSlash + 1);
    }

    /**
     * 从 VFS 路径提取父目录。
     */
    public static String getParentPath(String path) {
        String normalized = resolvePath(path);
        if (normalized.equals("/")) return "/";
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0) return "/";
        return normalized.substring(0, lastSlash);
    }

    /**
     * 检查路径是否以 .json 结尾。
     */
    public static boolean isJsonFile(String path) {
        return path != null && path.toLowerCase().endsWith(".json");
    }

    // ════════════════════════════════════════════
    // 进程文件命名（.pres）
    // ════════════════════════════════════════════

    private static final String PROCESS_EXT = ".pres";

    /**
     * 将进程名称安全地转为文件名（含 .pres 后缀）。
     */
    public static String getProcessFileName(String processName) {
        String safe = sanitizeFileName(processName);
        return safe + PROCESS_EXT;
    }

    /**
     * 获取进程文件的 VFS 路径。
     */
    public static String getProcessFilePath(String processName) {
        return Constants.SYSTEM_PROCESS_PATH + getProcessFileName(processName);
    }

    /**
     * 根据 PID 扫描进程目录，找到对应的 .pres 文件名。
     * 如果找不到则返回 null。
     */
    @SuppressWarnings("unchecked")
    public static String findProcessFileNameByPid(int pid) {
        String processDir = toRealPath(Constants.SYSTEM_PROCESS_PATH);
        File dir = new File(processDir);
        if (!dir.exists()) return null;
        File[] files = dir.listFiles((d, name) -> name.endsWith(PROCESS_EXT));
        if (files == null) return null;
        for (File f : files) {
            try {
                String content = FileUtil.read(Constants.SYSTEM_PROCESS_PATH + f.getName());
                if (content == null) continue;
                Map<String, Object> data = JsonUtil.parseToMap(content);
                Object pidObj = data.get("PID");
                if (pidObj instanceof Number && ((Number) pidObj).intValue() == pid) {
                    return f.getName();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 根据 PID 扫描进程目录，找到对应的 .pres 文件完整路径。
     * 如果找不到则返回 null。
     */
    public static String findProcessFilePathByPid(int pid) {
        String fileName = findProcessFileNameByPid(pid);
        if (fileName == null) return null;
        return Constants.SYSTEM_PROCESS_PATH + fileName;
    }

    /**
     * 扫描进程目录，返回 PID → 文件名 的映射。
     */
    @SuppressWarnings("unchecked")
    public static Map<Integer, String> scanProcessFileNames() {
        Map<Integer, String> result = new LinkedHashMap<>();
        String processDir = toRealPath(Constants.SYSTEM_PROCESS_PATH);
        File dir = new File(processDir);
        if (!dir.exists()) return result;
        File[] files = dir.listFiles((d, name) -> name.endsWith(PROCESS_EXT));
        if (files == null) return result;
        for (File f : files) {
            try {
                String content = FileUtil.read(Constants.SYSTEM_PROCESS_PATH + f.getName());
                if (content == null) continue;
                Map<String, Object> data = JsonUtil.parseToMap(content);
                Object pidObj = data.get("PID");
                if (pidObj instanceof Number) {
                    result.put(((Number) pidObj).intValue(), f.getName());
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
     * 对文件名进行安全转义：只保留字母数字 . _ -
     */
    private static String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 从 VFS 文件路径推断文件名（不含路径）。
     */
    public static String extractFileName(String path) {
        return getFileName(path);
    }

    /**
     * 从文件中提取元数据内容。
     * 文件格式：
     * #<META>
     * {JSON 元数据}
     * <META>#
     * {正文内容}
     */
    public static String extractMetaContent(String fileContent) {
        if (fileContent == null || fileContent.isEmpty()) return null;
        String trimmed = fileContent.trim();
        if (!trimmed.startsWith(com.follarce.Constants.META_START)) return null;

        int start = trimmed.indexOf('\n', com.follarce.Constants.META_START.length());
        int end = trimmed.indexOf(com.follarce.Constants.META_END);
        if (start < 0 || end < 0 || end <= start) return null;

        return trimmed.substring(start, end).trim();
    }

    /**
     * 从文件中提取正文内容（去除元数据头）。
     */
    public static String extractBodyContent(String fileContent) {
        if (fileContent == null || fileContent.isEmpty()) return "";
        String trimmed = fileContent.trim();
        if (!trimmed.startsWith(com.follarce.Constants.META_START)) {
            return trimmed;
        }

        int end = trimmed.indexOf(com.follarce.Constants.META_END);
        if (end < 0) return trimmed;

        int bodyStart = end + com.follarce.Constants.META_END.length();
        if (bodyStart >= trimmed.length()) return "";
        return trimmed.substring(bodyStart).trim();
    }

    /**
     * 构建带元数据的文件内容。
     */
    public static String buildMetaFile(String metaJson, String body) {
        return com.follarce.Constants.META_START + "\n"
                + metaJson + "\n"
                + com.follarce.Constants.META_END + "\n"
                + (body != null ? body : "");
    }
}
