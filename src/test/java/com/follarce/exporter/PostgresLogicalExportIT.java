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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgresLogicalExportIT {
    private static final Instant NOW = Instant.parse("2026-07-26T08:00:00Z");
    private static final String MIGRATOR_PASSWORD = "migration-test-password";
    private static final String EXPORTER_PASSWORD = "exporter-test-password";
    private static final String READONLY_PASSWORD = "readonly-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getProperty("cilexec.test.postgres.image", "postgres:17.10-alpine3.23"));

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE cilexec_owner NOLOGIN INHERIT");
            statement.execute("CREATE ROLE cilexec_migrator LOGIN INHERIT CREATEROLE PASSWORD '"
                    + MIGRATOR_PASSWORD + "'");
            statement.execute("CREATE ROLE cilexec_runtime LOGIN NOINHERIT");
            statement.execute("CREATE ROLE cilexec_effect_worker LOGIN NOINHERIT");
            statement.execute("CREATE ROLE cilexec_readonly LOGIN NOINHERIT PASSWORD '"
                    + READONLY_PASSWORD + "'");
            statement.execute("CREATE ROLE cilexec_exporter LOGIN NOINHERIT PASSWORD '"
                    + EXPORTER_PASSWORD + "'");
            statement.execute("ALTER ROLE cilexec_readonly SET default_transaction_read_only TO on");
            statement.execute("ALTER ROLE cilexec_exporter SET default_transaction_read_only TO on");
            statement.execute("GRANT cilexec_owner TO cilexec_migrator");
            statement.execute("ALTER DATABASE \"" + connection.getCatalog().replace("\"", "\"\"")
                    + "\" OWNER TO cilexec_owner");
            statement.execute("GRANT CONNECT ON DATABASE \""
                    + connection.getCatalog().replace("\"", "\"\"")
                    + "\" TO cilexec_migrator, cilexec_readonly, cilexec_exporter");
            statement.execute("CREATE SCHEMA flyway AUTHORIZATION cilexec_migrator");
            statement.execute("GRANT USAGE ON SCHEMA flyway TO cilexec_exporter");
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_migrator IN SCHEMA flyway "
                    + "GRANT SELECT ON TABLES TO cilexec_exporter");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "cilexec_migrator", MIGRATOR_PASSWORD)
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
        source.setUser("cilexec_exporter");
        source.setPassword(EXPORTER_PASSWORD);
        Path database = temporaryDirectory.resolve("postgres-snapshot.db");

        LogicalExportReport report = new LogicalExportService(source,
                Clock.fixed(NOW, ZoneOffset.UTC)).export(database,
                new BuildInfo("CilExec", "1.0", "integration", 1, 1, 30));

        assertTrue(report.tableCount() > 30);
        assertTrue(report.rowCount() > 1);
        assertEquals("2", scalar(database, "SELECT metadata_value FROM export_metadata "
                + "WHERE metadata_key='database.schema.version'"));
        assertEquals("0", scalar(database, "SELECT metadata_value FROM export_metadata "
                + "WHERE metadata_key='source.statement.timeout'"));
        assertEquals("0", scalar(database, "SELECT metadata_value FROM export_metadata "
                + "WHERE metadata_key='source.idle.transaction.timeout'"));
        assertEquals(0, number(database, "SELECT count(*) FROM export_table WHERE table_name IN "
                + "('auth.user_credential','meta.kernel_instance','scheduler.runner','scheduler.lease')"));
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

        try (Connection exporter = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                "cilexec_exporter", EXPORTER_PASSWORD);
             Statement statement = exporter.createStatement()) {
            assertThrows(java.sql.SQLException.class,
                    () -> statement.executeQuery("SELECT * FROM auth.user_credential"));
            assertThrows(java.sql.SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM audit.event"));
            assertThrows(java.sql.SQLException.class,
                    () -> statement.executeQuery(
                            "SELECT object_store.gc_orphans(1)"));
        }

        try (Connection readonly = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                "cilexec_readonly", READONLY_PASSWORD);
             Statement statement = readonly.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM diagnostic.account_status")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1));
            }
            assertThrows(java.sql.SQLException.class,
                    () -> statement.executeQuery("SELECT * FROM auth.user_account"));
            assertThrows(java.sql.SQLException.class,
                    () -> statement.executeQuery("SELECT * FROM auth.user_credential"));
        }
    }

    @Test
    void securityInvariantRejectsRoleOwnerDefinerAclAndReadonlyPolicyDrift()
            throws Exception {
        assertInvariantRejects(
                "ALTER ROLE cilexec_exporter CREATEROLE",
                "ALTER ROLE cilexec_exporter NOCREATEROLE");
        assertInvariantRejects(
                "ALTER VIEW diagnostic.audit_status OWNER TO cilexec_exporter",
                "ALTER VIEW diagnostic.audit_status OWNER TO cilexec_owner");
        assertInvariantRejects(
                "GRANT EXECUTE ON FUNCTION process.enforce_owner_process_quota() TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION process.enforce_owner_process_quota() FROM PUBLIC");
        assertInvariantRejects(
                "CREATE POLICY injected_readonly_access ON auth.user_account "
                        + "FOR SELECT TO cilexec_readonly USING (true)",
                "DROP POLICY injected_readonly_access ON auth.user_account");
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
                    "INSERT INTO auth.user_credential(user_id,password_hash) VALUES (?,?)")) {
                insert.setObject(1, ownerId);
                insert.setString(2, "pbkdf2-sha256$310000$test-salt$test-verifier");
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

    private static void assertInvariantRejects(String tamper, String repair) throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute(tamper);
            try {
                assertThrows(java.sql.SQLException.class,
                        () -> statement.execute("SELECT meta.assert_security_invariants()"));
            } finally {
                statement.execute(repair);
            }
            statement.execute("SELECT meta.assert_security_invariants()");
        }
    }

    private static String scalar(Path database, String query) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath().normalize().toUri().toASCIIString()
                        + "?mode=ro&immutable=1");
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
