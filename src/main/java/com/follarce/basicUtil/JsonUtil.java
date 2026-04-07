package com.follarce.basicUtil;

import com.follarce.basicUtil.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

public class JsonUtil {

    private static final Gson gson = new Gson();
    private static final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Convert Double values to Integer if they represent whole numbers
     * Recursively processes Maps and Lists
     */
    @SuppressWarnings("unchecked")
    private static Object convertNumbers(Object obj) {
        if (obj instanceof Double) {
            Double d = (Double) obj;
            if (d == d.intValue()) {
                return d.intValue();
            }
            return d;
        } else if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            Map<String, Object> newMap = new HashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                newMap.put(entry.getKey(), convertNumbers(entry.getValue()));
            }
            return newMap;
        } else if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            List<Object> newList = new ArrayList<>();
            for (Object item : list) {
                newList.add(convertNumbers(item));
            }
            return newList;
        }
        return obj;
    }
 
    /**
     * Check if string is valid JSON
     *
     * @param content String to check
     * @return true=valid JSON, false=invalid JSON
     */
    public static boolean isValidJson(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }

        try {
            gson.fromJson(content, Object.class);
            return true;
        } catch (JsonSyntaxException e) {
            return false;
        }
    }

    /**
     * Parse JSON content
     *
     * @param content JSON string content
     * @return Returns parsed object (Map/List/String/Number/Boolean) on success, returns String[] error info array on failure
     */
    public static Object readJson(String content) {
        List<String> errors = new ArrayList<>();

        // First call isValidJson to check if valid
        if (!isValidJson(content)) {
            errors.add("ERROR");
            errors.add("INCORRECT_FORMAT");
            return errors.toArray(new String[0]);
        }

        try {
            String trimmed = content.trim();

            // Determine JSON type and parse
            if (trimmed.startsWith("{")) {
                Type type = new TypeToken<Map<String, Object>>() {
                }.getType();
                Map<String, Object> map = gson.fromJson(content, type);
                if (map == null) {
                    map = new HashMap<>();
                }
                return convertNumbers(map);
            } else if (trimmed.startsWith("[")) {
                Type type = new TypeToken<List<Object>>() {
                }.getType();
                List<Object> list = gson.fromJson(content, type);
                if (list == null) {
                    list = new ArrayList<>();
                }
                return convertNumbers(list);
            } else if (trimmed.startsWith("\"")) {
                String str = gson.fromJson(content, String.class);
                return str != null ? str : "";
            } else if (trimmed.equals("true") || trimmed.equals("false")) {
                Boolean bool = gson.fromJson(content, Boolean.class);
                return bool != null ? bool : false;
            } else if (trimmed.equals("null")) {
                return null;
            } else {
                // Number type - parse as Number first, then determine if it's integer or double
                Number num = gson.fromJson(content, Number.class);
                if (num == null) {
                    return 0;
                }
                if (num.doubleValue() == num.intValue()) {
                    // It's an integer value
                    return num.intValue();
                } else {
                    // It's a decimal value
                    return num.doubleValue();
                }
            }
        } catch (JsonSyntaxException e) {
            // Although isValidJson passed, parsing still failed (theoretically shouldn't happen)
            errors.add("ERROR");
            errors.add("INCORRECT_FORMAT");
            return errors.toArray(new String[0]);
        }
    }

    /**
     * Convert object to JSON string
     *
     * @param obj Object to convert
     * @return JSON string
     */
    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }
    
    /**
     * Convert object to formatted JSON string with pretty printing
     *
     * @param obj Object to convert
     * @return Formatted JSON string with indentation
     */
    public static String toJsonPretty(Object obj) {
        return prettyGson.toJson(obj);
    }
}