package com.follarce.persistence.postgres.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps a supervisor alive while PostgreSQL itself is SIGKILLed, then verifies WAL recovery. */
@Testcontainers
class PostgresWalCrashIT {
    private static final String USERNAME = "test";
    private static final String PASSWORD = "test-password";
    private static final String DATABASE = "test";

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse(System.getProperty(
                    "cilexec.test.postgres.image", "postgres:17.10-alpine3.23")))
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withEnv("POSTGRES_DB", DATABASE)
            .withExposedPorts(5432)
            .withCommand("sh", "-c",
                    "docker-entrypoint.sh postgres & while :; do sleep 60; done")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)));

    @BeforeAll
    static void migrate() throws Exception {
        // The supervisor shell opens the port before PostgreSQL has finished recovery.
        awaitDatabase(Duration.ofSeconds(20));
        try (Connection connection = adminConnection()) {
            com.follarce.persistence.postgres.PostgresTestBootstrap.createServiceRoles(
                    connection, PASSWORD);
        }
        Flyway.configure()
                .dataSource(jdbcUrl(),
                        com.follarce.persistence.postgres.PostgresTestBootstrap.MIGRATOR_ROLE,
                        PASSWORD)
                .locations("classpath:db/migration")
                .defaultSchema("flyway")
                .schemas("flyway")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @Test
    void immediateDatabaseCrashKeepsCommittedRowsAndRollsBackOpenTransaction()
            throws Exception {
        UUID committedUser = UUID.randomUUID();
        UUID uncommittedUser = UUID.randomUUID();
        try (Connection connection = adminConnection()) {
            insertUser(connection, committedUser, "committed-before-crash");
        }

        Connection openTransaction = adminConnection();
        openTransaction.setAutoCommit(false);
        insertUser(openTransaction, uncommittedUser, "must-rollback");

        var killed = POSTGRES.execInContainer("sh", "-c",
                "kill -9 \"$(head -n 1 \"$PGDATA/postmaster.pid\")\"");
        assertEquals(0, killed.getExitCode(), killed.getStderr());
        assertTrue(POSTGRES.isRunning(), "supervisor container must survive database SIGKILL");
        try {
            openTransaction.close();
        } catch (Exception ignored) {
            // The backend disappeared while this transaction was open.
        }

        var restarted = POSTGRES.execInContainer("sh", "-c",
                "docker-entrypoint.sh postgres >/tmp/postgres-restart.log 2>&1 &");
        assertEquals(0, restarted.getExitCode(), restarted.getStderr());
        awaitDatabase(Duration.ofSeconds(20));

        assertEquals(1, userCount(committedUser));
        assertEquals(0, userCount(uncommittedUser));
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT meta.assert_security_invariants()");
        }
    }

    private static void insertUser(Connection connection, UUID userId, String username)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO auth.user_account(user_id,username,postgres_role_name,status) "
                        + "VALUES (?,?,?,'ACTIVE')")) {
            statement.setObject(1, userId);
            statement.setString(2, username);
            statement.setString(3, "cilexec_user_" + userId.toString().replace("-", ""));
            statement.executeUpdate();
        }
    }

    private static int userCount(UUID userId) throws Exception {
        try (Connection connection = adminConnection(); PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT count(*) FROM auth.user_account WHERE user_id=?")) {
            statement.setObject(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void awaitDatabase(Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        Exception last = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = adminConnection()) {
                if (connection.isValid(1)) {
                    return;
                }
            } catch (Exception failure) {
                last = failure;
                Thread.sleep(100);
            }
        }
        var logs = POSTGRES.execInContainer("sh", "-c",
                "tail -n 100 /tmp/postgres-restart.log 2>/dev/null || true");
        throw new AssertionError("PostgreSQL did not recover: " + logs.getStdout(), last);
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/" + DATABASE;
    }
}
