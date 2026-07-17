package com.follarce.function;

import com.follarce.Constants;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.EffectLedger;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
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
 *       "lockedByGeneration": null,
 *       "leaseUntilEpochMs": 0,
 *       "fencingToken": 0,
 *       "whitelist": [],          // 允许读取的 PID 列表（空=不限）
 *       "blacklist": [],          // 禁止读取的 PID 列表
 *       "readCount": 0            // times(N) 用：剩余可读次数
 *     }
 *   },
 *   "AppliedEffects": {}          // effect ID -> serialized logical result
 * }
 * </pre>
 * <p>
 * 命名空间: "swapPool"
 */
public class SwapFunctionProvider implements FunctionProvider {
    private static final int VARIABLE_LOCK_VERSION = 2;
    private static final String LEGACY_LOCK_GENERATION = "legacy";
    private static final Object WAIT_PENDING = new Object();
    private static final Set<String> TRANSACTIONAL_FUNCTIONS = Set.of(
            "create", "add", "get", "removeVar", "update",
            "unlock", "clear", "waitFor", "signal");

    @Override
    public String getNamespace() {
        return "swapPool";
    }

    @Override
    public EffectPolicy getEffectPolicy(String functionName) {
        if (TRANSACTIONAL_FUNCTIONS.contains(functionName)) {
            return EffectPolicy.LOCAL_TRANSACTIONAL;
        }
        return FunctionProvider.super.getEffectPolicy(functionName);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            switch (functionName) {

                case "create":
                    return createPool(getStringArg(args, 0), context);

                case "remove":
                    return removePool(getStringArg(args, 0), context);

                case "add":
                    return addVariable(getStringArg(args, 0), getStringArg(args, 1),
                            tailArgs(args, 2), context);

                case "get":
                    return getVariable(getStringArg(args, 0), getStringArg(args, 1), context);

                case "removeVar":
                    return removeVariable(getStringArg(args, 0), getStringArg(args, 1),
                            getLongArg(args, 2), context);

                case "update":
                    return updateVariable(getStringArg(args, 0), getStringArg(args, 1),
                            getStringArg(args, 2), getLongArg(args, 3), context);

                case "lock":
                    return lockVariable(getStringArg(args, 0), getStringArg(args, 1),
                            getLongArg(args, 2), context);

                case "renewLock":
                    return renewVariableLock(getStringArg(args, 0), getStringArg(args, 1),
                            getLongArg(args, 2), getLongArg(args, 3), context);

                case "unlock":
                    return unlockVariable(getStringArg(args, 0), getStringArg(args, 1),
                            getLongArg(args, 2), context);

                case "ls":
                    return listVariables(getStringArg(args, 0), context.getPid());

                case "clear":
                    return clearPool(getStringArg(args, 0), context);

                case "exists":
                    return poolExists(getStringArg(args, 0));

                case "waitFor":
                    return waitForVariable(getStringArg(args, 0), getStringArg(args, 1), context);

                case "signal":
                    return signalVariable(getStringArg(args, 0), getStringArg(args, 1), context);

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
        validatePoolName(poolName);
        return Constants.SYSTEM_SWAP_PATH + poolName + ".json";
    }

    private static void validatePoolName(String poolName) {
        if (poolName == null || !poolName.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid pool name: " + poolName);
        }
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

    private static Map<String, Object> getOrCreateContent(Map<String, Object> pool) {
        Map<String, Object> content = getContent(pool);
        if (content == null) {
            content = new LinkedHashMap<>();
            pool.put("content", content);
        }
        return content;
    }

    /**
     * 创建一个变量的元数据对象。
     */
    private static Map<String, Object> createVarMeta(Object value, String type) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("value", value);
        meta.put("type", type != null ? type : Constants.SWAP_TYPE_ALWAYS);
        meta.put("addTime", FileUtil.getCurrentTimeArray());
        meta.put("editTime", FileUtil.getCurrentTimeArray());
        meta.put("changed", false);
        meta.put("lockVersion", VARIABLE_LOCK_VERSION);
        meta.put("locked", false);
        meta.put("lockedBy", null);
        meta.put("lockedByGeneration", null);
        meta.put("leaseUntilEpochMs", 0L);
        meta.put("fencingToken", 0L);
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

    @FunctionalInterface
    private interface PoolMutation {
        MutationResult apply(Map<String, Object> pool);
    }

    private static final class MutationResult {
        private final Object result;
        private final boolean changed;
        private final boolean complete;

        private MutationResult(Object result, boolean changed, boolean complete) {
            this.result = result;
            this.changed = changed;
            this.complete = complete;
        }

        private static MutationResult complete(Object result, boolean changed) {
            return new MutationResult(result, changed, true);
        }

        private static MutationResult pending() {
            return new MutationResult(WAIT_PENDING, false, false);
        }
    }

    /** Commits a pool mutation and its effect receipt in one atomic replacement. */
    @SuppressWarnings("unchecked")
    private static Object transactPool(String poolName, FunctionContext context,
                                       PoolMutation mutation) {
        String path = getPoolPath(poolName);
        EffectLedger.Lookup durableResult = EffectLedger.lookup(context.getEffectId());
        if (durableResult.found()) return durableResult.result();
        ReentrantLock lock = JsonUtil.lockFile(path);
        try {
            if (!FileUtil.exists(path)) {
                return error("Swap pool not found: " + poolName);
            }

            Map<String, Object> pool = JsonUtil.parseToMapStrict(FileUtil.read(path));
            Map<String, Object> appliedEffects = null;
            Object rawEffects = pool.get("AppliedEffects");
            if (rawEffects instanceof Map) {
                appliedEffects = (Map<String, Object>) rawEffects;
            } else if (rawEffects != null) {
                throw new IllegalArgumentException("Invalid AppliedEffects in pool: " + poolName);
            }

            String effectId = context.getEffectId();
            if (effectId != null && appliedEffects != null && appliedEffects.containsKey(effectId)) {
                return deserializeResult(appliedEffects.get(effectId));
            }

            MutationResult outcome = mutation.apply(pool);
            if (!outcome.complete) return outcome.result;

            Object logicalResult = outcome.result;
            boolean changed = outcome.changed;
            if (effectId != null) {
                if (appliedEffects == null) {
                    appliedEffects = new LinkedHashMap<>();
                    pool.put("AppliedEffects", appliedEffects);
                }
                String serialized = JsonUtil.toJson(logicalResult);
                appliedEffects.put(effectId, serialized);
                logicalResult = deserializeResult(serialized);
                changed = true;
            }
            if (changed) {
                FileUtil.writeAtomic(path, JsonUtil.toMetaJson(pool));
            }
            return logicalResult;
        } finally {
            lock.unlock();
        }
    }

    private static Object deserializeResult(Object stored) {
        if (stored instanceof String) {
            Object result = JsonUtil.parseJson((String) stored);
            if (result instanceof String[]) {
                throw new IllegalArgumentException("Invalid serialized effect result");
            }
            return result;
        }
        return JsonUtil.deepCopy(stored);
    }

    private static boolean isVariableLockActive(Map<String, Object> varMeta, long now) {
        if (!Boolean.TRUE.equals(varMeta.get("locked"))) return false;
        Object deadline = varMeta.get("leaseUntilEpochMs");
        // Legacy locks had no lease and remain active until a legacy owner releases them.
        return !(deadline instanceof Number) || ((Number) deadline).longValue() > now;
    }

    private static boolean isUpdateOwner(Map<String, Object> varMeta, FunctionContext context) {
        if (!isPidOwner(varMeta, context.getPid())) return false;
        if (context.getProcessGeneration() == null) {
            Object storedGeneration = varMeta.get("lockedByGeneration");
            return storedGeneration == null || LEGACY_LOCK_GENERATION.equals(storedGeneration);
        }
        return Objects.equals(varMeta.get("lockedByGeneration"),
                context.getProcessGeneration());
    }

    private static boolean isExactLockOwner(Map<String, Object> varMeta,
                                            FunctionContext context, Long token) {
        if (token == null || !isPidOwner(varMeta, context.getPid())) return false;
        return Objects.equals(varMeta.get("lockedByGeneration"), lockGeneration(context))
                && longNumber(varMeta.get("fencingToken"), Long.MIN_VALUE) == token;
    }

    private static boolean isPidOwner(Map<String, Object> varMeta, int pid) {
        Object owner = varMeta.get("lockedBy");
        return owner instanceof Number && ((Number) owner).intValue() == pid;
    }

    private static boolean isLegacyLockOwner(Map<String, Object> varMeta, int pid) {
        if (!isPidOwner(varMeta, pid)) return false;
        Object generation = varMeta.get("lockedByGeneration");
        return generation == null || LEGACY_LOCK_GENERATION.equals(generation);
    }

    private static String lockGeneration(FunctionContext context) {
        return context.getProcessGeneration() != null
                ? context.getProcessGeneration() : LEGACY_LOCK_GENERATION;
    }

    private static long leaseDeadline(long now, long leaseMs) {
        return leaseMs > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + leaseMs;
    }

    private static long longNumber(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static Map<String, Object> lockHandle(long token, long deadline) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fencingToken", token);
        result.put("leaseUntilEpochMs", deadline);
        return result;
    }

    private static void clearVariableLock(Map<String, Object> varMeta) {
        varMeta.put("lockVersion", VARIABLE_LOCK_VERSION);
        varMeta.put("locked", false);
        varMeta.put("lockedBy", null);
        varMeta.put("lockedByGeneration", null);
        varMeta.put("leaseUntilEpochMs", 0L);
        if (!(varMeta.get("fencingToken") instanceof Number)) {
            varMeta.put("fencingToken", 0L);
        }
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
    @SuppressWarnings("unchecked")
    private static Object createPool(String name, FunctionContext context) {
        if (name == null || name.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }

        String path = getPoolPath(name);
        try {
            FileUtil.createFileOnce(Constants.SYSTEM_SWAP_PATH, name + ".json",
                    context.getEffectId(), context.getCurrentUser());
        } catch (RuntimeException e) {
            if (FileUtil.exists(path)) return error("Pool already exists: " + name);
            throw e;
        }

        ReentrantLock lock = JsonUtil.lockFile(path);
        try {
            String body = FileUtil.read(path);
            Map<String, Object> pool;
            if (body == null || body.trim().isEmpty()) {
                pool = new LinkedHashMap<>();
                pool.put("name", name);
                pool.put("OwnerPID", context.getPid());
                Map<String, Object> time = new LinkedHashMap<>();
                time.put("createTime", FileUtil.getCurrentTimeArray());
                time.put("lastEditTime", FileUtil.getCurrentTimeArray());
                pool.put("time", time);
                pool.put("content", new LinkedHashMap<String, Object>());
                pool.put("AppliedEffects", new LinkedHashMap<String, Object>());
            } else {
                pool = JsonUtil.parseToMapStrict(body);
            }

            Map<String, Object> appliedEffects;
            Object rawEffects = pool.get("AppliedEffects");
            if (rawEffects instanceof Map) {
                appliedEffects = (Map<String, Object>) rawEffects;
            } else if (rawEffects == null) {
                appliedEffects = new LinkedHashMap<>();
                pool.put("AppliedEffects", appliedEffects);
            } else {
                throw new IllegalArgumentException("Invalid AppliedEffects in pool: " + name);
            }

            String effectId = context.getEffectId();
            if (effectId != null && appliedEffects.containsKey(effectId)) {
                return deserializeResult(appliedEffects.get(effectId));
            }

            Object result = "Swap pool created: " + name;
            if (effectId != null) {
                String serialized = JsonUtil.toJson(result);
                appliedEffects.put(effectId, serialized);
                result = deserializeResult(serialized);
            }
            FileUtil.writeAtomic(path, JsonUtil.toMetaJson(pool));
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 删除交换池。
     * FCL: swapPool.remove("pool1")
     */
    private static Object removePool(String name, FunctionContext context) {
        if (name == null || name.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        String path = getPoolPath(name);
        if (!FileUtil.exists(path)) {
            return error("Swap pool not found: " + name);
        }
        Map<String, Object> pool = JsonUtil.parseToMapStrict(FileUtil.read(path));
        Object owner = pool.get("OwnerPID");
        if (!Constants.DEFAULT_USER_LOCAL.equals(context.getCurrentUser())
                && (!(owner instanceof Number) || ((Number) owner).intValue() != context.getPid())) {
            return error("Only the pool owner can remove: " + name);
        }
        Object effects = pool.get("AppliedEffects");
        if (effects instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) effects).entrySet()) {
                EffectLedger.record(String.valueOf(entry.getKey()), deserializeResult(entry.getValue()));
            }
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
    private static Object addVariable(String data, String poolName, List<Object> params,
                                      FunctionContext context) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        if (data == null || !data.contains(":")) {
            return error("Invalid format, expected varName:value");
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

        String resolvedType = type;
        return transactPool(poolName, context, pool -> {
            Map<String, Object> content = getOrCreateContent(pool);
            Object existing = content.get(varName);
            if (existing != null) {
                return MutationResult.complete(error("Variable already exists: " + varName), false);
            }
            Map<String, Object> varMeta = createVarMeta(varValue, resolvedType);
            varMeta.put("whitelist", new ArrayList<>(whitelist));
            varMeta.put("blacklist", new ArrayList<>(blacklist));
            if (Constants.SWAP_TYPE_SYNC.equals(resolvedType)) {
                varMeta.put("changed", true);
            }
            content.put(varName, varMeta);
            updatePoolTime(pool);
            return MutationResult.complete(
                    "Variable added: " + varName + " (type=" + resolvedType + ")", true);
        });
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
    private static Object getVariable(String varName, String poolName, FunctionContext context) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        return transactPool(poolName, context, pool -> {
            Map<String, Object> content = getContent(pool);
            Object raw = content != null ? content.get(varName) : null;
            if (raw == null) {
                return MutationResult.complete(error("Variable not found: " + varName), false);
            }
            if (!(raw instanceof Map)) {
                return MutationResult.complete(raw, false);
            }

            Map<String, Object> varMeta = (Map<String, Object>) raw;
            int pid = context.getPid();
            if (!checkAccess(varMeta, pid)) {
                return MutationResult.complete(
                        error("Access denied: PID " + pid + " cannot access " + varName), false);
            }
            if (isVariableLockActive(varMeta, System.currentTimeMillis())) {
                return MutationResult.complete(error("Variable is locked: " + varName), false);
            }

            Object value = varMeta.get("value");
            String type = (String) varMeta.getOrDefault("type", Constants.SWAP_TYPE_ALWAYS);
            boolean changed = false;
            if (Constants.SWAP_TYPE_SYNC.equals(type)
                    && Boolean.TRUE.equals(varMeta.get("changed"))) {
                varMeta.put("changed", false);
                varMeta.put("editTime", FileUtil.getCurrentTimeArray());
                changed = true;
            }

            if (type != null && type.startsWith(Constants.SWAP_TYPE_TIMES_PREFIX)) {
                int readCount = varMeta.get("readCount") instanceof Number
                        ? ((Number) varMeta.get("readCount")).intValue() : 0;
                if (readCount <= 1) {
                    content.remove(varName);
                } else {
                    varMeta.put("readCount", readCount - 1);
                    varMeta.put("editTime", FileUtil.getCurrentTimeArray());
                }
                changed = true;
            }
            if (changed) updatePoolTime(pool);
            return MutationResult.complete(value, changed);
        });
    }

    /**
     * 删除变量。
     * FCL: swapPool.removeVar("msg", "pool1")
     */
    private static Object removeVariable(String varName, String poolName,
                                          Long token, FunctionContext context) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        return transactPool(poolName, context, pool -> {
            Map<String, Object> content = getContent(pool);
            if (content == null || !content.containsKey(varName)) {
                return MutationResult.complete(error("Variable not found: " + varName), false);
            }
            Object raw = content.get(varName);
            if (raw instanceof Map) {
                Map<String, Object> varMeta = (Map<String, Object>) raw;
                if (isVariableLockActive(varMeta, System.currentTimeMillis())
                        && !isExactLockOwner(varMeta, context, token)) {
                    return MutationResult.complete(error("Variable is locked: " + varName), false);
                }
            }
            content.remove(varName);
            updatePoolTime(pool);
            return MutationResult.complete("Variable removed: " + varName, true);
        });
    }

    /**
     * 更新变量值（type 感知）。
     * FCL: swapPool.update("msg", "pool1", "newValue")
     * <p>
     * - sync 类型：更新 value 并将 changed 置为 true
     * - always/times(N)：仅更新 value
    */
    @SuppressWarnings("unchecked")
    private static Object updateVariable(String varName, String poolName, String newValue,
                                          Long token, FunctionContext context) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        return transactPool(poolName, context, pool -> {
            Map<String, Object> content = getContent(pool);
            Object raw = content != null ? content.get(varName) : null;
            if (!(raw instanceof Map)) {
                return MutationResult.complete(error("Variable not found: " + varName), false);
            }
            Map<String, Object> varMeta = (Map<String, Object>) raw;
            if (isVariableLockActive(varMeta, System.currentTimeMillis())
                    && !isExactLockOwner(varMeta, context, token)) {
                return MutationResult.complete(
                        error("Variable is locked by PID " + varMeta.get("lockedBy")), false);
            }

            varMeta.put("value", newValue);
            varMeta.put("editTime", FileUtil.getCurrentTimeArray());
            if (Constants.SWAP_TYPE_SYNC.equals(varMeta.get("type"))) {
                varMeta.put("changed", true);
            }
            updatePoolTime(pool);
            return MutationResult.complete("Variable updated: " + varName, true);
        });
    }

    /**
     * 锁定变量。
     * FCL: swapPool.lock("msg", "pool1")
     */
    @SuppressWarnings("unchecked")
    private static Object lockVariable(String varName, String poolName, Long requestedLeaseMs,
                                       FunctionContext context) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        long leaseMs = requestedLeaseMs != null
                ? requestedLeaseMs : Constants.DEFAULT_FILE_LOCK_LEASE_MS;
        return transactPool(poolName, context, pool -> {
            if (leaseMs <= 0) {
                return MutationResult.complete(error("leaseMs must be greater than zero"), false);
            }
            Map<String, Object> content = getContent(pool);
            Object raw = content != null ? content.get(varName) : null;
            if (raw == null) {
                return MutationResult.complete(error("Variable not found: " + varName), false);
            }
            if (!(raw instanceof Map)) {
                return MutationResult.complete(error("Variable has no metadata"), false);
            }
            Map<String, Object> varMeta = (Map<String, Object>) raw;
            long now = System.currentTimeMillis();
            if (isVariableLockActive(varMeta, now)) {
                return MutationResult.complete(
                        error("Variable is locked by PID " + varMeta.get("lockedBy")), false);
            }

            long previousToken = longNumber(varMeta.get("fencingToken"), 0L);
            if (previousToken < 0) previousToken = 0;
            if (previousToken == Long.MAX_VALUE) {
                return MutationResult.complete(error("Fencing token exhausted for: " + varName), false);
            }
            long token = previousToken + 1L;
            long deadline = leaseDeadline(now, leaseMs);
            varMeta.put("lockVersion", VARIABLE_LOCK_VERSION);
            varMeta.put("locked", true);
            varMeta.put("lockedBy", context.getPid());
            varMeta.put("lockedByGeneration", lockGeneration(context));
            varMeta.put("leaseUntilEpochMs", deadline);
            varMeta.put("fencingToken", token);
            updatePoolTime(pool);
            return MutationResult.complete(lockHandle(token, deadline), true);
        });
    }

    /** Renews a variable lease without changing its fencing token. */
    @SuppressWarnings("unchecked")
    private static Object renewVariableLock(String varName, String poolName, Long token,
                                            Long requestedLeaseMs, FunctionContext context) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        long leaseMs = requestedLeaseMs != null
                ? requestedLeaseMs : Constants.DEFAULT_FILE_LOCK_LEASE_MS;
        return transactPool(poolName, context, pool -> {
            if (token == null) {
                return MutationResult.complete(error("renewLock requires a fencing token"), false);
            }
            if (leaseMs <= 0) {
                return MutationResult.complete(error("leaseMs must be greater than zero"), false);
            }
            Map<String, Object> content = getContent(pool);
            Object raw = content != null ? content.get(varName) : null;
            if (!(raw instanceof Map)) {
                return MutationResult.complete(error("Variable not found: " + varName), false);
            }
            Map<String, Object> varMeta = (Map<String, Object>) raw;
            long now = System.currentTimeMillis();
            if (!isVariableLockActive(varMeta, now)
                    || !isExactLockOwner(varMeta, context, token)) {
                return MutationResult.complete(
                        error("Not authorized to renew lock: " + varName), false);
            }

            long deadline = leaseDeadline(now, leaseMs);
            varMeta.put("lockVersion", VARIABLE_LOCK_VERSION);
            varMeta.put("leaseUntilEpochMs", deadline);
            updatePoolTime(pool);
            return MutationResult.complete(lockHandle(token, deadline), true);
        });
    }

    /**
     * 解锁变量。
     * FCL: swapPool.unlock("msg", "pool1")
     */
    @SuppressWarnings("unchecked")
    private static Object unlockVariable(String varName, String poolName, Long token,
                                         FunctionContext context) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        return transactPool(poolName, context, pool -> {
            if (context.getProcessGeneration() != null && token == null) {
                return MutationResult.complete(error("unlock requires a fencing token"), false);
            }
            Map<String, Object> content = getContent(pool);
            Object raw = content != null ? content.get(varName) : null;
            if (raw == null) {
                return MutationResult.complete(error("Variable not found: " + varName), false);
            }
            if (!(raw instanceof Map)) {
                return MutationResult.complete(error("Variable has no metadata"), false);
            }
            Map<String, Object> varMeta = (Map<String, Object>) raw;
            if (Boolean.TRUE.equals(varMeta.get("locked"))) {
                boolean authorized = context.getProcessGeneration() == null
                        ? isLegacyLockOwner(varMeta, context.getPid())
                        : isExactLockOwner(varMeta, context, token);
                if (!authorized) {
                    return MutationResult.complete(
                            error("Not authorized to unlock: " + varName), false);
                }
                clearVariableLock(varMeta);
                updatePoolTime(pool);
                return MutationResult.complete("Variable unlocked: " + varName, true);
            }
            return MutationResult.complete("Variable unlocked: " + varName, false);
        });
    }

    /**
     * 列出池中所有变量（含类型信息）。
     * FCL: swapPool.ls("pool1")
     */
    @SuppressWarnings("unchecked")
    private static Object listVariables(String poolName, int pid) {
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
                if (!checkAccess(meta, pid)) continue;
                Object value = meta.get("value");
                String type = (String) meta.getOrDefault("type", "always");
                boolean locked = isVariableLockActive(meta, System.currentTimeMillis());
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
    private static Object clearPool(String poolName, FunctionContext context) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        return transactPool(poolName, context, pool -> {
            Map<String, Object> existing = getContent(pool);
            if (existing != null) {
                for (Object value : existing.values()) {
                    if (value instanceof Map
                            && isVariableLockActive((Map<String, Object>) value,
                            System.currentTimeMillis())) {
                        return MutationResult.complete(error("Pool contains locked variables"), false);
                    }
                }
            }
            pool.put("content", new LinkedHashMap<String, Object>());
            updatePoolTime(pool);
            return MutationResult.complete("Pool cleared: " + poolName, true);
        });
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
    private static Object waitForVariable(String varName, String poolName,
                                          FunctionContext context) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }

        long start = System.currentTimeMillis();
        long timeout = 30000; // 30 秒超时

        while (System.currentTimeMillis() - start < timeout) {
            Object result = transactPool(poolName, context, pool -> {
                Map<String, Object> content = getContent(pool);
                Object raw = content != null ? content.get(varName) : null;
                if (raw == null) {
                    return MutationResult.complete(error("Variable not found: " + varName), false);
                }
                if (!(raw instanceof Map)) {
                    return MutationResult.complete(raw, false);
                }
                Map<String, Object> varMeta = (Map<String, Object>) raw;
                if (!checkAccess(varMeta, context.getPid())) {
                    return MutationResult.complete(
                            error("Access denied: PID " + context.getPid()), false);
                }
                if (!Boolean.TRUE.equals(varMeta.get("changed"))) {
                    return MutationResult.pending();
                }

                Object value = varMeta.get("value");
                varMeta.put("changed", false);
                varMeta.put("editTime", FileUtil.getCurrentTimeArray());
                updatePoolTime(pool);
                return MutationResult.complete(value, true);
            });
            if (result != WAIT_PENDING) return result;

            // 没变更，休眠再试
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return transactPool(poolName, context, pool -> MutationResult.complete(
                        error("waitFor interrupted"), false));
            }
        }

        return transactPool(poolName, context, pool -> MutationResult.complete(
                error("waitFor timeout (30s) for " + varName), false));
    }

    /**
     * 信号：标记 sync 变量已变更（唤醒等待者）。
     * FCL: swapPool.signal("msg", "pool1")
    */
    @SuppressWarnings("unchecked")
    private static Object signalVariable(String varName, String poolName,
                                         FunctionContext context) {
        if (poolName == null || poolName.trim().isEmpty()) {
            return error("Pool name cannot be empty");
        }
        return transactPool(poolName, context, pool -> {
            Map<String, Object> content = getContent(pool);
            Object raw = content != null ? content.get(varName) : null;
            if (raw == null) {
                return MutationResult.complete(error("Variable not found: " + varName), false);
            }
            if (!(raw instanceof Map)) {
                return MutationResult.complete(error("Variable has no metadata"), false);
            }
            Map<String, Object> varMeta = (Map<String, Object>) raw;
            varMeta.put("changed", true);
            varMeta.put("editTime", FileUtil.getCurrentTimeArray());
            updatePoolTime(pool);
            return MutationResult.complete("Signal sent for: " + varName, true);
        });
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

    private static Long getLongArg(List<Object> args, int index) {
        if (args == null || index >= args.size() || args.get(index) == null) return null;
        Object value = args.get(index);
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Object> tailArgs(List<Object> args, int fromIndex) {
        if (args == null || fromIndex >= args.size()) {
            return Collections.emptyList();
        }
        return args.subList(fromIndex, args.size());
    }
}
