package com.follarce.kernel.process;

import com.follarce.kernel.Constants;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable fork identity independent of the lifetime of the child snapshot. */
public final class ForkLedger {
    public record Entry(String effectId, int parentPid, String parentGeneration,
                        int childPid, String childGeneration, String state) {}

    private ForkLedger() {}

    public static Entry read(String effectId) {
        if (effectId == null) return null;
        String path = path(effectId);
        if (!FileUtil.exists(path)) return null;
        Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(path));
        if (!effectId.equals(data.get("EffectId"))) throw new IllegalStateException("Fork ledger collision");
        return new Entry(effectId,
                ((Number) data.get("ParentPid")).intValue(),
                data.get("ParentGeneration").toString(),
                ((Number) data.get("ChildPid")).intValue(),
                data.get("ChildGeneration").toString(),
                data.get("State").toString());
    }

    public static void reserve(String effectId, int parentPid, String parentGeneration,
                               int childPid, String childGeneration) {
        write(effectId, parentPid, parentGeneration, childPid, childGeneration, "RESERVED");
    }

    public static void markCreated(Entry entry) {
        write(entry.effectId(), entry.parentPid(), entry.parentGeneration(),
                entry.childPid(), entry.childGeneration(), "CREATED");
    }

    private static void write(String effectId, int parentPid, String parentGeneration,
                              int childPid, String childGeneration, String state) {
        String path = path(effectId);
        java.util.concurrent.locks.ReentrantLock lock = JsonUtil.lockFile(path);
        try {
            Entry existing = read(effectId);
            if (existing != null) {
                if (existing.parentPid() != parentPid
                        || !existing.parentGeneration().equals(parentGeneration)
                        || existing.childPid() != childPid
                        || !existing.childGeneration().equals(childGeneration)) {
                    throw new IllegalStateException("Fork effect identity changed: " + effectId);
                }
                if (existing.state().equals(state)) return;
                if ("CREATED".equals(existing.state()) && "RESERVED".equals(state)) return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("EffectId", effectId);
            data.put("ParentPid", parentPid);
            data.put("ParentGeneration", parentGeneration);
            data.put("ChildPid", childPid);
            data.put("ChildGeneration", childGeneration);
            data.put("State", state);
            JsonUtil.writeFile(path, JsonUtil.toMetaJson(data));
        } finally {
            lock.unlock();
        }
    }

    private static String path(String effectId) {
        try {
            String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(effectId.getBytes(StandardCharsets.UTF_8)));
            return Constants.SYSTEM_FORK_EFFECT_PATH + hash + ".json";
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
