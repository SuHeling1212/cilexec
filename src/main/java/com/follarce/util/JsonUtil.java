package com.follarce.util;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.*;

/**
 * JSON 工具类 —— 封装 Gson 操作。
 * 返回类型约定：成功返回 Map/List/String/Number/Boolean，失败返回 String[]。
 */
public final class JsonUtil {

    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private static final Gson gsonCompact = new GsonBuilder()
            .serializeNulls()
            .create();

    private JsonUtil() {}

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
            return new String[]{ com.follarce.Constants.ERROR_MARKER, "Invalid JSON: " + e.getMessage() };
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
                double d = prim.getAsDouble();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    long l = (long) d;
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                        return (int) l;
                    }
                    return l;
                }
                return d;
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
    private static final String ERROR_MARKER = com.follarce.Constants.ERROR_MARKER;
}
