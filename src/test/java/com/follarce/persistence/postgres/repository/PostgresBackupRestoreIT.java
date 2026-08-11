package com.follarce.persistence.postgres.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Disaster-recovery contract across two independent PostgreSQL clusters. */
@Testcontainers
class PostgresBackupRestoreIT {
    private static final String RESTORED_DATABASE = "cilexec_restore";
    private static final String MIGRATOR_PASSWORD = "migration-test-password";
    private static final String APPLICATION_VERIFIER =
            "pbkdf2-sha256$310000$MDEyMzQ1Njc4OWFiY2RlZg$"
                    + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";
    private static final String IMAGE = System.getProperty(
            "cilexec.test.postgres.image", "postgres:17.10-alpine3.23");

    @Container
    static final PostgreSQLContainer<?> SOURCE = new PostgreSQLContainer<>(IMAGE);

    @Container
    static final PostgreSQLContainer<?> TARGET = new PostgreSQLContainer<>(IMAGE);

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void migrateSourceAsProductionRole() throws Exception {
        bootstrapServiceRoles(SOURCE, SOURCE.getDatabaseName());
        Flyway.configure()
                .dataSource(SOURCE.getJdbcUrl(), "cilexec_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .defaultSchema("flyway")
                .schemas("flyway")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @Test
    void customFormatDumpRestoresIntoFreshClusterWithDynamicRoles() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String roleName = "cilexec_user_" + ownerId.toString().replace("-", "");
        try (Connection connection = migratorConnection(SOURCE, SOURCE.getDatabaseName());
             PreparedStatement account = connection.prepareStatement(
                     "INSERT INTO auth.user_account(user_id,username,postgres_role_name,status) "
                             + "VALUES (?,?,?,'ACTIVE')");
             PreparedStatement provision = connection.prepareStatement(
                     "SELECT auth.provision_login_role(?,?)")) {
            account.setObject(1, ownerId);
            account.setString(2, "backup-owner");
            account.setString(3, roleName);
            account.executeUpdate();
            provision.setObject(1, ownerId);
            provision.setString(2, APPLICATION_VERIFIER);
            provision.executeQuery().close();
        }

        assertExec(SOURCE.execInContainer("pg_dump", "--username=" + SOURCE.getUsername(),
                "--dbname=" + SOURCE.getDatabaseName(), "--format=custom",
                "--file=/tmp/cilexec.backup"));
        Path archive = temporaryDirectory.resolve("cilexec.backup");
        SOURCE.copyFileFromContainer("/tmp/cilexec.backup", archive.toString());

        bootstrapServiceRoles(TARGET, RESTORED_DATABASE);
        TARGET.copyFileToContainer(MountableFile.forHostPath(archive), "/tmp/cilexec.backup");
        assertExec(TARGET.execInContainer("pg_restore",
                "--username=" + TARGET.getUsername(), "--dbname=" + RESTORED_DATABASE,
                "--exit-on-error", "/tmp/cilexec.backup"));

        // Cluster roles are not part of pg_dump. Recreate stable NOLOGIN tenant roles from
        // restored account/credential rows through the same audited provisioning boundary.
        try (Connection connection = migratorConnection(TARGET, RESTORED_DATABASE);
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT auth.provision_login_role(account.user_id,credential.password_hash) "
                    + "FROM auth.user_account AS account JOIN auth.user_credential AS credential "
                    + "USING (user_id) WHERE account.status='ACTIVE'");
            statement.execute("SELECT meta.assert_security_invariants()");
        }

        try (Connection connection = adminConnection(TARGET, RESTORED_DATABASE)) {
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM auth.user_account WHERE user_id='" + ownerId + "'::uuid"));
            assertEquals(1, count(connection,
                    "SELECT max(version::integer) FROM flyway.flyway_schema_history WHERE success"));
            assertEquals(13, count(connection,
                    "SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname IN "
                            + "('meta','auth','object_store','vfs','program','process','scheduler',"
                            + "'ipc','effect','package','terminal','audit','diagnostic')"));
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname='" + roleName + "' "
                            + "AND NOT rolcanlogin AND NOT rolinherit"));
            assertEquals(1, count(connection,
                    "SELECT pg_has_role('cilexec_runtime','" + roleName + "','MEMBER')::integer"));
        }
    }

    private static void bootstrapServiceRoles(PostgreSQLContainer<?> container,
                                              String databaseName) throws Exception {
        try (Connection connection = adminConnection(container, container.getDatabaseName());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE cilexec_owner NOLOGIN INHERIT");
            statement.execute("CREATE ROLE cilexec_migrator LOGIN INHERIT CREATEROLE PASSWORD '"
                    + MIGRATOR_PASSWORD + "'");
            statement.execute("CREATE ROLE cilexec_runtime LOGIN NOINHERIT");
            statement.execute("CREATE ROLE cilexec_effect_worker LOGIN NOINHERIT");
            statement.execute("CREATE ROLE cilexec_readonly LOGIN NOINHERIT");
            statement.execute("CREATE ROLE cilexec_exporter LOGIN NOINHERIT");
            statement.execute("ALTER ROLE cilexec_readonly SET default_transaction_read_only TO on");
            statement.execute("ALTER ROLE cilexec_exporter SET default_transaction_read_only TO on");
            statement.execute("GRANT cilexec_owner TO cilexec_migrator");
            if (databaseName.equals(container.getDatabaseName())) {
                statement.execute("ALTER DATABASE \"" + databaseName.replace("\"", "\"\"")
                        + "\" OWNER TO cilexec_owner");
            } else {
                statement.execute("CREATE DATABASE \"" + databaseName.replace("\"", "\"\"")
                        + "\" OWNER cilexec_owner");
            }
            statement.execute("GRANT CONNECT ON DATABASE \""
                    + databaseName.replace("\"", "\"\"") + "\" TO cilexec_migrator,"
                    + "cilexec_runtime,cilexec_effect_worker,cilexec_readonly,cilexec_exporter");
        }
        if (databaseName.equals(container.getDatabaseName())) {
            try (Connection connection = adminConnection(container, databaseName);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA flyway AUTHORIZATION cilexec_migrator");
                statement.execute("GRANT USAGE,CREATE ON SCHEMA flyway TO cilexec_migrator");
                statement.execute("GRANT USAGE ON SCHEMA flyway TO cilexec_runtime,cilexec_exporter");
            }
        }
    }

    private static void assertExec(org.testcontainers.containers.Container.ExecResult result) {
        assertEquals(0, result.getExitCode(), result.getStdout() + result.getStderr());
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static Connection migratorConnection(PostgreSQLContainer<?> container,
                                                  String databaseName) throws Exception {
        return DriverManager.getConnection(jdbcUrl(container, databaseName),
                "cilexec_migrator", MIGRATOR_PASSWORD);
    }

    private static Connection adminConnection(PostgreSQLContainer<?> container,
                                               String databaseName) throws Exception {
        return DriverManager.getConnection(jdbcUrl(container, databaseName),
                container.getUsername(), container.getPassword());
    }

    private static String jdbcUrl(PostgreSQLContainer<?> container, String databaseName) {
        return "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(5432) + "/" + databaseName;
    }
}
