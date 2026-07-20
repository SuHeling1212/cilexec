package com.follarce.kernel.process;

import java.util.LinkedHashMap;
import java.util.Map;

/** A durable process-control message addressed to one process incarnation. */
public record ProcessMessage(
        String messageId,
        long sequence,
        int targetPid,
        String targetGeneration,
        int senderPid,
        String senderGeneration,
        String field,
        Object value,
        long createdAtEpochMs
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("MessageId", messageId);
        map.put("Sequence", sequence);
        map.put("TargetPid", targetPid);
        map.put("TargetGeneration", targetGeneration);
        map.put("SenderPid", senderPid);
        map.put("SenderGeneration", senderGeneration);
        map.put("Field", field);
        map.put("Value", value);
        map.put("CreatedAtEpochMs", createdAtEpochMs);
        return map;
    }

    public static ProcessMessage fromMap(Map<String, Object> map) {
        return new ProcessMessage(
                requiredString(map, "MessageId"),
                number(map.get("Sequence"), -1).longValue(),
                number(map.get("TargetPid"), -1).intValue(),
                requiredString(map, "TargetGeneration"),
                number(map.get("SenderPid"), 0).intValue(),
                map.get("SenderGeneration") instanceof String ? map.get("SenderGeneration").toString() : null,
                requiredString(map, "Field"),
                map.get("Value"),
                number(map.get("CreatedAtEpochMs"), 0).longValue());
    }

    private static Number number(Object value, Number fallback) {
        return value instanceof Number ? (Number) value : fallback;
    }

    private static String requiredString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String) || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing process message field: " + key);
        }
        return value.toString();
    }
}
