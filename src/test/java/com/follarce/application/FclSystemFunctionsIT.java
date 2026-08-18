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

@Testcontainers
class FclSystemFunctionsIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getProperty("cilexec.test.postgres.image", "postgres:17.10-alpine3.23"));

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = adminConnection()) {
            com.follarce.persistence.postgres.PostgresTestBootstrap.createServiceRoles(connection);
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(),
                        com.follarce.persistence.postgres.PostgresTestBootstrap.MIGRATOR_ROLE,
                        com.follarce.persistence.postgres.PostgresTestBootstrap.DEFAULT_PASSWORD)
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
        FclSystemFunctionsExternalIT.execute(new JdbcTransactionExecutor(source));
    }

    @Test
    void exchangesSwapPoolDataAcrossRealFclProcesses() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        FclSystemFunctionsExternalIT.executeSwapPoolAcrossProcesses(
                new JdbcTransactionExecutor(source));
    }

    @Test
    void downloadsFilesThroughTheDurableEffectPipeline() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        FclSystemFunctionsExternalIT.executeNetworkDownloads(new JdbcTransactionExecutor(source));
    }

    @Test
    void createsAndRotatesSixCharacterApplicationCredential() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        JdbcTransactionExecutor transactions = new JdbcTransactionExecutor(source);
        String username = "legacy-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "orig01";
        String replacement = "repl02";
        var account = new AuthService(transactions, Clock.systemUTC()).create(username,
                password.toCharArray(), Set.of(Capability.VFS_READ));

        var access = new TerminalAccessService(transactions, POSTGRES.getJdbcUrl(),
                Clock.systemUTC());
        assertTrue(access.login(username, password.toCharArray()).isPresent());
        new AuthService(transactions, Clock.systemUTC()).rotateCredential(
                account.userId(), replacement.toCharArray());
        assertTrue(access.login(username, password.toCharArray()).isEmpty());
        assertTrue(access.login(username, replacement.toCharArray()).isPresent());
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
