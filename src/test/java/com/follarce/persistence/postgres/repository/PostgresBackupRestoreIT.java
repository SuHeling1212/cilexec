package com.follarce.persistence.postgres.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Disaster-recovery contract for the documented pg_dump/pg_restore path. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresBackupRestoreIT {
    private static final String RESTORED_DATABASE = "cilexec_restore";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres:18.0-alpine3.22");

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = sourceConnection(); Statement statement = connection.createStatement()) {
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
    }

    @Test
    void customFormatDumpRestoresSemanticRowsSchemaHistoryAndSecurityInvariants()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        try (Connection connection = sourceConnection(); PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO auth.user_account(user_id,username,postgres_role_name,status) "
                                + "VALUES (?,?,?,'ACTIVE')")) {
            statement.setObject(1, ownerId);
            statement.setString(2, "backup-owner");
            statement.setString(3, "cilexec_user_" + ownerId.toString().replace("-", ""));
            statement.executeUpdate();
        }

        assertExec(POSTGRES.execInContainer("pg_dump", "--username=" + POSTGRES.getUsername(),
                "--dbname=" + POSTGRES.getDatabaseName(), "--format=custom",
                "--file=/tmp/cilexec.backup"));
        assertExec(POSTGRES.execInContainer("createdb", "--username=" + POSTGRES.getUsername(),
                "--owner=cilexec_owner", RESTORED_DATABASE));
        assertExec(POSTGRES.execInContainer("pg_restore",
                "--username=" + POSTGRES.getUsername(), "--dbname=" + RESTORED_DATABASE,
                "--exit-on-error", "/tmp/cilexec.backup"));

        try (Connection connection = restoredConnection()) {
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM auth.user_account WHERE user_id='" + ownerId + "'::uuid"));
            assertEquals(1, count(connection,
                    "SELECT max(version::integer) FROM flyway.flyway_schema_history WHERE success"));
            assertEquals(12, count(connection,
                    "SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname IN "
                            + "('meta','auth','object_store','vfs','program','process','scheduler',"
                            + "'ipc','effect','package','terminal','audit')"));
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT meta.assert_security_invariants()");
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

    private static Connection sourceConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private static Connection restoredConnection() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                        + POSTGRES.getMappedPort(5432) + "/" + RESTORED_DATABASE,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
