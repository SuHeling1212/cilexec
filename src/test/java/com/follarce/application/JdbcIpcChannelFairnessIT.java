package com.follarce.application;

import com.follarce.auth.AuthService;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.ipc.IpcChannel;
import com.follarce.domain.ipc.IpcSubscription;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.process.CilProcess;
import com.follarce.ipc.IpcService;
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
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Competing-consumer channel delivery fairness against a real PostgreSQL instance:
 * a channel with several active subscribers must rotate the receiver per message
 * instead of permanently pinning the chronologically earliest subscription.
 */
@Testcontainers
class JdbcIpcChannelFairnessIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getProperty("cilexec.test.postgres.image", "postgres:17.10-alpine3.23"));

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            PostgresTestBootstrap.createServiceRoles(connection);
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(),
                        PostgresTestBootstrap.MIGRATOR_ROLE,
                        PostgresTestBootstrap.DEFAULT_PASSWORD)
                .locations("classpath:db/migration")
                .defaultSchema("flyway")
                .schemas("flyway")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @Test
    void channelSendsRoundRobinAcrossActiveSubscribers() {
        JdbcTransactionExecutor transactions = transactions();
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthService auth = new AuthService(transactions, clock);
        UserAccount owner = auth.create("ipc-fair-" + suffix, "owner-password-123".toCharArray(),
                Set.of(Capability.PROCESS_CREATE, Capability.PROCESS_CONTROL_OWN));
        ProcessService processes = new ProcessService(transactions);
        CilProcess first = processes.create(owner.userId(),
                new com.follarce.application.ProgramService(transactions).create(owner.userId(),
                        "value = 1\n"), Optional.empty());
        CilProcess second = processes.create(owner.userId(),
                new com.follarce.application.ProgramService(transactions).create(owner.userId(),
                        "value = 2\n"), Optional.empty());
        IpcService ipc = new IpcService(transactions, clock);

        IpcChannel channel = ipc.createChannel(owner.userId(), "work-" + suffix);
        IpcSubscription firstSubscription = ipc.subscribeChannel(owner.userId(),
                first.identity().processUid(), channel.channelId());
        IpcSubscription secondSubscription = ipc.subscribeChannel(owner.userId(),
                second.identity().processUid(), channel.channelId());

        UUID firstUid = first.identity().processUid();
        UUID secondUid = second.identity().processUid();
        for (int i = 0; i < 4; i++) {
            ipc.sendChannel(owner.userId(), Optional.empty(),
                    channel.channelId(),
                    IpcService.Payload.json("text/json", "{\"n\":" + i + "}"),
                    Optional.empty());
        }

        List<com.follarce.domain.ipc.IpcDelivery> firstPending = transactions.inUserTransaction(
                owner.userId(), Isolation.READ_COMMITTED, transaction ->
                        transaction.ipc().findPending(firstUid, 10));
        List<com.follarce.domain.ipc.IpcDelivery> secondPending = transactions.inUserTransaction(
                owner.userId(), Isolation.READ_COMMITTED, transaction ->
                        transaction.ipc().findPending(secondUid, 10));
        assertEquals(4, firstPending.size() + secondPending.size(),
                "every channel message must be delivered to exactly one consumer");
        assertTrue(!firstPending.isEmpty(),
                "the earliest subscriber must not starve the channel; first="
                        + firstPending.size() + " second=" + secondPending.size());
        assertTrue(!secondPending.isEmpty(),
                "the later subscriber must receive a share of channel messages; first="
                        + firstPending.size() + " second=" + secondPending.size());
    }

    private static JdbcTransactionExecutor transactions() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return new JdbcTransactionExecutor(source);
    }
}
