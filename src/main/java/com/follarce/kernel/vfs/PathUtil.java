package com.follarce.kernel.vfs;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

import com.follarce.kernel.Constants;
import com.follarce.kernel.util.JsonUtil;

/**
 * 路径工具类 —— 路径解析、标准化、别名替换。
 */
public final class PathUtil {

    private static final int MAX_EXPANSION_DEPTH = 32;

    private static File vfsRoot;
    private static volatile Map<String, String> envAliases = Collections.emptyMap();

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
        Map<String, String> copy = aliases == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(aliases);
        envAliases = Collections.unmodifiableMap(copy);
    }

    /**
     * 获取完整的环境变量别名映射。
     */
    public static Map<String, String> getEnvAliases() {
        File root = vfsRoot;
        if (root == null) return new LinkedHashMap<>(envAliases);
        java.nio.file.Path envPath = root.toPath()
                .resolve((Constants.SYSTEM_CONFIG_PATH + Constants.CONFIG_ENV_JSON).substring(1));
        if (!Files.isRegularFile(envPath)) return new LinkedHashMap<>(envAliases);
        try {
            String body = extractBodyContent(Files.readString(envPath));
            Map<String, Object> env = JsonUtil.parseToMapStrict(body);
            Object configuredAliases = env.get("aliases");
            if (!(configuredAliases instanceof Map<?, ?> aliasMap)) return new LinkedHashMap<>();
            Map<String, String> aliases = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : aliasMap.entrySet()) {
                if (entry.getKey() instanceof String name
                        && entry.getValue() instanceof String value) {
                    aliases.put(name, value);
                }
            }
            return aliases;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read path aliases from disk", e);
        }
    }

    /**
     * 替换路径中的别名和特殊符号。
     * ~, $HOME → /user/local
     * $SYSTEM → /system
     * 其他 $VAR → envAliases 中查找
     */
    public static String resolvePath(String path) {
        return resolvePath(path, Constants.DEFAULT_USER_LOCAL, Collections.emptyMap());
    }

    /**
     * 使用进程上下文解析路径。进程别名优先于全局别名。
     */
    public static String resolvePath(String path, String effectiveUser,
                                     Map<String, String> processAliases) {
        if (path == null || path.isEmpty()) return "/";

        String user = effectiveUser == null || effectiveUser.isEmpty()
                ? Constants.DEFAULT_USER_LOCAL
                : effectiveUser;
        String home = Constants.USER_HOME_PREFIX + user;
        char initialMarker = path.charAt(0);
        Map<String, String> globalAliases = initialMarker == '$' || initialMarker == '@'
                ? getEnvAliases() : Collections.emptyMap();
        Map<String, String> localAliases = processAliases == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(processAliases);
        Set<String> expandedAliases = new HashSet<>();
        String resolved = path;
        int expansionDepth = 0;

        while (!resolved.isEmpty()) {
            char marker = resolved.charAt(0);
            if (marker != '~' && marker != '$' && marker != '@') break;

            int tokenEnd = leadingTokenEnd(resolved);
            String token = resolved.substring(0, tokenEnd);
            String replacement;
            String cycleKey = null;

            if (marker == '~') {
                if (!token.equals("~")) {
                    throw new IllegalArgumentException("Unknown home token: " + token);
                }
                replacement = home;
            } else if (marker == '$') {
                String name = token.substring(1);
                if (name.equals("HOME")) {
                    replacement = home;
                } else if (name.equals("SYSTEM")) {
                    replacement = "/system";
                } else if (globalAliases.containsKey(name)) {
                    replacement = globalAliases.get(name);
                    cycleKey = "global:$" + name;
                } else {
                    throw new IllegalArgumentException("Unknown environment token: " + token);
                }
            } else {
                String name = token.substring(1);
                if (localAliases.containsKey(name)) {
                    replacement = localAliases.get(name);
                    cycleKey = "process:@" + name;
                } else if (globalAliases.containsKey(name)) {
                    replacement = globalAliases.get(name);
                    cycleKey = "global:@" + name;
                } else {
                    throw new IllegalArgumentException("Unknown path alias: " + token);
                }
            }

            if (replacement == null || replacement.isEmpty()) {
                throw new IllegalArgumentException("Path token has no value: " + token);
            }
            if (cycleKey != null && !expandedAliases.add(cycleKey)) {
                throw new IllegalArgumentException("Path alias cycle detected at " + token);
            }
            if (++expansionDepth > MAX_EXPANSION_DEPTH) {
                throw new IllegalArgumentException(
                        "Path expansion exceeds maximum depth of " + MAX_EXPANSION_DEPTH);
            }

            resolved = replacement + resolved.substring(tokenEnd);
        }

        return normalizePath(resolved);
    }

    private static int leadingTokenEnd(String path) {
        int slash = path.indexOf('/');
        int backslash = path.indexOf('\\');
        if (slash < 0) return backslash < 0 ? path.length() : backslash;
        if (backslash < 0) return slash;
        return Math.min(slash, backslash);
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
            if (part.startsWith(".") && !part.equals(com.follarce.kernel.Constants.META_DIR_FILE)) {
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
    // 进程文件命名（.proc）
    // ════════════════════════════════════════════

    private static final String PROCESS_EXT = ".proc";

    /**
     * 将进程名称安全地转为文件名（含 .proc 后缀）。
     */
    public static String getProcessFileName(String processName) {
        String safe = sanitizeFileName(processName);
        return safe + PROCESS_EXT;
    }

    /**
     * 获取进程文件的 VFS 路径（基于进程名称）。
     */
    public static String getProcessFilePath(String processName) {
        return Constants.SYSTEM_PROCESS_PATH + getProcessFileName(processName);
    }

    /**
     * 获取进程文件的 VFS 路径（基于 PID）。
     * PID 是唯一标识，避免同名进程文件覆盖（#1）。
     */
    public static String getProcessFilePath(int pid) {
        return Constants.SYSTEM_PROCESS_PATH + pid + PROCESS_EXT;
    }

    /**
     * 根据 PID 查找 .proc 文件名。
     * 文件名即为 {pid}.proc。
     */
    public static String findProcessFileNameByPid(int pid) {
        String fileName = pid + PROCESS_EXT;
        String processDir = toRealPath(Constants.SYSTEM_PROCESS_PATH);
        File file = new File(processDir, fileName);
        return file.exists() ? fileName : null;
    }

    /**
     * 根据 PID 获取 .proc 文件完整路径。
     */
    public static String findProcessFilePathByPid(int pid) {
        return getProcessFilePath(pid);
    }

    /**
     * 扫描进程目录，返回 PID → 文件名 的映射。
     * 文件名本身就是 {pid}.proc，从文件名解析 PID。
     */
    public static Map<Integer, String> scanProcessFileNames() {
        Map<Integer, String> result = new LinkedHashMap<>();
        String processDir = toRealPath(Constants.SYSTEM_PROCESS_PATH);
        File dir = new File(processDir);
        if (!dir.exists()) return result;
        File[] files = dir.listFiles((d, name) -> name.endsWith(PROCESS_EXT));
        if (files == null) return result;
        for (File f : files) {
            try {
                String name = f.getName();
                int extIdx = name.lastIndexOf(PROCESS_EXT);
                if (extIdx <= 0) continue;
                int pid = Integer.parseInt(name.substring(0, extIdx));
                result.put(pid, name);
            } catch (NumberFormatException ignored) {
                // 兼容旧版 name-based 文件格式
                try {
                    String content = FileUtil.read(Constants.SYSTEM_PROCESS_PATH + f.getName());
                    if (content == null) continue;
                    Map<String, Object> data = JsonUtil.parseToMap(content);
                    Object pidObj = data.get("PID");
                    if (pidObj instanceof Number) {
                        result.put(((Number) pidObj).intValue(), f.getName());
                    }
                } catch (Exception ignored2) {
                }
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
        if (!trimmed.startsWith(com.follarce.kernel.Constants.META_START)) return null;

        int start = trimmed.indexOf('\n', com.follarce.kernel.Constants.META_START.length());
        int end = trimmed.indexOf(com.follarce.kernel.Constants.META_END);
        if (start < 0 || end < 0 || end <= start) return null;

        return trimmed.substring(start, end).trim();
    }

    /**
     * 从文件中提取正文内容（去除元数据头）。
     */
    public static String extractBodyContent(String fileContent) {
        if (fileContent == null || fileContent.isEmpty()) return "";
        String trimmed = fileContent.trim();
        if (!trimmed.startsWith(com.follarce.kernel.Constants.META_START)) {
            return trimmed;
        }

        int end = trimmed.indexOf(com.follarce.kernel.Constants.META_END);
        if (end < 0) return trimmed;

        int bodyStart = end + com.follarce.kernel.Constants.META_END.length();
        if (bodyStart >= trimmed.length()) return "";
        return trimmed.substring(bodyStart).trim();
    }

    /**
     * 构建带元数据的文件内容。
     */
    public static String buildMetaFile(String metaJson, String body) {
        return com.follarce.kernel.Constants.META_START + "\n"
                + metaJson + "\n"
                + com.follarce.kernel.Constants.META_END + "\n"
                + (body != null ? body : "");
    }
}
