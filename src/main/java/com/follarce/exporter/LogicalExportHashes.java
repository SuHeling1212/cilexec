package com.follarce.exporter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class LogicalExportHashes {
    private LogicalExportHashes() {
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    static String sha256(String value) {
        return HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    static void frame(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }

    static String manifest(Map<String, String> metadata, List<TableSummary> tables) {
        MessageDigest digest = sha256();
        metadata.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            frame(digest, "metadata");
            frame(digest, entry.getKey());
            frame(digest, entry.getValue());
        });
        tables.stream().sorted(java.util.Comparator.comparing(TableSummary::tableName))
                .forEach(table -> {
                    frame(digest, "table");
                    frame(digest, table.tableName());
                    frame(digest, Long.toString(table.rowCount()));
                    frame(digest, table.contentSha256());
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    record TableSummary(String tableName, long rowCount, String contentSha256) {
    }
}
