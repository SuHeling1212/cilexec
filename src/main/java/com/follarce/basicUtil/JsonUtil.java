package com.follarce.basicUtil;

import com.follarce.basicUtil.*;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public class JsonUtil {

    private static final Gson gson = new Gson();
 
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
                return gson.fromJson(content, type);
            } else if (trimmed.startsWith("[")) {
                Type type = new TypeToken<List<Object>>() {
                }.getType();
                return gson.fromJson(content, type);
            } else if (trimmed.startsWith("\"")) {
                return gson.fromJson(content, String.class);
            } else if (trimmed.equals("true") || trimmed.equals("false")) {
                return gson.fromJson(content, Boolean.class);
            } else if (trimmed.equals("null")) {
                return null;
            } else {
                // Number type
                try {
                    return gson.fromJson(content, Integer.class);
                } catch (Exception e) {
                    return gson.fromJson(content, Double.class);
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
}