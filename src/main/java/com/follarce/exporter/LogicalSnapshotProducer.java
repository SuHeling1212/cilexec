package com.follarce.exporter;

import com.follarce.app.BuildInfo;

import java.time.Instant;

@FunctionalInterface
interface LogicalSnapshotProducer {
    void writeSnapshot(SqliteLogicalExportWriter writer, BuildInfo buildInfo, Instant exportedAt);
}
