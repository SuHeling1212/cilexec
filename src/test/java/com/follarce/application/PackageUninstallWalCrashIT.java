package com.follarce.application;

import com.follarce.auth.AuthService;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.packageinfo.PackageInstallation;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.fcl.FclContinuation;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.vfs.VfsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps a supervisor alive while PostgreSQL is SIGKILLed with an uncommitted
 * package uninstall open, then verifies that WAL recovery rolled the whole
 * lifecycle transaction back: installations, private data, and the global
 * release payload all survive.
 */
@Testcontainers
class PackageUninstallWalCrashIT {
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
    void uncommittedUninstallRollsBackCompletelyAfterWalCrash() throws Exception {
        JdbcTransactionExecutor transactions = transactions();
        Clock clock = Clock.systemUTC();
        UserAccount owner = new AuthService(transactions, clock).create("crash-owner",
                "owner-password-123".toCharArray(), Set.of(Capability.VFS_READ,
                        Capability.VFS_WRITE, Capability.PROCESS_CREATE,
                        Capability.PACKAGE_IMPORT, Capability.PACKAGE_BIND));
        VfsService vfs = new VfsService(transactions, clock);
        VfsNode root = transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.vfs().findChild(owner.userId(), Optional.empty(), "/")
                        .orElseThrow());
        String source = "func greet(value) { return \"Hello, \" + value }\n"
                + "func run() { return greet(\"package\") }\n";
        com.follarce.package_manager.PackageManifest manifest =
                new com.follarce.package_manager.PackageManifest("cilexec", "editor", "0.0.1",
                        "fcl-0.0.2", List.of(new com.follarce.package_manager.PackageManifest.Module(
                                "main", "main.fcl")), List.of(), List.of(),
                        List.of(new com.follarce.package_manager.PackageManifest.Entrypoint(
                                "run", "main", "run")), List.of(), List.of());
        byte[] database = new com.follarce.package_manager.PackageBuilder().build(manifest,
                path -> source.getBytes(StandardCharsets.UTF_8));
        vfs.createFile(owner.userId(), root.nodeId(), "editor.db", database,
                "application/vnd.sqlite3", Set.of(), false);

        FclContinuation installRuntime = run(transactions, owner.userId(),
                "installed = package.install(\"/editor.db\")\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> installed = (Map<String, Object>) installRuntime.scope().get("installed");
        ObjectHash fileHash = new ObjectHash((String) installed.get("sha256"));
        transactions.<Void>inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    transaction.packages().writeDataEntry(owner.userId(), fileHash,
                            "config.json", "{\"theme\":\"dark\"}".getBytes(StandardCharsets.UTF_8),
                            "application/json", -1);
                    return null;
                });

        // Open an uncommitted uninstall as the owner's mapped role, mirroring the
        // runtime's user-transaction identity, then SIGKILL PostgreSQL.
        Connection openTransaction = adminConnection();
        openTransaction.setAutoCommit(false);
        try (Statement role = openTransaction.createStatement()) {
            role.execute("SET LOCAL ROLE " + owner.postgresRoleName());
        }
        try (PreparedStatement claim = openTransaction.prepareStatement(
                "SELECT set_config('app.cilexec_user_id', ?, true)")) {
            claim.setString(1, owner.userId().toString());
            claim.execute();
        }
        try (PreparedStatement uninstall = openTransaction.prepareStatement(
                "SELECT package.uninstall_package(?, ?, ?)")) {
            uninstall.setBytes(1, com.follarce.persistence.postgres.mapper.JdbcValues.hash(fileHash));
            uninstall.setBoolean(2, false);
            uninstall.setObject(3, UUID.randomUUID());
            uninstall.executeQuery().close();
        }

        var killed = POSTGRES.execInContainer("sh", "-c",
                "kill -9 \"$(head -n 1 \"$PGDATA/postmaster.pid\")\"");
        assertEquals(0, killed.getExitCode(), killed.getStderr());
        assertTrue(POSTGRES.isRunning(), "supervisor container must survive database SIGKILL");
        try {
            openTransaction.close();
        } catch (Exception ignored) {
            // The backend disappeared while the uninstall was uncommitted.
        }

        var restarted = POSTGRES.execInContainer("sh", "-c",
                "docker-entrypoint.sh postgres >/tmp/postgres-restart.log 2>&1 &");
        assertEquals(0, restarted.getExitCode(), restarted.getStderr());
        awaitDatabase(Duration.ofSeconds(20));

        // Every phase of the uninstall must have rolled back together.
        List<PackageInstallation> installations = transactions.inUserTransaction(
                owner.userId(), Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .findInstallations(owner.userId()));
        assertEquals(1, installations.size());
        assertEquals("{\"theme\":\"dark\"}", new String(transactions.inUserTransaction(
                owner.userId(), Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .readDataEntry(owner.userId(), fileHash, "config.json")),
                StandardCharsets.UTF_8));
        assertTrue(transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages()
                        .findReleaseByDatabaseFileHash(fileHash)).isPresent());
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT meta.assert_security_invariants()");
        }
    }

    private static FclContinuation run(JdbcTransactionExecutor transactions, UUID ownerId,
                                       String source) {
        CilProcess process = new ProcessService(transactions).create(ownerId,
                new ProgramService(transactions).create(ownerId, source), Optional.empty());
        var program = transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> transaction.programs().findById(process.continuation().programId())
                        .orElseThrow());
        FclContinuation continuation = new FclContinuation();
        var compiled = new com.follarce.fcl.FclCompiler().compile(source);
        int steps = 0;
        while (!continuation.halted() && steps++ < 100) {
            FclContinuation current = continuation;
            com.follarce.fcl.FclStepResult result = transactions.inUserTransaction(ownerId,
                    Isolation.READ_COMMITTED, transaction -> new com.follarce.fcl.FclRuntime(
                            FclRuntimeFunctions.create(transaction, process, program, current,
                                    Clock.systemUTC().instant()))
                            .executeOne(compiled, current));
            assertTrue(result.status() != com.follarce.fcl.FclStepResult.Status.FAILED,
                    () -> String.valueOf(result.value()));
        }
        assertTrue(continuation.halted());
        return continuation;
    }

    private static JdbcTransactionExecutor transactions() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(jdbcUrl());
        source.setUser(USERNAME);
        source.setPassword(PASSWORD);
        return new JdbcTransactionExecutor(source);
    }

    private static void awaitDatabase(Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        Exception last = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection ignored = adminConnection()) {
                return;
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
