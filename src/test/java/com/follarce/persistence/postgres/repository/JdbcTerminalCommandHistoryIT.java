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
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class JdbcTerminalCommandHistoryIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres:18.0-alpine3.22");

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
    }

    @Test
    void survivesRepositoryRestartDeduplicatesAndPrunesPerUser() throws Exception {
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        createUser(firstOwner, "history-first");
        createUser(secondOwner, "history-second");

        try (Connection connection = runtimeConnection()) {
            JdbcTerminalRepository repository = new JdbcTerminalRepository(connection);
            repository.appendCommandHistory(firstOwner, "first", Instant.now(), 3);
            repository.appendCommandHistory(firstOwner, "first", Instant.now(), 3);
            repository.appendCommandHistory(firstOwner, "second", Instant.now(), 3);
            repository.appendCommandHistory(firstOwner, "third", Instant.now(), 3);
            repository.appendCommandHistory(firstOwner, "fourth", Instant.now(), 3);
            repository.appendCommandHistory(secondOwner, "private", Instant.now(), 3);
            connection.commit();
        }

        try (Connection restartedConnection = runtimeConnection()) {
            JdbcTerminalRepository restarted = new JdbcTerminalRepository(restartedConnection);
            assertEquals(List.of("second", "third", "fourth"),
                    restarted.findCommandHistory(firstOwner, 200));
            assertEquals(List.of("private"), restarted.findCommandHistory(secondOwner, 200));
        }
    }

    private static void createUser(UUID userId, String username) throws Exception {
        try (Connection connection = adminConnection(); PreparedStatement statement =
                connection.prepareStatement("INSERT INTO auth.user_account"
                        + "(user_id,username,postgres_role_name,status) VALUES (?,?,?,'ACTIVE')")) {
            statement.setObject(1, userId);
            statement.setString(2, username);
            statement.setString(3, "cilexec_user_" + userId.toString().replace("-", ""));
            statement.executeUpdate();
        }
    }

    private static Connection runtimeConnection() throws Exception {
        Connection connection = adminConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE cilexec_runtime");
        }
        return connection;
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
