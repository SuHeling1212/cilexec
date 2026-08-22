package com.follarce.application;

import com.follarce.auth.AuthService;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.program.Program;
import com.follarce.domain.terminal.TerminalSession;
import com.follarce.domain.timer.ProcessTimer;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V003 explicit resource control: {@code program.remove}, closed-terminal-session removal,
 * {@code timer.purge}, and {@code audit.purge} remove durable rows only on demand, and a
 * referenced Program is reported instead of removed.
 */
@Testcontainers
class ResourceControlExternalIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getProperty("cilexec.test.postgres.image", "postgres:17.10-alpine3.23"));

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"),
            ZoneOffset.UTC);

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

    private static Connection adminConnection() throws Exception {
        return java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static JdbcTransactionExecutor transactions() {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return new JdbcTransactionExecutor(dataSource);
    }

    @Test
    void removesOnlyExplicitlyTargetedDurableHistory() {
        JdbcTransactionExecutor transactions = transactions();
        Instant now = CLOCK.instant();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthService auth = new AuthService(transactions, CLOCK);
        UserAccount owner = auth.create("rc-owner-" + suffix,
                "owner-password-123".toCharArray(), Set.of(Capability.PROCESS_CREATE));
        UserAccount administrator = auth.create("rc-admin-" + suffix,
                "admin-password-123".toCharArray(), Set.of(Capability.SYSTEM_ADMIN));

        // Audit purge removes only events created before the cutoff.
        UUID ownerUuid = owner.userId();
        transactions.inUserTransaction(ownerUuid, Isolation.READ_COMMITTED, transaction -> {
            transaction.audit().append(new AuditEvent(UUID.randomUUID(),
                    AuditEvent.ActorType.USER, ownerUuid.toString(), "rc.old", "test", "old",
                    AuditEvent.Result.SUCCEEDED, Map.of(), now.minusSeconds(86_400)));
            transaction.audit().append(new AuditEvent(UUID.randomUUID(),
                    AuditEvent.ActorType.USER, ownerUuid.toString(), "rc.new", "test", "new",
                    AuditEvent.Result.SUCCEEDED, Map.of(), now));
            return null;
        });
        int purged = transactions.inUserTransaction(administrator.userId(),
                Isolation.READ_COMMITTED, transaction ->
                        transaction.audit().purgeBeforeByAdministrator(administrator.userId(),
                                now, null));
        assertEquals(1, purged);

        // A finished timer is purgeable; a scheduled one is never touched.
        Program program = new ProgramService(transactions).create(owner.userId(), "util.exit()");
        CilProcess process = new ProcessService(transactions).create(owner.userId(), program,
                Optional.empty());
        UUID processUid = process.identity().processUid();
        UUID scheduledId = UUID.randomUUID();
        UUID firedId = UUID.randomUUID();
        UUID runnerId = UUID.randomUUID();
        transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED, transaction -> {
            transaction.timers().save(new ProcessTimer(scheduledId, processUid,
                    now.plusSeconds(60), ProcessTimer.Status.SCHEDULED, now, Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            ProcessTimer fired = new ProcessTimer(firedId, processUid, now.minusSeconds(60),
                    ProcessTimer.Status.FIRED, now.minusSeconds(120), Optional.of(runnerId),
                    Optional.of(now.minusSeconds(100)), Optional.of(now.minusSeconds(90)),
                    Optional.empty());
            transaction.timers().save(fired);
            return null;
        });
        Integer purgedTimers = transactions.inUserTransaction(administrator.userId(),
                Isolation.READ_COMMITTED, transaction ->
                        transaction.timers().purgeFinishedBefore(now, null));
        assertEquals(1, purgedTimers);
        Boolean scheduledSurvives = transactions.inUserTransaction(administrator.userId(),
                Isolation.READ_COMMITTED, transaction ->
                        transaction.timers().findById(scheduledId).isPresent());
        assertTrue(scheduledSurvives);

        // A closed terminal session is removable; an open one is not.
        UUID sessionId = UUID.randomUUID();
        transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED, transaction -> {
            transaction.terminal().saveSession(new TerminalSession(sessionId, owner.userId(),
                    TerminalSession.Status.CLOSED, 1, now.minusSeconds(300),
                    now.minusSeconds(60), Optional.of(now.minusSeconds(60))));
            return null;
        });
        Boolean removedSession = transactions.inUserTransaction(administrator.userId(),
                Isolation.READ_COMMITTED, transaction ->
                        transaction.terminal().removeClosedSession(sessionId));
        assertTrue(removedSession);
        Boolean sessionGone = transactions.inUserTransaction(administrator.userId(),
                Isolation.READ_COMMITTED, transaction ->
                        transaction.terminal().findSession(sessionId).isEmpty());
        assertTrue(sessionGone);
        UUID openSessionId = UUID.randomUUID();
        transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED, transaction -> {
            transaction.terminal().saveSession(new TerminalSession(openSessionId, owner.userId(),
                    TerminalSession.Status.OPEN, 1, now, now, Optional.empty()));
            return null;
        });
        Boolean openNotRemoved = transactions.inUserTransaction(administrator.userId(),
                Isolation.READ_COMMITTED, transaction ->
                        transaction.terminal().removeClosedSession(openSessionId));
        assertFalse(openNotRemoved);

        // An unreferenced program is removable; a program with a live reference is reported.
        Program unreachable = new ProgramService(transactions).create(owner.userId(),
                "// unreachable\nutil.exit()");
        Map<String, Object> report = transactions.inUserTransaction(administrator.userId(),
                Isolation.READ_COMMITTED, transaction ->
                        transaction.programs().removeByAdministrator(administrator.userId(),
                                unreachable.programId(), UUID.randomUUID(), now));
        assertEquals(Boolean.TRUE, report.get("removed"));
        Program referenced = new ProgramService(transactions).create(owner.userId(),
                "// referenced\nutil.exit()");
        new ProcessService(transactions).create(owner.userId(), referenced, Optional.empty());
        Map<String, Object> blocked = transactions.inUserTransaction(administrator.userId(),
                Isolation.READ_COMMITTED, transaction ->
                        transaction.programs().removeByAdministrator(administrator.userId(),
                                referenced.programId(), UUID.randomUUID(), now));
        assertEquals(Boolean.FALSE, blocked.get("removed"));
        assertNotNull(blocked.get("processes"));
    }
}
