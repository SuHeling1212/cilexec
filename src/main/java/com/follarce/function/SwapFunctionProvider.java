package com.follarce.function;

import com.follarce.Constants;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 交换池（Swap Pool）函数提供者 —— 完整实现。
 * <p>
 * 每个交换池是一个 JSON 文件，存放在 /system/swap/{name}.json。
 * 变量可附带类型元数据，支持 always / sync / times(N) 三种类型。
 * <p>
 * 文件格式：
 * <pre>
 * {
 *   "name": "pool_name",
 *   "OwnerPID": 1,
 *   "time": { "createTime": [...], "lastEditTime": [...] },
 *   "content": {
 *     "varName": {
 *       "value": ...,
 *       "type": "always",        // always | sync | times(N)
 *       "addTime": [...],
 *       "editTime": [...],
 *       "changed": false,         // sync 用：标记是否有未读变更
 *       "locked": false,
 *       "lockedBy": null,
 *       "whitelist": [],          // 允许读取的 PID 列表（空=不限）
 *       "blacklist": [],          // 禁止读取的 PID 列表
 *       "readCount": 0            // times(N) 用：剩余可读次数
 *     }
 *   }
 * }
 * </pre>
 * <p>
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
                    return createPool(getStringArg(args, 0), context.getPid());

                case "remove":
                    return removePool(getStringArg(args, 0));

                case "add":
                    return addVariable(getStringArg(args, 0), getStringArg(args, 1),
                            tailArgs(args, 2), context.getPid());

                case "get":
                    return getVariable(getStringArg(args, 0), getStringArg(args, 1), context.getPid());

                case "removeVar":
                    return removeVariable(getStringArg(args, 0), getStringArg(args, 1));

                case "update":
                    return updateVariable(getStringArg(args, 0), getStringArg(args, 1),
                            getStringArg(args, 2), context.getPid());

                case "lock":
                    return lockVariable(getStringArg(args, 0), getStringArg(args, 1), context.getPid());

                case "unlock":
                    return unlockVariable(getStringArg(args, 0), getStringArg(args, 1), context.getPid());

                case "ls":
                    return listVariables(getStringArg(args, 0));

                case "clear":
                    return clearPool(getStringArg(args, 0));

                case "exists":
                    return poolExists(getStringArg(args, 0));

                case "waitFor":
                    return waitForVariable(getStringArg(args, 0), getStringArg(args, 1), context.getPid());

                case "signal":
                    return signalVariable(getStringArg(args, 0), getStringArg(args, 1), context.getPid());

                case "list":
                    return listAllPools();

                default:
                    return null;
            }
        } catch (Exception e) {
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    // ════════════════════════════════════════════
    // 内部数据结构辅助
    // ════════════════════════════════════════════

    private static String getPoolPath(String poolName) {
        return Constants.SYSTEM_SWAP_PATH + poolName + ".json";
    }

    /**
     * 读取整个交换池文件为 Map。
     * 返回的结构：{ name, OwnerPID, time, content: { varName -> varMeta } }
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readPool(String poolName) {
        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return null;
        }
        String content = FileUtil.read(path);
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        Object parsed = JsonUtil.parseJson(content);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        return null;
    }

    private static void writePool(String poolName, Map<String, Object> data) {
        String json = JsonUtil.toMetaJson(data);
        FileUtil.write(getPoolPath(poolName), json);
    }

    /**
     * 获取 content 子 Map（content.varName -> varMeta）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getContent(Map<String, Object> pool) {
        if (pool == null) return null;
        Object raw = pool.get("content");
        if (raw instanceof Map) {
            return (Map<String, Object>) raw;
        }
        return null;
    }

    /**
     * 创建一个变量的元数据对象。
     */
    private static Map<String, Object> createVarMeta(Object value, String type, int pid) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("value", value);
        meta.put("type", type != null ? type : Constants.SWAP_TYPE_ALWAYS);
        meta.put("addTime", FileUtil.getCurrentTimeArray());
        meta.put("editTime", FileUtil.getCurrentTimeArray());
        meta.put("changed", false);
        meta.put("locked", false);
        meta.put("lockedBy", null);
        meta.put("whitelist", new ArrayList<>());
        meta.put("blacklist", new ArrayList<>());
        if (type != null && type.startsWith(Constants.SWAP_TYPE_TIMES_PREFIX)) {
            // times(N) → 提取数字
            int count = 1;
            String inner = type.substring(Constants.SWAP_TYPE_TIMES_PREFIX.length()).replace("(", "").replace(")", "").trim();
            try { count = Integer.parseInt(inner); } catch (Exception ignored) {}
            meta.put("readCount", count);
        }
        return meta;
    }

    /**
     * 检查 PID 是否有权访问变量（白/黑名单）。
     */
    @SuppressWarnings("unchecked")
    private static boolean checkAccess(Map<String, Object> varMeta, int pid) {
        if (varMeta == null) return false;
        List<Object> whitelist = (List<Object>) varMeta.get("whitelist");
        List<Object> blacklist = (List<Object>) varMeta.get("blacklist");
        if (blacklist != null && !blacklist.isEmpty()) {
            for (Object id : blacklist) {
                if (id instanceof Number && ((Number) id).intValue() == pid) return false;
                if (id instanceof String && id.equals(String.valueOf(pid))) return false;
            }
        }
        if (whitelist != null && !whitelist.isEmpty()) {
            for (Object id : whitelist) {
                if (id instanceof Number && ((Number) id).intValue() == pid) return true;
                if (id instanceof String && id.equals(String.valueOf(pid))) return true;
            }
            return false; // 白名单有内容但当前 PID 不在其中
        }
        return true; // 无限制
    }

    // ════════════════════════════════════════════
    // 交换池 CRUD
    // ════════════════════════════════════════════

    /**
     * 创建交换池。
     * FCL: swapPool.create("pool1")
     */
    private static Object createPool(String name, int pid) {
        if (name == null || name.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        if (poolExists(name) == Boolean.TRUE) {
            return error("Pool already exists: " + name);
        }
        FileUtil.createFile(Constants.SYSTEM_SWAP_PATH, name + ".json");

        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("name", name);
        pool.put("OwnerPID", pid);
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("createTime", FileUtil.getCurrentTimeArray());
        time.put("lastEditTime", FileUtil.getCurrentTimeArray());
        pool.put("time", time);
        pool.put("content", new LinkedHashMap<String, Object>());

        writePool(name, pool);
        return "Swap pool created: " + name;
    }

    /**
     * 删除交换池。
     * FCL: swapPool.remove("pool1")
     */
    private static Object removePool(String name) {
        if (name == null || name.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        String path = getPoolPath(name);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + name);
        }
        FileUtil.removeFile(path);
        return "Swap pool removed: " + name;
    }

    /**
     * 添加变量。
     * FCL: swapPool.add("msg:hello", "pool1")
     * FCL: swapPool.add("msg:hello", "pool1", "type:sync")
     * FCL: swapPool.add("msg:hello", "pool1", "type:times(3)")
     * FCL: swapPool.add("msg:hello", "pool1", "type:sync", "whitelist:2,3")
     */
    @SuppressWarnings("unchecked")
    private static Object addVariable(String data, String poolName, List<Object> params, int pid) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        if (data == null || !data.contains(":")) {
            return error("Invalid format, expected varName:value");
        }

        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + poolName);
        }

        // 解析 varName:value
        int colon = data.indexOf(':');
        String varName = data.substring(0, colon);
        String varValue = data.substring(colon + 1);

        // 解析参数：type, whitelist, blacklist
        String type = Constants.SWAP_TYPE_ALWAYS;
        List<Integer> whitelist = new ArrayList<>();
        List<Integer> blacklist = new ArrayList<>();

        if (params != null) {
            for (Object p : params) {
                if (!(p instanceof String)) continue;
                String param = (String) p;
                if (param.startsWith("type:")) {
                    type = param.substring(5);
                } else if (param.startsWith("whitelist:")) {
                    String csv = param.substring(10);
                    for (String s : csv.split(",")) {
                        try { whitelist.add(Integer.parseInt(s.trim())); } catch (Exception ignored) {}
                    }
                } else if (param.startsWith("blacklist:")) {
                    String csv = param.substring(10);
                    for (String s : csv.split(",")) {
                        try { blacklist.add(Integer.parseInt(s.trim())); } catch (Exception ignored) {}
                    }
                }
            }
        }

        // 创建变量元数据
        Map<String, Object> varMeta = createVarMeta(varValue, type, pid);
        varMeta.put("whitelist", whitelist);
        varMeta.put("blacklist", blacklist);

        // 原子写入 content.varName
        JsonUtil.setField(path, "content." + varName, varMeta);

        // 如果是 sync 类型，自动设置 changed=true
        if (type.equals(Constants.SWAP_TYPE_SYNC)) {
            JsonUtil.setField(path, "content." + varName + ".changed", true);
        }

        JsonUtil.setField(path, "time.lastEditTime",
                          FileUtil.getCurrentTimeArray());
        return "Variable added: " + varName + " (type=" + type + ")";
    }

    /**
     * 获取变量值（type 感知）。
     * FCL: val = swapPool.get("msg", "pool1")
     * <p>
     * - sync 类型：返回 value，并将 changed 置为 false
     * - times(N) 类型：返回 value，readCount 减 1，减到 0 自动删除变量
     * - always 类型：直接返回 value
     */
    @SuppressWarnings("unchecked")
    private static Object getVariable(String varName, String poolName, int pid) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + poolName);
        }

        // 访问控制检查
        Object raw = JsonUtil.getField(path, "content." + varName);
        if (raw == null) {
            return error("Variable not found: " + varName);
        }
        if (!(raw instanceof Map)) {
            return raw; // 兼容旧格式：直接返回原始值
        }

        Map<String, Object> varMeta = (Map<String, Object>) raw;
        if (!checkAccess(varMeta, pid)) {
            return error("Access denied: PID " + pid + " cannot access " + varName);
        }
        if (Boolean.TRUE.equals(varMeta.get("locked"))) {
            return error("Variable is locked: " + varName);
        }

        Object value = varMeta.get("value");
        String type = (String) varMeta.getOrDefault("type", Constants.SWAP_TYPE_ALWAYS);
        int[] timeArray = FileUtil.getCurrentTimeArray();

        // sync 类型：标记已读，清除 changed
        if (Constants.SWAP_TYPE_SYNC.equals(type)) {
            if (Boolean.TRUE.equals(varMeta.get("changed"))) {
                JsonUtil.setField(path, "content." + varName + ".changed", false);
                JsonUtil.setField(path, "content." + varName + ".editTime", timeArray);
                JsonUtil.setField(path, "time.lastEditTime", timeArray);
            }
        }

        // times(N) 类型：递减，到 0 删除
        if (type != null && type.startsWith(Constants.SWAP_TYPE_TIMES_PREFIX)) {
            int readCount = varMeta.containsKey("readCount")
                    ? ((Number) varMeta.get("readCount")).intValue() : 0;
            if (readCount <= 1) {
                JsonUtil.removeField(path, "content." + varName);
            } else {
                JsonUtil.setField(path, "content." + varName + ".readCount", readCount - 1);
                JsonUtil.setField(path, "content." + varName + ".editTime", timeArray);
            }
            JsonUtil.setField(path, "time.lastEditTime", timeArray);
        }

        return value;
    }

    /**
     * 删除变量。
     * FCL: swapPool.removeVar("msg", "pool1")
     */
    private static Object removeVariable(String varName, String poolName) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + poolName);
        }
        if (JsonUtil.getField(path, "content." + varName) == null) {
            return error("Variable not found: " + varName);
        }
        JsonUtil.removeField(path, "content." + varName);
        JsonUtil.setField(path, "time.lastEditTime",
                          FileUtil.getCurrentTimeArray());
        return "Variable removed: " + varName;
    }

    /**
     * 更新变量值（type 感知）。
     * FCL: swapPool.update("msg", "pool1", "newValue")
     * <p>
     * - sync 类型：更新 value 并将 changed 置为 true
     * - always/times(N)：仅更新 value
     */
    @SuppressWarnings("unchecked")
    private static Object updateVariable(String varName, String poolName, String newValue, int pid) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + poolName);
        }

        // 锁定检查：先读 locked 和 type 字段
        Object locked = JsonUtil.getField(path, "content." + varName + ".locked");
        if (Boolean.TRUE.equals(locked)) {
            Object lockedBy = JsonUtil.getField(path, "content." + varName + ".lockedBy");
            if (lockedBy instanceof Number && ((Number) lockedBy).intValue() != pid) {
                return error("Variable is locked by PID " + lockedBy);
            }
        }
        Object type = JsonUtil.getField(path, "content." + varName + ".type");

        JsonUtil.setField(path, "content." + varName + ".value", newValue);
        JsonUtil.setField(path, "content." + varName + ".editTime",
                          FileUtil.getCurrentTimeArray());

        // sync 类型：标记 changed
        if (Constants.SWAP_TYPE_SYNC.equals(type)) {
            JsonUtil.setField(path, "content." + varName + ".changed", true);
        }

        JsonUtil.setField(path, "time.lastEditTime",
                          FileUtil.getCurrentTimeArray());
        return "Variable updated: " + varName;
    }

    /**
     * 锁定变量。
     * FCL: swapPool.lock("msg", "pool1")
     */
    @SuppressWarnings("unchecked")
    private static Object lockVariable(String varName, String poolName, int pid) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + poolName);
        }
        Object raw = JsonUtil.getField(path, "content." + varName);
        if (raw == null) {
            return error("Variable not found: " + varName);
        }
        if (!(raw instanceof Map)) {
            return error("Variable has no metadata");
        }
        JsonUtil.setField(path, "content." + varName + ".locked", true);
        JsonUtil.setField(path, "content." + varName + ".lockedBy", pid);
        JsonUtil.setField(path, "time.lastEditTime",
                          FileUtil.getCurrentTimeArray());
        return "Variable locked: " + varName;
    }

    /**
     * 解锁变量。
     * FCL: swapPool.unlock("msg", "pool1")
     */
    @SuppressWarnings("unchecked")
    private static Object unlockVariable(String varName, String poolName, int pid) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + poolName);
        }
        Object raw = JsonUtil.getField(path, "content." + varName);
        if (raw == null) {
            return error("Variable not found: " + varName);
        }
        if (!(raw instanceof Map)) {
            return error("Variable has no metadata");
        }
        JsonUtil.setField(path, "content." + varName + ".locked", false);
        JsonUtil.setField(path, "content." + varName + ".lockedBy", null);
        JsonUtil.setField(path, "time.lastEditTime",
                          FileUtil.getCurrentTimeArray());
        return "Variable unlocked: " + varName;
    }

    /**
     * 列出池中所有变量（含类型信息）。
     * FCL: swapPool.ls("pool1")
     */
    @SuppressWarnings("unchecked")
    private static Object listVariables(String poolName) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + poolName);
        }
        Object rawContent = JsonUtil.getField(path, "content");
        if (!(rawContent instanceof Map) || ((Map) rawContent).isEmpty()) {
            return "(empty)";
        }
        Map<String, Object> content = (Map<String, Object>) rawContent;

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : content.entrySet()) {
            String name = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Map) {
                Map<String, Object> meta = (Map<String, Object>) val;
                Object value = meta.get("value");
                String type = (String) meta.getOrDefault("type", "always");
                boolean locked = Boolean.TRUE.equals(meta.get("locked"));
                boolean changed = Boolean.TRUE.equals(meta.get("changed"));
                sb.append(name).append(" = ").append(value)
                        .append(" [type=").append(type)
                        .append(locked ? ", LOCKED" : "")
                        .append(changed ? ", CHANGED" : "")
                        .append("]\n");
            } else {
                sb.append(name).append(" = ").append(val).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 清空池中所有变量。
     * FCL: swapPool.clear("pool1")
     */
    private static Object clearPool(String poolName) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + poolName);
        }
        JsonUtil.setField(path, "content", new LinkedHashMap<String, Object>());
        JsonUtil.setField(path, "time.lastEditTime",
                          FileUtil.getCurrentTimeArray());
        return "Pool cleared: " + poolName;
    }

    /**
     * 检查交换池是否存在。
     * FCL: swapPool.exists("pool1")
     */
    private static Object poolExists(String poolName) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return false;
        }
        return FileUtil.exists(getPoolPath(poolName));
    }

    /**
     * 等待 sync 变量变更。
     * FCL: swapPool.waitFor("msg", "pool1")
     * <p>
     * 轮询检查变量的 changed 标记，每 100ms 检查一次。
     * 当 changed=true 时返回当前值并将 changed 置为 false。
     */
    @SuppressWarnings("unchecked")
    private static Object waitForVariable(String varName, String poolName, int pid) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + poolName);
        }

        long start = System.currentTimeMillis();
        long timeout = 30000; // 30 秒超时

        while (System.currentTimeMillis() - start < timeout) {
            Object raw = JsonUtil.getField(path, "content." + varName);
            if (raw == null) {
                return error("Variable not found: " + varName);
            }
            if (!(raw instanceof Map)) {
                return raw; // 旧格式，直接返回
            }
            Map<String, Object> varMeta = (Map<String, Object>) raw;

            // 访问控制
            if (!checkAccess(varMeta, pid)) {
                return error("Access denied: PID " + pid);
            }

            if (Boolean.TRUE.equals(varMeta.get("changed"))) {
                // 找到变更，消费它
                JsonUtil.setField(path, "content." + varName + ".changed", false);
                JsonUtil.setField(path, "content." + varName + ".editTime",
                                  FileUtil.getCurrentTimeArray());
                JsonUtil.setField(path, "time.lastEditTime",
                                  FileUtil.getCurrentTimeArray());
                return varMeta.get("value");
            }

            // 没变更，休眠再试
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("waitFor interrupted");
            }
        }

        return error("waitFor timeout (30s) for " + varName);
    }

    /**
     * 信号：标记 sync 变量已变更（唤醒等待者）。
     * FCL: swapPool.signal("msg", "pool1")
     */
    @SuppressWarnings("unchecked")
    private static Object signalVariable(String varName, String poolName, int pid) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        String path = getPoolPath(poolName);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + poolName);
        }
        Object raw = JsonUtil.getField(path, "content." + varName);
        if (raw == null) {
            return error("Variable not found: " + varName);
        }
        if (!(raw instanceof Map)) {
            return error("Variable has no metadata");
        }
        JsonUtil.setField(path, "content." + varName + ".changed", true);
        JsonUtil.setField(path, "content." + varName + ".editTime",
                          FileUtil.getCurrentTimeArray());
        JsonUtil.setField(path, "time.lastEditTime",
                          FileUtil.getCurrentTimeArray());
        return "Signal sent for: " + varName;
    }

    /**
     * 列出所有交换池。
     * FCL: swapPool.list()
     */
    private static Object listAllPools() {
        String swapDir = Constants.SYSTEM_SWAP_PATH;
        if (!FileUtil.exists(swapDir)) {
            return "(no pools)";
        }
        var dirs = FileUtil.getListOfFileAndDirectory(swapDir);
        if (dirs == null || dirs.isEmpty()) {
            return "(no pools)";
        }
        return dirs.stream()
                .map(m -> {
                    String name = (String) m.get("name");
                    if (name != null && name.endsWith(".json")) {
                        return name.substring(0, name.length() - 5);
                    }
                    return name;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    // ════════════════════════════════════════════
    // 辅助方法
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private static void updatePoolTime(Map<String, Object> pool) {
        if (pool == null) return;
        Map<String, Object> time = (Map<String, Object>) pool.get("time");
        if (time == null) {
            time = new LinkedHashMap<>();
            pool.put("time", time);
        }
        time.put("lastEditTime", FileUtil.getCurrentTimeArray());
    }

    private static String error(String msg) {
        return "ERROR: " + msg;
    }

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