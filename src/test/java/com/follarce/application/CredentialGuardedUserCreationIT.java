package com.follarce.application;

import com.follarce.auth.AuthService;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.persistence.postgres.PostgresTestBootstrap;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V003 regression: {@code user.create(username, password, [adminUsername, adminPassword])}
 * creates an administrator only when the supplied credentials belong to a current
 * effective SYSTEM_ADMIN holder; without the third argument any user may self-register.
 */
@Testcontainers
class CredentialGuardedUserCreationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getProperty("cilexec.test.postgres.image", "postgres:17.10-alpine3.23"));

    private static JdbcTransactionExecutor transactions;
    private static AuthService auth;
    private static String suffix;

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
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
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        transactions = new JdbcTransactionExecutor(source);
        auth = new AuthService(transactions, Clock.systemUTC());
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void selfRegistrationAndCredentialGuardedAdministratorCreation() {
        UserAccount owner = auth.create("cred-owner-" + suffix,
                "owner-password-123".toCharArray(),
                Set.of(Capability.PROCESS_CREATE, Capability.VFS_READ, Capability.VFS_WRITE));
        UserAccount local = auth.create("local", "local-password-123".toCharArray(),
                TerminalAccessService.ADMIN_CAPABILITIES);

        // Any logged-in user may self-register a normal account.
        String selfSource = "created = user.create(\"cred-self-%s\", \"self-password-1\")"
                .formatted(suffix);
        FclContinuation selfRuntime = run(transactions, owner, selfSource);
        @SuppressWarnings("unchecked")
        Map<String, Object> selfCreated =
                (Map<String, Object>) selfRuntime.scope().get("created");
        assertEquals("ACTIVE", selfCreated.get("status"));
        assertTrue(transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser("cred-self-" + suffix)).isPresent());

        // A valid administrator credential pair creates an administrator.
        String adminSource = """
                created = user.create("cred-admin-%s", "admin-password-1", ["%s", "%s"])
                """.formatted(suffix, "local", "local-password-123");
        FclContinuation adminRuntime = run(transactions, owner, adminSource);
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) adminRuntime.scope().get("created");
        assertEquals("ACTIVE", created.get("status"));
        UUID createdUserId = UUID.fromString((String) created.get("userId"));
        assertTrue(transactions.inUserTransaction(createdUserId, Isolation.READ_COMMITTED,
                transaction -> transaction.auth().capabilities(createdUserId))
                .contains(Capability.SYSTEM_ADMIN));

        // A non-administrator's own password must not mint an administrator.
        assertFails(transactions, owner,
                "user.create(\"cred-denied-a-%s\", \"denied-password-1\", [\"%s\", \"%s\"])"
                        .formatted(suffix, owner.username(), "owner-password-123"));
        assertFalse(exists("cred-denied-a-" + suffix));

        // Revoking SYSTEM_ADMIN must make the still-valid password insufficient.
        transactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            transaction.auth().replaceCapabilities(local.userId(),
                    TerminalAccessService.USER_CAPABILITIES);
            return null;
        });
        assertFails(transactions, owner,
                "user.create(\"cred-denied-b-%s\", \"denied-password-1\", [\"%s\", \"%s\"])"
                        .formatted(suffix, "local", "local-password-123"));
        assertFalse(exists("cred-denied-b-" + suffix));
    }

    @Test
    void expiredSystemAdminCannotCreateAdministrator() throws Exception {
        UserAccount expiredAdmin = auth.create("cred-expire-" + suffix,
                "expire-password-123".toCharArray(), TerminalAccessService.ADMIN_CAPABILITIES);
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE auth.user_capability "
                        + "SET expires_at = clock_timestamp() - interval '1 minute' "
                        + "WHERE user_id = '" + expiredAdmin.userId() + "'");
            }
        }
        UserAccount caller = auth.create("cred-caller-" + suffix,
                "caller-password-123".toCharArray(),
                Set.of(Capability.PROCESS_CREATE, Capability.VFS_READ));
        assertFalse(transactions.inUserTransaction(expiredAdmin.userId(),
                Isolation.READ_COMMITTED,
                transaction -> transaction.auth().capabilities(expiredAdmin.userId()))
                .contains(Capability.SYSTEM_ADMIN));
        assertFails(transactions, caller,
                "user.create(\"cred-denied-c-%s\", \"denied-password-1\", [\"%s\", \"%s\"])"
                        .formatted(suffix, expiredAdmin.username(), "expire-password-123"));
        assertFalse(exists("cred-denied-c-" + suffix));
    }

    private static boolean exists(String username) {
        return transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(username)).isPresent();
    }

    private static void assertFails(JdbcTransactionExecutor executor, UserAccount owner,
                                    String source) {
        com.follarce.domain.process.CilProcess failedProcess = process(executor, owner, source);
        FclContinuation continuation = new FclContinuation();
        var compiled = new FclCompiler().compile(source);
        FclStepResult last = null;
        int steps = 0;
        while (!continuation.halted() && steps++ < 100) {
            last = step(executor, failedProcess, program(executor, failedProcess), compiled,
                    continuation);
        }
        assertTrue(continuation.halted(), source);
        FclStepResult terminal = last;
        assertFalse(terminal == null || terminal.status() != FclStepResult.Status.FAILED,
                () -> String.valueOf(terminal == null ? "no steps" : terminal.value()));
        assertTrue(continuation.failed(), source);
    }

    private static FclContinuation run(JdbcTransactionExecutor executor, UserAccount owner,
                                       String source) {
        com.follarce.domain.process.CilProcess process = process(executor, owner, source);
        FclContinuation continuation = new FclContinuation();
        var compiled = new FclCompiler().compile(source);
        int steps = 0;
        while (!continuation.halted() && steps++ < 100) {
            FclStepResult result = step(executor, process, program(executor, process), compiled,
                    continuation);
            assertFalse(result.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(result.value()));
        }
        assertTrue(continuation.halted());
        return continuation;
    }

    private static com.follarce.domain.process.CilProcess process(
            JdbcTransactionExecutor executor, UserAccount owner, String source) {
        return new ProcessService(executor).create(owner.userId(),
                new ProgramService(executor).create(owner.userId(),
                        source), java.util.Optional.empty());
    }

    private static com.follarce.domain.program.Program program(
            JdbcTransactionExecutor executor, com.follarce.domain.process.CilProcess process) {
        return executor.inUserTransaction(process.ownerId(), Isolation.READ_COMMITTED,
                transaction -> transaction.programs().findById(process.continuation().programId())
                        .orElseThrow());
    }

    private static FclStepResult step(JdbcTransactionExecutor executor,
                                      com.follarce.domain.process.CilProcess process,
                                      com.follarce.domain.program.Program program,
                                      com.follarce.fcl.FclProgram compiled,
                                      FclContinuation continuation) {
        return executor.inUserTransaction(process.ownerId(), Isolation.READ_COMMITTED,
                transaction -> new FclRuntime(
                        FclRuntimeFunctions.create(transaction, process,
                                program, continuation, Clock.systemUTC().instant()))
                        .executeOne(compiled, continuation));
    }
}
