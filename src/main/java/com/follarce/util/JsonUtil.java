package com.follarce.util;

import com.follarce.util.*;
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
     * 判断字符串是否为合法的JSON
     * 
     * @param content 要检查的字符串
     * @return true=合法JSON，false=非法JSON
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
     * 解析JSON内容
     * 
     * @param content JSON字符串内容
     * @return 成功返回解析后的对象（Map/List/String/Number/Boolean），失败返回String[]错误信息数组
     */
    public static Object readJson(String content) {
        List<String> errors = new ArrayList<>();

        // 先调用isValidJson判断是否合法
        if (!isValidJson(content)) {
            errors.add("ERROR");
            errors.add("INCORRECT_FORMAT");
            return errors.toArray(new String[0]);
        }

        try {
            String trimmed = content.trim();

            // 判断JSON类型并解析
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
                // 数字类型
                try {
                    return gson.fromJson(content, Integer.class);
                } catch (Exception e) {
                    return gson.fromJson(content, Double.class);
                }
            }
        } catch (JsonSyntaxException e) {
            // 虽然isValidJson通过了，但解析还是失败了（理论上不会发生）
            errors.add("ERROR");
            errors.add("INCORRECT_FORMAT");
            return errors.toArray(new String[0]);
        }
    }

    /**
     * 将对象转换为JSON字符串
     * 
     * @param obj 要转换的对象
     * @return JSON字符串
     */
    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }
}