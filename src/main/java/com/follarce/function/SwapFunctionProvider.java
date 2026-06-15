package com.follarce.function;

import com.follarce.Constants;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;

import java.util.*;

/**
 * 交换池（Swap Pool）函数提供者。
 * 操作 /system/swap/ 目录下的 JSON 文件，用于进程间数据交换。
 * 命名空间: "swapPool"
 */
public class SwapFunctionProvider implements FunctionProvider {

    @Override
    public String getNamespace() {
        return "swapPool";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            switch (functionName) {
                case "create":
                    return createPool(getStringArg(args, 0));

                case "remove":
                    return removePool(getStringArg(args, 0));

                case "add":
                    return addVariable(getStringArg(args, 0), getStringArg(args, 1), tailArgs(args, 2));

                case "get":
                    return getVariable(getStringArg(args, 0), getStringArg(args, 1));

                case "removeVar":
                    return removeVariable(getStringArg(args, 0), getStringArg(args, 1));

                case "lock":
                    return lockVariable(getStringArg(args, 0), getStringArg(args, 1), intArg(args, 2));

                case "unlock":
                    return unlockVariable(getStringArg(args, 0), getStringArg(args, 1), intArg(args, 2));

                case "update":
                    return updateVariable(getStringArg(args, 0), getStringArg(args, 1), getStringArg(args, 2));

                case "getAll":
                    return getAllVariables(getStringArg(args, 0));

                default:
                    return null;
            }
        } catch (Exception e) {
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    // ── 私有辅助 ──

    private static String getPoolPath(String poolName) {
        return Constants.SYSTEM_SWAP_PATH + poolName + ".json";
    }

    private static Map<String, Object> readPool(String poolName) {
        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return new LinkedHashMap<>();
        }
        String content = FileUtil.read(path);
        if (content == null || content.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        Object parsed = JsonUtil.parseJson(content);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        return new LinkedHashMap<>();
    }

    private static void writePool(String poolName, Map<String, Object> data) {
        String json = JsonUtil.toMetaJson(data);
        FileUtil.write(getPoolPath(poolName), json);
    }

    // ── 交换池 CRUD ──

    private static Object createPool(String name) {
        if (name == null || name.trim().isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Pool name cannot be empty"};
        }
        FileUtil.createFile(Constants.SYSTEM_SWAP_PATH, name + ".json");
        writePool(name, new LinkedHashMap<>());
        return "Swap pool created: " + name;
    }

    private static Object removePool(String name) {
        if (name == null || name.trim().isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Pool name cannot be empty"};
        }
        String path = getPoolPath(name);
        if (!FileUtil.exists(path)) {
            return new String[]{Constants.ERROR_MARKER, "Swap pool not found: " + name};
        }
        FileUtil.removeFile(path);
        return "Swap pool removed: " + name;
    }

    private static Object addVariable(String data, String poolName, List<Object> extraParams) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Pool name cannot be empty"};
        }
        Map<String, Object> pool = readPool(poolName);

        // 解析 data: 格式 "varName:value"
        if (data != null && data.contains(":")) {
            int colon = data.indexOf(':');
            String varName = data.substring(0, colon);
            String varValue = data.substring(colon + 1);
            pool.put(varName, varValue);
        }

        // 额外参数也按 "varName:value" 格式处理
        if (extraParams != null) {
            for (Object param : extraParams) {
                if (param instanceof String) {
                    String str = (String) param;
                    if (str.contains(":")) {
                        int colon = str.indexOf(':');
                        String varName = str.substring(0, colon);
                        String varValue = str.substring(colon + 1);
                        pool.put(varName, varValue);
                    }
                }
            }
        }

        writePool(poolName, pool);
        return "Variables added to pool: " + poolName;
    }

    private static Object getVariable(String varName, String poolName) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Pool name cannot be empty"};
        }
        Map<String, Object> pool = readPool(poolName);
        Object value = pool.get(varName);
        if (value == null) {
            return new String[]{Constants.ERROR_MARKER, "Variable not found: " + varName};
        }
        return value;
    }

    private static Object removeVariable(String varName, String poolName) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Pool name cannot be empty"};
        }
        Map<String, Object> pool = readPool(poolName);
        if (pool.remove(varName) == null) {
            return new String[]{Constants.ERROR_MARKER, "Variable not found: " + varName};
        }
        writePool(poolName, pool);
        return "Variable removed: " + varName;
    }

    private static Object lockVariable(String varName, String poolName, int pid) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Pool name cannot be empty"};
        }
        Map<String, Object> pool = readPool(poolName);
        if (!pool.containsKey(varName)) {
            return new String[]{Constants.ERROR_MARKER, "Variable not found: " + varName};
        }
        // 将变量包装为带锁状态的对象
        Map<String, Object> lockedVar = new LinkedHashMap<>();
        lockedVar.put("value", pool.get(varName));
        lockedVar.put("isLocked", true);
        lockedVar.put("lockedBy", pid);
        pool.put(varName, lockedVar);
        writePool(poolName, pool);
        return "Variable locked: " + varName;
    }

    private static Object unlockVariable(String varName, String poolName, int pid) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Pool name cannot be empty"};
        }
        Map<String, Object> pool = readPool(poolName);
        if (!pool.containsKey(varName)) {
            return new String[]{Constants.ERROR_MARKER, "Variable not found: " + varName};
        }
        Object existing = pool.get(varName);
        if (existing instanceof Map) {
            Map<String, Object> varObj = (Map<String, Object>) existing;
            varObj.put("isLocked", false);
            varObj.put("lockedBy", null);
            pool.put(varName, varObj);
        }
        writePool(poolName, pool);
        return "Variable unlocked: " + varName;
    }

    private static Object updateVariable(String varName, String poolName, String newValue) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Pool name cannot be empty"};
        }
        Map<String, Object> pool = readPool(poolName);
        if (!pool.containsKey(varName)) {
            return new String[]{Constants.ERROR_MARKER, "Variable not found: " + varName};
        }
        pool.put(varName, newValue);
        writePool(poolName, pool);
        return "Variable updated: " + varName;
    }

    private static Object getAllVariables(String poolName) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Pool name cannot be empty"};
        }
        Map<String, Object> pool = readPool(poolName);
        if (pool.isEmpty()) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : pool.entrySet()) {
            sb.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
        }
        return sb.toString().trim();
    }

    // ── 参数提取 ──

    private static String getStringArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) {
            return null;
        }
        Object val = args.get(index);
        return val != null ? val.toString() : null;
    }

    private static int intArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) {
            return 0;
        }
        Object val = args.get(index);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return 0;
    }

    private static List<Object> tailArgs(List<Object> args, int fromIndex) {
        if (args == null || fromIndex >= args.size()) {
            return Collections.emptyList();
        }
        return args.subList(fromIndex, args.size());
    }
}
