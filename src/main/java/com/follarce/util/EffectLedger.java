package com.follarce.util;

import com.follarce.Constants;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable tombstones for effects whose original resource was deleted. */
public final class EffectLedger {
    public record Lookup(boolean found, Object result) {}

    private EffectLedger() {}

    public static Lookup lookup(String effectId) {
        if (effectId == null || effectId.isBlank()) return new Lookup(false, null);
        String path = path(effectId);
        if (!FileUtil.exists(path)) return new Lookup(false, null);
        Map<String, Object> record = JsonUtil.parseToMapStrict(FileUtil.read(path));
        if (!effectId.equals(record.get("EffectId"))) {
            throw new IllegalStateException("Effect ledger hash collision");
        }
        return new Lookup(true, JsonUtil.deepCopy(record.get("Result")));
    }

    public static void record(String effectId, Object result) {
        if (effectId == null || effectId.isBlank()) return;
        String path = path(effectId);
        java.util.concurrent.locks.ReentrantLock lock = JsonUtil.lockFile(path);
        try {
            if (FileUtil.exists(path)) {
                Map<String, Object> existing = JsonUtil.parseToMapStrict(FileUtil.read(path));
                if (!effectId.equals(existing.get("EffectId"))) {
                    throw new IllegalStateException("Effect ledger hash collision");
                }
                return;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("EffectId", effectId);
            record.put("Result", JsonUtil.deepCopy(result));
            record.put("RecordedAtEpochMs", System.currentTimeMillis());
            JsonUtil.writeFile(path, JsonUtil.toMetaJson(record));
        } finally {
            lock.unlock();
        }
    }

    private static String path(String effectId) {
        return Constants.SYSTEM_APPLIED_EFFECT_PATH + hash(effectId) + ".json";
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
