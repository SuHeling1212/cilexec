package com.follarce.kernel.util;

import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.io.File;

/**
 * JSON 工具类 —— 封装 Gson 操作。
 * 返回类型约定：成功返回 Map/List/String/Number/Boolean，失败返回 String[]。
 * <p>
 * <strong>原子字段操作</strong>：{@link #getField}, {@link #setField}, {@link #removeField}
 * 将"锁 → 读 → 改单个字段 → 写"合为一步，消除 TOCTOU 窗口。
 * 按文件路径锁定，确保多线程写入安全。
 */
public final class JsonUtil {

    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private static final Gson gsonCompact = new GsonBuilder()
            .serializeNulls()
            .create();

    // ── 文件级锁表（路径 → ReentrantLock） ──
    private static final ConcurrentHashMap<String, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

    private JsonUtil() {}

    // ════════════════════════════════════════════
    // 原子字段操作（按文件路径锁定）
    // ════════════════════════════════════════════

    /**
     * 获取或创建指定真实路径的文件锁。
     */
    private static ReentrantLock getFileLock(String realPath) {
        return FILE_LOCKS.computeIfAbsent(realPath, k -> new ReentrantLock());
    }

    /**
     * 对指定 VFS 路径加锁并返回锁对象，调用方负责在 finally 中 unlock()。
     * 读写均须持锁：写锁保证写入期间无读者看到半写状态，读锁保证读取期间文件不被替换。
     */
    public static ReentrantLock lockFile(String vfsPath) {
        String realPath = PathUtil.toRealPath(vfsPath);
        ReentrantLock lock = getFileLock(realPath);
        lock.lock();
        return lock;
    }

    /**
     * 原子读取 VFS 文件中 dotPath 指定字段的值。
     *
     * @param vfsPath VFS 路径（如 "/system/process/2.proc"）
     * @param dotPath 点号分隔的字段路径
     * @return 字段值，或 null
     */
    @SuppressWarnings("unchecked")
    public static Object getField(String vfsPath, String dotPath) {
        String realPath = PathUtil.toRealPath(vfsPath);
        ReentrantLock lock = getFileLock(realPath);
        lock.lock();
        try {
            File f = new File(realPath);
            if (!f.exists()) return null;

            String body = FileUtil.read(vfsPath);
            if (body == null || body.trim().isEmpty()) return null;

            Map<String, Object> data = parseToMap(body);

            String[] parts = dotPath.split("\\.");
            Object cur = data;
            for (String part : parts) {
                if (cur instanceof Map) {
                    cur = ((Map<String, Object>) cur).get(part);
                } else if (cur instanceof List && isInteger(part)) {
                    int idx = Integer.parseInt(part);
                    List<Object> list = (List<Object>) cur;
                    cur = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
                } else {
                    return null;
                }
                if (cur == null) return null;
            }
            return cur;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 原子设置 VFS 文件中 dotPath 指定字段的值。
     * 中间路径不存在时自动创建 LinkedHashMap。
     * value 为 null 时删除该字段。
     */
    @SuppressWarnings("unchecked")
    public static void setField(String vfsPath, String dotPath, Object value) {
        String realPath = PathUtil.toRealPath(vfsPath);
        ReentrantLock lock = getFileLock(realPath);
        lock.lock();
        try {
            String body = FileUtil.read(vfsPath);
            Map<String, Object> data = parseToMap(body);

            String[] parts = dotPath.split("\\.");
            Map<String, Object> cur = data;

            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                Object next = cur.get(part);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<>();
                    cur.put(part, next);
                }
                cur = (Map<String, Object>) next;
            }

            String leaf = parts[parts.length - 1];
            if (value == null) {
                cur.remove(leaf);
            } else {
                cur.put(leaf, value);
            }

            FileUtil.writeAtomic(vfsPath, toJson(data));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 原子删除 VFS 文件中 dotPath 指定的字段。
     */
    public static void removeField(String vfsPath, String dotPath) {
        setField(vfsPath, dotPath, null);
    }

    /**
     * Atomically read, mutate, and replace a JSON object stored in a VFS file.
     */
    public static void updateFile(String vfsPath, Consumer<Map<String, Object>> updater) {
        ReentrantLock lock = lockFile(vfsPath);
        try {
            Map<String, Object> data = parseToMapStrict(FileUtil.read(vfsPath));
            updater.accept(data);
            FileUtil.writeAtomic(vfsPath, toJson(data));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在 VFS 路径上写入完整文件内容。
     * 委托给 {@link FileUtil#writeAtomic}，锁由 writeAtomic 内部管理。
     */
    public static void writeFile(String vfsPath, String content) {
        FileUtil.writeAtomic(vfsPath, content);
    }

    /**
     * 判断字符串是否为整数。
     */
    private static boolean isInteger(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    // ── 序列化 ──

    /**
     * 将对象序列化为 JSON 字符串（格式化）。
     */
    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }

    /**
     * 将对象序列化为 JSON 字符串（紧凑）。
     */
    public static String toJsonCompact(Object obj) {
        return gsonCompact.toJson(obj);
    }

    // ── 反序列化 ──

    /**
     * 将 JSON 字符串解析为 Object。
     * 返回类型：Map, List, String, Number, Boolean, null。
     */
    public static Object parseJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            return parseElement(JsonParser.parseString(json.trim()));
        } catch (JsonParseException e) {
            return new String[]{ com.follarce.kernel.Constants.ERROR_MARKER, "Invalid JSON: " + e.getMessage() };
        }
    }

    /**
     * 将 JSON 字符串解析为 Map。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseToMap(String json) {
        Object result = parseJson(json);
        if (result instanceof Map) {
            return (Map<String, Object>) result;
        }
        return new LinkedHashMap<>();
    }

    /** Parse a JSON object without silently converting malformed input to an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseToMapStrict(String json) {
        Object result = parseJson(json);
        if (result instanceof Map) {
            return (Map<String, Object>) result;
        }
        throw new IllegalArgumentException("Expected a valid JSON object");
    }

    /**
     * 将 JSON 字符串解析为 List。
     */
    @SuppressWarnings("unchecked")
    public static List<Object> parseToList(String json) {
        Object result = parseJson(json);
        if (result instanceof List) {
            return (List<Object>) result;
        }
        return new ArrayList<>();
    }

    // ── 对象转 Map ──

    /**
     * 将 Java 对象转换为 Map（用于 JSON 输出）。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> objectToMap(Object obj) {
        if (obj == null) return new LinkedHashMap<>();
        String json = toJson(obj);
        Object parsed = parseJson(json);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        return new LinkedHashMap<>();
    }

    /**
     * 解析 JSON 字符串并转换为深度类型化的 Map。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromJson(String json) {
        return parseToMap(json);
    }

    // ── 内部辅助 ──

    @SuppressWarnings("unchecked")
    private static Object parseElement(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) {
            JsonPrimitive prim = element.getAsJsonPrimitive();
            if (prim.isNumber()) {
                String literal = prim.getAsString();
                if (!literal.contains(".") && !literal.contains("e") && !literal.contains("E")) {
                    long l;
                    try {
                        l = Long.parseLong(literal);
                    } catch (NumberFormatException e) {
                        return prim.getAsBigDecimal();
                    }
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                        return (int) l;
                    }
                    return l;
                }
                return prim.getAsBigDecimal();
            }
            if (prim.isBoolean()) return prim.getAsBoolean();
            return prim.getAsString();
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            List<Object> list = new ArrayList<>();
            for (JsonElement e : array) {
                list.add(parseElement(e));
            }
            return list;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                map.put(entry.getKey(), parseElement(entry.getValue()));
            }
            return map;
        }
        return null;
    }

    // ── 深拷贝 ──

    /**
     * 深拷贝一个对象（通过 JSON 序列化/反序列化）。
     */
    @SuppressWarnings("unchecked")
    public static <T> T deepCopy(T obj) {
        if (obj == null) return null;
        String json = toJson(obj);
        return (T) parseJson(json);
    }

    // ── 写入辅助 ──

    /**
     * 将对象转为格式化的 JSON 字符串，用于 VFS 文件写入。
     */
    public static String toMetaJson(Object obj) {
        return toJson(obj);
    }

    // 使用顶层 Constants 中的 ERROR_MARKER
    private static final String ERROR_MARKER = com.follarce.kernel.Constants.ERROR_MARKER;
}
