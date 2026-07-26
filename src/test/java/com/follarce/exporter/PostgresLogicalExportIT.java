package com.follarce.exporter;

import com.follarce.app.BuildInfo;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PostgresLogicalExportIT {
    private static final Instant NOW = Instant.parse("2026-07-26T08:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres:18.0-alpine3.22");

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE cilexec_owner NOLOGIN");
            statement.execute("CREATE ROLE cilexec_migrator NOLOGIN");
            statement.execute("CREATE ROLE cilexec_runtime NOLOGIN");
            statement.execute("CREATE ROLE cilexec_effect_worker NOLOGIN");
            statement.execute("CREATE ROLE cilexec_readonly NOLOGIN");
            statement.execute("ALTER DATABASE \"" + connection.getCatalog().replace("\"", "\"\"")
                    + "\" OWNER TO cilexec_owner");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("flyway")
                .schemas("flyway")
                .cleanDisabled(true)
                .load()
                .migrate();
        seedSemanticAndRuntimeRows();
    }

    @Test
    void exportsCommittedSemanticsButNotRuntimeConnectionOrLeaseState() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Path database = temporaryDirectory.resolve("postgres-snapshot.db");

        LogicalExportReport report = new LogicalExportService(source,
                Clock.fixed(NOW, ZoneOffset.UTC)).export(database,
                new BuildInfo("CilExec", "1.0", "integration", 1, 1, 25));

        assertTrue(report.tableCount() > 30);
        assertTrue(report.rowCount() > 1);
        assertEquals("25", scalar(database, "SELECT metadata_value FROM export_metadata "
                + "WHERE metadata_key='database.schema.version'"));
        assertEquals(0, number(database, "SELECT count(*) FROM export_table WHERE table_name IN "
                + "('meta.kernel_instance','scheduler.runner','scheduler.lease')"));
        assertEquals(1, number(database, "SELECT count(*) FROM export_table "
                + "WHERE table_name='scheduler.queue'"));

        String boot = scalar(database, "SELECT row_json FROM export_row "
                + "WHERE table_name='meta.boot'");
        assertFalse(boot.contains("control_backend_pid"));
        assertFalse(boot.contains("control_backend_started_at"));
        assertFalse(boot.contains("control_proof_lock_key"));
        assertTrue(boot.contains("\"runtime_version\":\"integration\""));

        String object = scalar(database, "SELECT row_json FROM export_row "
                + "WHERE table_name='object_store.object'");
        assertTrue(object.contains("\"content\":\"\\\\x7061796c6f6164\""));
        assertEquals("ok", scalar(database, "PRAGMA integrity_check"));
    }

    private static void seedSemanticAndRuntimeRows() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID kernelId = UUID.randomUUID();
        UUID bootId = UUID.randomUUID();
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
        try (Connection connection = adminConnection()) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO auth.user_account(user_id,username,postgres_role_name,status) "
                            + "VALUES (?,?,?,'ACTIVE')")) {
                insert.setObject(1, ownerId);
                insert.setString(2, "export-test");
                insert.setString(3, "cilexec_user_" + ownerId.toString().replace("-", ""));
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO meta.instance(instance_id,instance_name,advisory_lock_key,status) "
                            + "VALUES (?,'export-test',4242,'ACTIVE')")) {
                insert.setObject(1, instanceId);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO meta.kernel_instance(kernel_instance_id,instance_id,runtime_version,"
                            + "fcl_runtime_format_version,hostname,container_identity,status) "
                            + "VALUES (?,?,'integration',1,'host-secret','container-secret','ACTIVE')")) {
                insert.setObject(1, kernelId);
                insert.setObject(2, instanceId);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO meta.boot(boot_id,instance_id,kernel_instance_id,status,"
                            + "runtime_version,schema_version,fcl_runtime_format_version,"
                            + "control_backend_pid,control_backend_started_at,control_proof_lock_key) "
                            + "VALUES (?,?,?,'ACTIVE','integration','20',1,12345,?,987654321)")) {
                insert.setObject(1, bootId);
                insert.setObject(2, instanceId);
                insert.setObject(3, kernelId);
                insert.setTimestamp(4, java.sql.Timestamp.from(NOW));
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO object_store.object(object_hash,byte_size,media_type,content,"
                            + "created_by) VALUES (?,?,'application/octet-stream',?,?)")) {
                insert.setBytes(1, hash);
                insert.setLong(2, content.length);
                insert.setBytes(3, content);
                insert.setObject(4, ownerId);
                insert.executeUpdate();
            }
        }
    }

    private static long number(Path database, String query) throws Exception {
        return Long.parseLong(scalar(database, query));
    }

    private static String scalar(Path database, String query) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:file:" + database.toAbsolutePath() + "?mode=ro&immutable=1");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
