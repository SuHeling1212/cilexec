package com.follarce.process;

import com.follarce.basicUtil.*;
import com.follarce.process.ProcessFunc;
import java.util.*;

public class SwapUtil {

    /**
     * Create a swap pool
     * 
     * @param name pool name
     * @return ["SUCCESS", null] on success, ["ERROR", code] on failure
     */
    public static String[] createSwapPool(String name) {
        if (name == null || name.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        String path = "/system/swap/" + name + ".json";
        String[] readResult = FileUtil.read(path);
        if (readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SWAP_POOL_EXIST" };
        }

        Map<String, Object> pool = new HashMap<>();
        pool.put("name", name);

        int[] now = TimeUtil.getTime();
        Map<String, Object> time = new HashMap<>();
        time.put("createTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
        time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
        time.put("lastOpenTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
        pool.put("time", time);

        pool.put("OwnerPID", ProcessFunc.getPID());
        pool.put("content", new HashMap<>());

        String json = JsonUtil.toJson(pool);
        String dirPath = "/system/swap/";
        FileUtil.createDirectory(dirPath, "");
        FileUtil.createFile(dirPath, name + ".json");
        FileUtil.write(path, json);

        return new String[] { "SUCCESS", null };
    }

    /**
     * Remove a swap pool
     */
    public static String[] removeSwapPool(String name) {
        if (name == null || name.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        String path = "/system/swap/" + name + ".json";
        String[] readResult = FileUtil.read(path);
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SWAP_POOL_DOES_NOT_EXIST" };
        }

        Map<String, Object> pool = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Object ownerPidObj = pool.get("OwnerPID");
        if (ownerPidObj == null) {
            return new String[] { "ERROR", "INVALID_SWAP_POOL" };
        }
        int ownerPid = ((Number) ownerPidObj).intValue();
        int currentPid = ProcessFunc.getPID();

        if (currentPid != ownerPid && !UserUtil.isLocal()) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        Map<String, Object> content = (Map<String, Object>) pool.get("content");
        if (content != null) {
            for (Object pidVarsObj : content.values()) {
                Map<String, Object> pidVars = (Map<String, Object>) pidVarsObj;
                for (Object varObj : pidVars.values()) {
                    Map<String, Object> var = (Map<String, Object>) varObj;
                    Boolean isLocked = (Boolean) var.get("locked");
                    if (isLocked != null && isLocked) {
                        return new String[] { "ERROR", "SOME_VAR_IS_LOCKED" };
                    }
                }
            }
        }

        String[] deleteResult = FileUtil.removeFile(path);
        if (!deleteResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "DELETE_FAILED" };
        }

        return new String[] { "SUCCESS", null };
    }

    /**
     * Add variable to swap pool
     */
    public static String[] swapPoolAdd(String varSpec, String poolName, String[] params) {
        int colonIndex = varSpec.indexOf(':');
        if (colonIndex == -1) {
            return new String[] { "ERROR", "INVALID_PARAMETER" };
        }
        String varName = varSpec.substring(0, colonIndex);
        String varValue = varSpec.substring(colonIndex + 1);

        if (varName == null || varName.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PARAMETER" };
        }

        String poolPath = "/system/swap/" + poolName + ".json";
        String[] readResult = FileUtil.read(poolPath);
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SWAP_POOL_IS_NOT_EXIST" };
        }

        Map<String, Object> pool = (Map<String, Object>) JsonUtil.readJson(readResult[1]);

        int ownerPid = ((Number) pool.get("OwnerPID")).intValue();
        int currentPid = ProcessFunc.getPID();
        if (currentPid != ownerPid && !UserUtil.isLocal()) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        Map<String, Object> content = (Map<String, Object>) pool.get("content");
        if (content == null) {
            content = new HashMap<>();
            pool.put("content", content);
        }

        Map<String, Object> pidVars = (Map<String, Object>) content.get(String.valueOf(currentPid));
        if (pidVars == null) {
            pidVars = new HashMap<>();
            content.put(String.valueOf(currentPid), pidVars);
        }

        if (pidVars.containsKey(varName)) {
            return new String[] { "ERROR", "VARIABLE_EXIST" };
        }

        String type = "always";
        List<Integer> whitelist = new ArrayList<>();
        List<Integer> blacklist = new ArrayList<>();

        if (params != null && params.length > 0) {
            for (String param : params) {
                param = param.trim();
                if (param.startsWith("times(") && param.endsWith(")")) {
                    type = param;
                } else if (param.startsWith("always")) {
                    type = "always";
                } else if (param.startsWith("sync")) {
                    type = "sync";
                } else if (param.startsWith("whitelist{") && param.endsWith("}")) {
                    String listStr = param.substring(10, param.length() - 1);
                    if (!listStr.isEmpty()) {
                        for (String pid : listStr.split(",")) {
                            try {
                                whitelist.add(Integer.parseInt(pid.trim()));
                            } catch (NumberFormatException e) {
                            }
                        }
                    }
                } else if (param.startsWith("blacklist{") && param.endsWith("}")) {
                    String listStr = param.substring(10, param.length() - 1);
                    if (!listStr.isEmpty()) {
                        for (String pid : listStr.split(",")) {
                            try {
                                blacklist.add(Integer.parseInt(pid.trim()));
                            } catch (NumberFormatException e) {
                            }
                        }
                    }
                } else {
                    return new String[] { "ERROR", "INVALID_PARAMETER" };
                }
            }
        }

        Object value = parseValue(varValue);

        Map<String, Object> varEntry = new HashMap<>();
        int[] now = TimeUtil.getTime();
        varEntry.put("addTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
        varEntry.put("editTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
        varEntry.put("type", type);
        if (type.startsWith("times(")) {
            String timesStr = type.substring(6, type.length() - 1);
            try {
                varEntry.put("remaining", Integer.parseInt(timesStr));
            } catch (NumberFormatException e) {
                varEntry.put("remaining", 1);
            }
        }
        varEntry.put("whitelist", whitelist);
        varEntry.put("blacklist", blacklist);
        varEntry.put("value", value);
        if ("sync".equals(type)) {
            varEntry.put("changed", false);
            varEntry.put("readers", new ArrayList<Integer>());
        }

        pidVars.put(varName, varEntry);

        Map<String, Object> time = (Map<String, Object>) pool.get("time");
        if (time == null) {
            time = new HashMap<>();
            pool.put("time", time);
        }
        time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

        FileUtil.write(poolPath, JsonUtil.toJson(pool));

        return new String[] { "SUCCESS", null };
    }

    /**
     * Get a single variable from swap pool
     */
    public static Object swapPoolGet(String varName, String poolName) {
        if (varName == null || varName.trim().isEmpty() || poolName == null || poolName.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PARAMETER" };
        }

        String poolPath = "/system/swap/" + poolName + ".json";
        String[] readResult = FileUtil.read(poolPath);
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SWAP_POOL_DOES_NOT_EXIST" };
        }

        Map<String, Object> pool = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> content = (Map<String, Object>) pool.get("content");

        if (content == null) {
            return new String[] { "ERROR", "VARIABLE_DOES_NOT_EXIST" };
        }

        int currentPid = ProcessFunc.getPID();

        // Search for variable across all PIDs
        for (Map.Entry<String, Object> entry : content.entrySet()) {
            Map<String, Object> pidVars = (Map<String, Object>) entry.getValue();
            if (pidVars.containsKey(varName)) {
                Map<String, Object> varEntry = (Map<String, Object>) pidVars.get(varName);

                // Check access control
                List<Integer> whitelist = (List<Integer>) varEntry.get("whitelist");
                List<Integer> blacklist = (List<Integer>) varEntry.get("blacklist");

                if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(currentPid)) {
                    return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
                }
                if (blacklist != null && blacklist.contains(currentPid)) {
                    return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
                }

                String type = (String) varEntry.get("type");
                Object value = varEntry.get("value");

                // Handle times(n) type
                if (type.startsWith("times(")) {
                    Integer remaining = (Integer) varEntry.get("remaining");
                    if (remaining == null || remaining <= 0) {
                        // Variable expired, remove it
                        pidVars.remove(varName);
                        if (pidVars.isEmpty()) {
                            content.remove(entry.getKey());
                        }
                        FileUtil.write(poolPath, JsonUtil.toJson(pool));
                        return new String[] { "ERROR", "VARIABLE_EXPIRED" };
                    }
                    // Decrement remaining count
                    varEntry.put("remaining", remaining - 1);
                    if (remaining - 1 == 0) {
                        // Remove after last read
                        pidVars.remove(varName);
                        if (pidVars.isEmpty()) {
                            content.remove(entry.getKey());
                        }
                    }
                    FileUtil.write(poolPath, JsonUtil.toJson(pool));
                    return value;
                }

                // Handle sync type
                if ("sync".equals(type)) {
                    Boolean changed = (Boolean) varEntry.get("changed");
                    List<Integer> readers = (List<Integer>) varEntry.get("readers");

                    if (changed != null && changed) {
                        // Value changed, return new value
                        if (readers == null) {
                            readers = new ArrayList<>();
                            varEntry.put("readers", readers);
                        }
                        if (!readers.contains(currentPid)) {
                            readers.add(currentPid);
                        }

                        // Check if all readers have read
                        // For simplicity, we don't track total readers count
                        // We'll clear changed flag after a timeout or next update

                        FileUtil.write(poolPath, JsonUtil.toJson(pool));
                        return value;
                    }
                }

                // Update last open time
                int[] now = TimeUtil.getTime();
                Map<String, Object> time = (Map<String, Object>) pool.get("time");
                if (time == null) {
                    time = new HashMap<>();
                    pool.put("time", time);
                }
                time.put("lastOpenTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
                FileUtil.write(poolPath, JsonUtil.toJson(pool));

                return value;
            }
        }

        return new String[] { "ERROR", "VARIABLE_DOES_NOT_EXIST" };
    }

    /**
     * Remove variable from swap pool
     */
    public static String[] swapPoolRemove(String varName, String poolName) {
        if (varName == null || varName.trim().isEmpty() || poolName == null || poolName.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PARAMETER" };
        }

        String poolPath = "/system/swap/" + poolName + ".json";
        String[] readResult = FileUtil.read(poolPath);
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SWAP_POOL_DOES_NOT_EXIST" };
        }

        Map<String, Object> pool = (Map<String, Object>) JsonUtil.readJson(readResult[1]);

        int ownerPid = ((Number) pool.get("OwnerPID")).intValue();
        int currentPid = ProcessFunc.getPID();
        if (currentPid != ownerPid && !UserUtil.isLocal()) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        Map<String, Object> content = (Map<String, Object>) pool.get("content");
        if (content == null) {
            return new String[] { "ERROR", "VARIABLE_DOES_NOT_EXIST" };
        }

        Map<String, Object> pidVars = (Map<String, Object>) content.get(String.valueOf(ownerPid));
        if (pidVars == null || !pidVars.containsKey(varName)) {
            return new String[] { "ERROR", "VARIABLE_DOES_NOT_EXIST" };
        }

        Map<String, Object> varEntry = (Map<String, Object>) pidVars.get(varName);
        Boolean isLocked = (Boolean) varEntry.get("locked");
        if (isLocked != null && isLocked) {
            return new String[] { "ERROR", "VAR_IS_LOCKED" };
        }

        pidVars.remove(varName);
        if (pidVars.isEmpty()) {
            content.remove(String.valueOf(ownerPid));
        }

        int[] now = TimeUtil.getTime();
        Map<String, Object> time = (Map<String, Object>) pool.get("time");
        if (time == null) {
            time = new HashMap<>();
            pool.put("time", time);
        }
        time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

        FileUtil.write(poolPath, JsonUtil.toJson(pool));

        return new String[] { "SUCCESS", null };
    }

    /**
     * Lock variable for modification
     */
    public static String[] swapPoolLock(String varName, String poolName) {
        if (varName == null || varName.trim().isEmpty() || poolName == null || poolName.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PARAMETER" };
        }

        String poolPath = "/system/swap/" + poolName + ".json";
        String[] readResult = FileUtil.read(poolPath);
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SWAP_POOL_DOES_NOT_EXIST" };
        }

        Map<String, Object> pool = (Map<String, Object>) JsonUtil.readJson(readResult[1]);

        int ownerPid = ((Number) pool.get("OwnerPID")).intValue();
        int currentPid = ProcessFunc.getPID();
        if (currentPid != ownerPid && !UserUtil.isLocal()) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        Map<String, Object> content = (Map<String, Object>) pool.get("content");
        if (content == null) {
            return new String[] { "ERROR", "VARIABLE_DOES_NOT_EXIST" };
        }

        Map<String, Object> pidVars = (Map<String, Object>) content.get(String.valueOf(ownerPid));
        if (pidVars == null || !pidVars.containsKey(varName)) {
            return new String[] { "ERROR", "VARIABLE_DOES_NOT_EXIST" };
        }

        Map<String, Object> varEntry = (Map<String, Object>) pidVars.get(varName);
        Boolean isLocked = (Boolean) varEntry.get("locked");
        if (isLocked != null && isLocked) {
            return new String[] { "ERROR", "VAR_IS_LOCKED" };
        }

        varEntry.put("locked", true);
        varEntry.put("lockedBy", currentPid);
        varEntry.put("lockedAt", TimeUtil.getTime());

        int[] now = TimeUtil.getTime();
        Map<String, Object> time = (Map<String, Object>) pool.get("time");
        if (time == null) {
            time = new HashMap<>();
            pool.put("time", time);
        }
        time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

        FileUtil.write(poolPath, JsonUtil.toJson(pool));

        return new String[] { "SUCCESS", null };
    }

    /**
     * Unlock variable
     */
    public static String[] swapPoolUnlock(String varName, String poolName) {
        if (varName == null || varName.trim().isEmpty() || poolName == null || poolName.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PARAMETER" };
        }

        String poolPath = "/system/swap/" + poolName + ".json";
        String[] readResult = FileUtil.read(poolPath);
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SWAP_POOL_DOES_NOT_EXIST" };
        }

        Map<String, Object> pool = (Map<String, Object>) JsonUtil.readJson(readResult[1]);

        int ownerPid = ((Number) pool.get("OwnerPID")).intValue();
        int currentPid = ProcessFunc.getPID();
        if (currentPid != ownerPid && !UserUtil.isLocal()) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        Map<String, Object> content = (Map<String, Object>) pool.get("content");
        if (content == null) {
            return new String[] { "ERROR", "VARIABLE_DOES_NOT_EXIST" };
        }

        Map<String, Object> pidVars = (Map<String, Object>) content.get(String.valueOf(ownerPid));
        if (pidVars == null || !pidVars.containsKey(varName)) {
            return new String[] { "ERROR", "VARIABLE_DOES_NOT_EXIST" };
        }

        Map<String, Object> varEntry = (Map<String, Object>) pidVars.get(varName);
        Boolean isLocked = (Boolean) varEntry.get("locked");
        if (isLocked == null || !isLocked) {
            return new String[] { "ERROR", "VAR_IS_NOT_LOCKED" };
        }

        varEntry.remove("locked");
        varEntry.remove("lockedBy");
        varEntry.remove("lockedAt");

        int[] now = TimeUtil.getTime();
        Map<String, Object> time = (Map<String, Object>) pool.get("time");
        if (time == null) {
            time = new HashMap<>();
            pool.put("time", time);
        }
        time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

        FileUtil.write(poolPath, JsonUtil.toJson(pool));

        return new String[] { "SUCCESS", null };
    }

    /**
     * Update variable value in swap pool (must be owner and locked)
     */
    public static String[] swapPoolUpdate(String varName, String poolName, String newValue) {
        if (varName == null || varName.trim().isEmpty() || poolName == null || poolName.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PARAMETER" };
        }
        if (newValue == null) {
            newValue = "";
        }

        String poolPath = "/system/swap/" + poolName + ".json";
        String[] readResult = FileUtil.read(poolPath);
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SWAP_POOL_DOES_NOT_EXIST" };
        }

        Map<String, Object> pool = (Map<String, Object>) JsonUtil.readJson(readResult[1]);

        int ownerPid = ((Number) pool.get("OwnerPID")).intValue();
        int currentPid = ProcessFunc.getPID();
        if (currentPid != ownerPid && !UserUtil.isLocal()) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        Map<String, Object> content = (Map<String, Object>) pool.get("content");
        if (content == null) {
            return new String[] { "ERROR", "VARIABLE_DOES_NOT_EXIST" };
        }

        Map<String, Object> pidVars = (Map<String, Object>) content.get(String.valueOf(ownerPid));
        if (pidVars == null || !pidVars.containsKey(varName)) {
            return new String[] { "ERROR", "VARIABLE_DOES_NOT_EXIST" };
        }

        Map<String, Object> varEntry = (Map<String, Object>) pidVars.get(varName);

        Boolean isLocked = (Boolean) varEntry.get("locked");
        if (isLocked == null || !isLocked) {
            return new String[] { "ERROR", "VAR_IS_NOT_LOCKED" };
        }

        Object parsedValue = parseValue(newValue);
        varEntry.put("value", parsedValue);

        int[] now = TimeUtil.getTime();
        varEntry.put("editTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

        String type = (String) varEntry.get("type");
        if ("sync".equals(type)) {
            varEntry.put("changed", true);
            varEntry.put("changedAt", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });
            varEntry.put("readers", new ArrayList<Integer>());
        }

        Map<String, Object> time = (Map<String, Object>) pool.get("time");
        if (time == null) {
            time = new HashMap<>();
            pool.put("time", time);
        }
        time.put("lastEditTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

        FileUtil.write(poolPath, JsonUtil.toJson(pool));

        return new String[] { "SUCCESS", null };
    }

    /**
     * Get all variables from swap pool (owner only)
     */
    public static Object swapPoolGetAll(String poolName) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_PARAMETER" };
        }

        String poolPath = "/system/swap/" + poolName + ".json";
        String[] readResult = FileUtil.read(poolPath);
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "SWAP_POOL_DOES_NOT_EXIST" };
        }

        Map<String, Object> pool = (Map<String, Object>) JsonUtil.readJson(readResult[1]);

        int ownerPid = ((Number) pool.get("OwnerPID")).intValue();
        int currentPid = ProcessFunc.getPID();
        if (currentPid != ownerPid && !UserUtil.isLocal()) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        Map<String, Object> content = (Map<String, Object>) pool.get("content");
        Map<String, Object> result = new HashMap<>();

        if (content != null) {
            for (Object pidVarsObj : content.values()) {
                Map<String, Object> pidVars = (Map<String, Object>) pidVarsObj;
                for (Map.Entry<String, Object> entry : pidVars.entrySet()) {
                    String varName = entry.getKey();
                    Map<String, Object> varEntry = (Map<String, Object>) entry.getValue();
                    Object value = varEntry.get("value");
                    result.put(varName, valueToString(value));
                }
            }
        }

        int[] now = TimeUtil.getTime();
        Map<String, Object> time = (Map<String, Object>) pool.get("time");
        if (time == null) {
            time = new HashMap<>();
            pool.put("time", time);
        }
        time.put("lastOpenTime", new int[] { now[0], now[1], now[2], now[3], now[4], now[5], now[6] });

        FileUtil.write(poolPath, JsonUtil.toJson(pool));

        return result;
    }

    /**
     * Parse value from string
     */
    private static Object parseValue(String valueStr) {
        valueStr = valueStr.trim();

        if (valueStr.startsWith("\"") && valueStr.endsWith("\"")) {
            return valueStr.substring(1, valueStr.length() - 1);
        }

        if (valueStr.matches("-?\\d+")) {
            return Integer.parseInt(valueStr);
        }
        if (valueStr.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(valueStr);
        }

        if (valueStr.equals("true"))
            return true;
        if (valueStr.equals("false"))
            return false;

        if (valueStr.startsWith("[") && valueStr.endsWith("]")) {
            return parseArray(valueStr);
        }

        if (valueStr.startsWith("{") && valueStr.endsWith("}")) {
            return parseMap(valueStr);
        }

        return valueStr;
    }

    private static List<Object> parseArray(String arrStr) {
        String content = arrStr.substring(1, arrStr.length() - 1).trim();
        List<Object> result = new ArrayList<>();

        if (!content.isEmpty()) {
            int depth = 0;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '[' || c == '{')
                    depth++;
                if (c == ']' || c == '}')
                    depth--;
                if (c == ',' && depth == 0) {
                    result.add(parseValue(current.toString().trim()));
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                result.add(parseValue(current.toString().trim()));
            }
        }

        return result;
    }

    private static Map<Object, Object> parseMap(String mapStr) {
        String content = mapStr.substring(1, mapStr.length() - 1).trim();
        Map<Object, Object> result = new HashMap<>();

        if (!content.isEmpty()) {
            int depth = 0;
            boolean inKey = true;
            StringBuilder currentKey = new StringBuilder();
            StringBuilder currentValue = new StringBuilder();

            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '[' || c == '{')
                    depth++;
                if (c == ']' || c == '}')
                    depth--;

                if (c == ':' && depth == 0 && inKey) {
                    inKey = false;
                    continue;
                }

                if (c == ',' && depth == 0) {
                    if (!inKey) {
                        Object key = parseValue(currentKey.toString().trim());
                        Object value = parseValue(currentValue.toString().trim());
                        result.put(key, value);
                    }
                    currentKey = new StringBuilder();
                    currentValue = new StringBuilder();
                    inKey = true;
                } else if (inKey) {
                    currentKey.append(c);
                } else {
                    currentValue.append(c);
                }
            }

            if (!inKey && currentValue.length() > 0) {
                Object key = parseValue(currentKey.toString().trim());
                Object value = parseValue(currentValue.toString().trim());
                result.put(key, value);
            }
        }

        return result;
    }

    private static String valueToString(Object value) {
        if (value == null)
            return "null";
        if (value instanceof String)
            return (String) value;
        if (value instanceof Number)
            return value.toString();
        if (value instanceof Boolean)
            return value.toString();
        if (value instanceof List)
            return JsonUtil.toJson(value);
        if (value instanceof Map)
            return JsonUtil.toJson(value);
        if (value instanceof Object[])
            return JsonUtil.toJson(value);
        return value.toString();
    }

    /**
     * Clean up sync variable readers when process exits
     */
    public static void onProcessExit(int pid) {
        String[] listResult = FileUtil.getListOfFileAndDirectory("/system/swap/");
        if (!listResult[0].equals("SUCCESS")) {
            return;
        }

        for (int i = 1; i < listResult.length; i++) {
            String poolName = listResult[i].replace(".json", "");
            String poolPath = "/system/swap/" + poolName + ".json";
            String[] readResult = FileUtil.read(poolPath);
            if (!readResult[0].equals("SUCCESS")) {
                continue;
            }

            Map<String, Object> pool = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
            Map<String, Object> content = (Map<String, Object>) pool.get("content");
            boolean modified = false;

            if (content != null) {
                for (Object pidVarsObj : content.values()) {
                    Map<String, Object> pidVars = (Map<String, Object>) pidVarsObj;
                    for (Object varObj : pidVars.values()) {
                        Map<String, Object> var = (Map<String, Object>) varObj;
                        if ("sync".equals(var.get("type"))) {
                            List<Integer> readers = (List<Integer>) var.get("readers");
                            if (readers != null && readers.contains(pid)) {
                                readers.remove(Integer.valueOf(pid));
                                modified = true;
                            }
                        }
                    }
                }
            }

            if (modified) {
                FileUtil.write(poolPath, JsonUtil.toJson(pool));
            }
        }
    }
}