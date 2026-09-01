package com.follarce.terminal;

import com.follarce.auth.AccountCapabilityProfiles;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.persistence.postgres.PostgresTestBootstrap;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class TerminalAccessServiceIT {
    private static final char[] ADMIN_PASSWORD = "admin-pass-1".toCharArray();
    private static final char[] REPLACEMENT_PASSWORD = "repl-pass-1".toCharArray();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getProperty("cilexec.test.postgres.image", "postgres:17.10-alpine3.23"));

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = adminConnection()) {
            PostgresTestBootstrap.createServiceRoles(connection);
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), PostgresTestBootstrap.MIGRATOR_ROLE,
                        PostgresTestBootstrap.DEFAULT_PASSWORD)
                .locations("classpath:db/migration")
                .defaultSchema("flyway")
                .schemas("flyway")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @Test
    void currentAdministratorCanCreateAnotherAdministrator() {
        String suffix = suffix();
        String adminUsername = "local" + suffix;
        JdbcTransactionExecutor transactions = transactions();
        TerminalAccessService access = access(transactions, adminUsername);

        access.bootstrap(adminUsername, ADMIN_PASSWORD.clone());
        UserAccount replacement = access.register("repl" + suffix,
                REPLACEMENT_PASSWORD.clone(), ADMIN_PASSWORD.clone());

        assertTrue(capabilities(transactions, replacement.userId())
                .contains(Capability.SYSTEM_ADMIN));
    }

    @Test
    void revokedAdministratorCannotCreateAnotherAdministrator() {
        String suffix = suffix();
        String adminUsername = "local" + suffix;
        JdbcTransactionExecutor transactions = transactions();
        TerminalAccessService access = access(transactions, adminUsername);

        UserAccount administrator = access.bootstrap(adminUsername, ADMIN_PASSWORD.clone());
        transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            transaction.auth().replaceCapabilities(administrator.userId(),
                    AccountCapabilityProfiles.USER);
            return null;
        });

        assertFalse(capabilities(transactions, administrator.userId())
                .contains(Capability.SYSTEM_ADMIN));
        assertThrows(IllegalArgumentException.class,
                () -> access.register("repl" + suffix, REPLACEMENT_PASSWORD.clone(),
                        ADMIN_PASSWORD.clone()));
        assertTrue(transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser("repl" + suffix)).isEmpty());
    }

    @Test
    void expiredSystemAdminCapabilityCannotCreateAdministrator() throws Exception {
        String suffix = suffix();
        String adminUsername = "local" + suffix;
        JdbcTransactionExecutor transactions = transactions();
        TerminalAccessService access = access(transactions, adminUsername);

        UserAccount administrator = access.bootstrap(adminUsername, ADMIN_PASSWORD.clone());
        transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            transaction.auth().replaceCapabilities(administrator.userId(),
                    Set.of(Capability.SYSTEM_ADMIN));
            return null;
        });
        expireCapability(administrator.userId());

        assertFalse(capabilities(transactions, administrator.userId())
                .contains(Capability.SYSTEM_ADMIN));
        assertThrows(IllegalArgumentException.class,
                () -> access.register("repl" + suffix, REPLACEMENT_PASSWORD.clone(),
                        ADMIN_PASSWORD.clone()));
        assertTrue(transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser("repl" + suffix)).isEmpty());
    }

    private static TerminalAccessService access(JdbcTransactionExecutor transactions,
                                                String administratorUsername) {
        return new TerminalAccessService(transactions, POSTGRES.getJdbcUrl(),
                Clock.systemUTC(), administratorUsername);
    }

    private static Set<Capability> capabilities(JdbcTransactionExecutor transactions,
                                                UUID userId) {
        return transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().capabilities(userId));
    }

    private static void expireCapability(UUID userId) throws Exception {
        try (Connection connection = runtimeConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE auth.user_capability SET expires_at=clock_timestamp()-interval '1 minute' "
                             + "WHERE user_id=?")) {
            update.setObject(1, userId);
            assertEquals(1, update.executeUpdate());
            connection.commit();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static JdbcTransactionExecutor transactions() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser("cilexec_runtime");
        source.setPassword(PostgresTestBootstrap.DEFAULT_PASSWORD);
        return new JdbcTransactionExecutor(source);
    }

    private static Connection runtimeConnection() throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                "cilexec_runtime", PostgresTestBootstrap.DEFAULT_PASSWORD);
        connection.setAutoCommit(false);
        return connection;
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
