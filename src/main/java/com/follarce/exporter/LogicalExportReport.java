package com.follarce.exporter;

import java.nio.file.Path;
import java.util.Objects;

/** Verified identity of one immutable logical-export delivery. */
public record LogicalExportReport(
        Path database,
        int tableCount,
        long rowCount,
        String manifestSha256
) {
    public LogicalExportReport {
        database = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
        if (tableCount < 0 || rowCount < 0) {
            throw new IllegalArgumentException("export counts must not be negative");
        }
        if (manifestSha256 == null || !manifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("manifestSha256 must be lowercase SHA-256 hex");
        }
    }
}
