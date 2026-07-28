package com.follarce.application;

import com.follarce.auth.AuthService;
import com.follarce.domain.auth.Capability;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.terminal.TerminalAccessService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class FclSystemFunctionsIT {
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
    void exposesAdministratorAndSystemOperationsToFcl() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        FclSystemFunctionsExternalIT.execute(new JdbcTransactionExecutor(source),
                POSTGRES.getJdbcUrl());
    }

    @Test
    void upgradesLegacyPlainDatabasePasswordOnFirstLogin() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        JdbcTransactionExecutor transactions = new JdbcTransactionExecutor(source);
        String username = "legacy-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "legacy-password-123";
        var account = new AuthService(transactions, Clock.systemUTC()).create(username,
                password.toCharArray(), Set.of(Capability.VFS_READ));
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE \"" + account.postgresRoleName()
                    + "\" PASSWORD '" + password + "'");
        }

        var access = new TerminalAccessService(transactions, POSTGRES.getJdbcUrl(),
                Clock.systemUTC());
        assertTrue(access.login(username, password.toCharArray()).isPresent());
        assertTrue(access.login(username, password.toCharArray()).isPresent());
        assertEquals(2L, transactions.inTransaction(
                com.follarce.domain.port.Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(account.userId())
                        .orElseThrow().credentialVersion()).longValue());
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
