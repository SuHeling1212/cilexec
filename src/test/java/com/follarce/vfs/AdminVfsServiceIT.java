package com.follarce.vfs;

import com.follarce.auth.AuthService;
import com.follarce.application.ProcessService;
import com.follarce.application.ProgramService;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class AdminVfsServiceIT {
    private static final Instant NOW = Instant.parse("2026-07-26T08:00:00Z");

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
    void administratorCrossesOwnerBoundaryOnlyThroughAuditedService() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcTransactionExecutor transactions = new JdbcTransactionExecutor(dataSource);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuthService auth = new AuthService(transactions, clock);
        UserAccount administrator = auth.create("system-admin",
                "admin-password-123".toCharArray(),
                Set.of(Capability.SYSTEM_ADMIN, Capability.VFS_READ));
        UserAccount owner = auth.create("file-owner", "owner-password-123".toCharArray(),
                Set.of(Capability.VFS_READ, Capability.VFS_WRITE));
        UserAccount ordinary = auth.create("ordinary-user", "other-password-123".toCharArray(),
                Set.of(Capability.VFS_READ));

        VfsService ownerVfs = new VfsService(transactions, clock);
        VfsNode root = ownerVfs.createDirectory(owner.userId(), Optional.empty(), "/", Set.of());
        VfsNode file = ownerVfs.createFile(owner.userId(), root.nodeId(), "private.txt",
                "original".getBytes(StandardCharsets.UTF_8), "text/plain", Set.of(), true);

        // SYSTEM_ADMIN can see the row through its RLS policy, but the owner-bound
        // service still requires the explicit audited administrator API.
        assertThrows(SecurityException.class,
                () -> ownerVfs.readFile(administrator.userId(), file.nodeId()));

        AdminVfsService adminVfs = new AdminVfsService(transactions, clock);
        assertArrayEquals("original".getBytes(StandardCharsets.UTF_8),
                adminVfs.readFile(administrator.userId(), owner.userId(), file.nodeId())
                        .content().bytes());
        adminVfs.replaceContent(administrator.userId(), owner.userId(), file.nodeId(),
                "changed".getBytes(StandardCharsets.UTF_8), "text/plain");
        assertArrayEquals("changed".getBytes(StandardCharsets.UTF_8),
                ownerVfs.readFile(owner.userId(), file.nodeId()).content().bytes());
        assertEquals(2, ownerVfs.fileRevisions(owner.userId(), file.nodeId()).size());
        assertTrue(adminVfs.listNodes(administrator.userId(), owner.userId()).stream()
                .anyMatch(node -> node.nodeId().equals(file.nodeId())));
        assertThrows(SecurityException.class,
                () -> adminVfs.readFile(ordinary.userId(), owner.userId(), file.nodeId()));

        boolean audited = transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.audit().findByResource(
                                "vfs.node", file.nodeId().toString(), 20).stream()
                        .anyMatch(event -> event.action().equals("vfs.admin.write")
                                && event.actorId().equals(administrator.userId().toString())));
        assertTrue(audited);
    }

    @Test
    void administratorHasEveryCapabilityAndControlsForeignProcesses() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcTransactionExecutor transactions = new JdbcTransactionExecutor(dataSource);
        Clock clock = Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC);
        AuthService auth = new AuthService(transactions, clock);
        UserAccount administrator = auth.create("process-system-admin",
                "admin-process-password-123".toCharArray(), Set.of(Capability.SYSTEM_ADMIN));
        UserAccount owner = auth.create("process-owner",
                "owner-process-password-123".toCharArray(), Set.of(Capability.PROCESS_CREATE));

        var program = new ProgramService(transactions).create(owner.userId(), "value = 1\n");
        var process = new ProcessService(transactions).create(owner.userId(), program,
                Optional.empty());

        transactions.inUserTransaction(administrator.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    assertEquals(Set.of(Capability.values()),
                            transaction.auth().capabilities(administrator.userId()));
                    assertTrue(transaction.processes().findAll().stream().anyMatch(candidate ->
                            candidate.identity().processUid().equals(process.identity().processUid())));
                    return null;
                });

        ProcessService processes = new ProcessService(transactions);
        assertEquals(com.follarce.domain.process.CilProcess.Status.PAUSED,
                processes.pause(administrator.userId(), process.identity().pid()).status());
        assertEquals(com.follarce.domain.process.CilProcess.Status.READY,
                processes.resume(administrator.userId(), process.identity().pid()).status());
        assertEquals(com.follarce.domain.process.CilProcess.Status.TERMINATED,
                processes.terminate(administrator.userId(), process.identity().pid()).status());
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
