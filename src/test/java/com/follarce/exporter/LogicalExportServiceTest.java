package com.follarce.exporter;

import com.follarce.app.BuildInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicalExportServiceTest {
    private static final Instant EXPORTED_AT = Instant.parse("2026-07-26T08:00:00Z");
    private static final BuildInfo BUILD = new BuildInfo("CilExec", "1.0", "abc123", 1, 1, 20);

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsVerifiedReadOnlyCanonicalExportWithStableManifest() throws Exception {
        Path first = temporaryDirectory.resolve("first.db");
        Path second = temporaryDirectory.resolve("second.db");
        LogicalExportService service = service(LogicalExportServiceTest::writeSnapshot);

        LogicalExportReport firstReport = service.export(first, BUILD);
        LogicalExportReport secondReport = service.export(second, BUILD);

        assertEquals(first.toAbsolutePath(), firstReport.database());
        assertEquals(2, firstReport.tableCount());
        assertEquals(3, firstReport.rowCount());
        assertEquals(firstReport.manifestSha256(), secondReport.manifestSha256());
        assertEquals("ok", scalar(first, "PRAGMA integrity_check"));
        assertEquals("2", scalar(first, "SELECT metadata_value FROM export_metadata "
                + "WHERE metadata_key='export.table_count'"));
        assertEquals("3", scalar(first, "SELECT metadata_value FROM export_metadata "
                + "WHERE metadata_key='export.row_count'"));
        assertEquals("{\"a\":{\"a\":1,\"z\":2},\"b\":2}", scalar(first,
                "SELECT row_json FROM export_row WHERE table_name='auth.user_account' "
                        + "AND row_number=1"));
        assertFalse(Files.exists(Path.of(first + "-journal")));
        assertFalse(Files.exists(Path.of(first + "-wal")));
        assertFalse(Files.exists(Path.of(first + "-shm")));
        assertReadOnly(first);
        assertThrows(SQLException.class, () -> {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + first);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM export_row");
            }
        });
    }

    @Test
    void refusesOverwriteBeforeReadingSourceAndPreservesExistingBytes() throws Exception {
        Path target = temporaryDirectory.resolve("existing.db");
        byte[] original = "not an export".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);
        AtomicInteger snapshots = new AtomicInteger();
        LogicalExportService service = service((writer, build, at) -> snapshots.incrementAndGet());

        assertThrows(LogicalExportException.class, () -> service.export(target, BUILD));

        assertEquals(0, snapshots.get());
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(target)));
    }

    @Test
    void removesTemporaryDatabaseAndNeverPublishesOnSnapshotFailure() throws Exception {
        Path target = temporaryDirectory.resolve("failed.db");
        LogicalExportService service = service((writer, build, at) -> {
            writer.begin(metadata());
            writer.beginTable("auth.user_account");
            writer.appendRow("{\"id\":1}");
            throw new LogicalExportException("source failed");
        });

        assertThrows(LogicalExportException.class, () -> service.export(target, BUILD));

        assertFalse(Files.exists(target));
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.toList().isEmpty());
        }
    }

    @Test
    void rejectsImplicitFormatAndDetectsContentTampering() throws Exception {
        LogicalExportService service = service(LogicalExportServiceTest::writeSnapshot);
        assertThrows(IllegalArgumentException.class,
                () -> service.export(temporaryDirectory.resolve("snapshot.sqlite"), BUILD));

        Path database = temporaryDirectory.resolve("tampered.db");
        try (SqliteLogicalExportWriter writer = new SqliteLogicalExportWriter(database)) {
            writeSnapshot(writer, BUILD, EXPORTED_AT);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER guard_export_row_update");
            statement.executeUpdate("UPDATE export_row SET row_json='{\"changed\":true}' "
                    + "WHERE table_name='auth.user_account' AND row_number=1");
        }

        assertThrows(LogicalExportException.class,
                () -> new SqliteLogicalExportVerifier().verify(database));
    }

    private LogicalExportService service(LogicalSnapshotProducer snapshot) {
        return new LogicalExportService(snapshot, new SqliteLogicalExportVerifier(),
                Clock.fixed(EXPORTED_AT, ZoneOffset.UTC));
    }

    private static void writeSnapshot(SqliteLogicalExportWriter writer, BuildInfo build,
                                      Instant exportedAt) {
        writer.begin(metadata());
        writer.beginTable("auth.user_account");
        writer.appendRow("{\"b\":2,\"a\":{\"z\":2,\"a\":1}}");
        writer.appendRow("{\"b\":3,\"a\":null}");
        writer.endTable();
        writer.beginTable("process.process");
        writer.appendRow("{\"status\":\"READY\",\"pid\":1}");
        writer.endTable();
        writer.finish();
    }

    private static Map<String, String> metadata() {
        return Map.of(
                "export.format", "cilexec-logical-export",
                "export.format.version", "1",
                "export.created_at", EXPORTED_AT.toString(),
                "application.name", "CilExec",
                "application.version", "1.0",
                "build.revision", "abc123",
                "fcl.runtime.format", "1",
                "database.schema.version", "20");
    }

    private static String scalar(Path database, String query) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:file:" + database.toAbsolutePath() + "?mode=ro&immutable=1");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static void assertReadOnly(Path database) throws Exception {
        FileStore store = Files.getFileStore(database);
        if (store.supportsFileAttributeView(PosixFileAttributeView.class)) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(database);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ), permissions);
        }
    }
}
