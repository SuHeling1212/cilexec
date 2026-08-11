package com.follarce.vfs;

import com.follarce.auth.AuthService;
import com.follarce.application.ProcessService;
import com.follarce.application.ProgramService;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.port.Isolation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Optional runner for databases supplied by CI or local Docker when Testcontainers is unavailable. */
@EnabledIfSystemProperty(named = "cilexec.external.jdbc", matches = ".+")
class AdminVfsExternalIT {
    @Test
    void exercisesRealAdministratorAndRlsPaths() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(System.getProperty("cilexec.external.jdbc"));
        source.setUser(System.getProperty("cilexec.external.user", "postgres"));
        source.setPassword(System.getProperty("cilexec.external.password"));
        JdbcTransactionExecutor transactions = new JdbcTransactionExecutor(source);
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthService auth = new AuthService(transactions, clock);
        UserAccount administrator = auth.create("external-admin-" + suffix,
                "admin-password-123".toCharArray(),
                Set.of(Capability.SYSTEM_ADMIN, Capability.VFS_READ));
        UserAccount owner = auth.create("external-owner-" + suffix,
                "owner-password-123".toCharArray(),
                Set.of(Capability.VFS_READ, Capability.VFS_WRITE,
                        Capability.PROCESS_CREATE));

        VfsService ordinary = new VfsService(transactions, clock);
        VfsNode root = transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.vfs().findChild(owner.userId(), Optional.empty(), "/")
                        .orElseThrow());
        VfsNode file = ordinary.createFile(owner.userId(), root.nodeId(), "private.txt",
                "owner-data".getBytes(StandardCharsets.UTF_8), "text/plain", Set.of(), true);
        assertThrows(SecurityException.class,
                () -> ordinary.readFile(administrator.userId(), file.nodeId()));
        assertThrows(RuntimeException.class, () -> transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.vfs()
                        .readFileByAdministrator(owner.userId(), owner.userId(), file.nodeId(),
                                UUID.randomUUID(), clock.instant())));

        transactions.inUserTransaction(administrator.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    assertEquals(Set.of(Capability.values()),
                            transaction.auth().capabilities(administrator.userId()));
                    return null;
                });

        AdminVfsService elevated = new AdminVfsService(transactions, clock);
        assertArrayEquals("owner-data".getBytes(StandardCharsets.UTF_8), elevated.readFile(
                administrator.userId(), owner.userId(), file.nodeId()).content().bytes());
        elevated.replaceContent(administrator.userId(), owner.userId(), file.nodeId(),
                "admin-data".getBytes(StandardCharsets.UTF_8), "text/plain");
        assertArrayEquals("admin-data".getBytes(StandardCharsets.UTF_8),
                ordinary.readFile(owner.userId(), file.nodeId()).content().bytes());
        assertEquals(2, ordinary.fileRevisions(owner.userId(), file.nodeId()).size());

        CilProcess process = new ProcessService(transactions).create(owner.userId(),
                new ProgramService(transactions).create(owner.userId(), "value = 1\n"),
                Optional.empty());
        FclContinuationCodec codec = new FclContinuationCodec();
        Instant at = Instant.now();
        transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED, transaction -> {
            assertEquals(true, transaction.ipc().createSwapPool(owner.userId(),
                    process.identity().processUid(), "shared", at));
            Continuation.PersistedValue value = new Continuation.PersistedValue("string",
                    codec.valueToJson("hello"));
            assertEquals(true, transaction.ipc().addSwapValue(owner.userId(), "shared", "message",
                    value, "SYNC", Optional.empty(), at));
            assertEquals("hello", codec.valueFromJson(transaction.ipc().consumeSwapValue(
                    owner.userId(), "shared", "message", at).orElseThrow().canonicalPayload()));
            assertEquals(true, transaction.ipc().signalSwapValue(owner.userId(), "shared",
                    "message", at));
            assertEquals(true, transaction.ipc().consumeSwapSignal(owner.userId(), "shared",
                    "message"));
            var swapLock = transaction.ipc().acquireSwapLock(owner.userId(), "shared", "message",
                    process.identity().processUid(), process.executionEpoch(), at.plusSeconds(5),
                    at).orElseThrow();
            assertEquals(true, transaction.ipc().releaseSwapLock(owner.userId(), "shared",
                    "message", process.identity().processUid(), process.executionEpoch(),
                    swapLock.fencingToken()));
            var fileLock = transaction.vfs().acquireLock(file.nodeId(), owner.userId(),
                    process.identity().processUid(), process.executionEpoch(), at.plusSeconds(5),
                    at).orElseThrow();
            assertFalse(transaction.vfs().releaseLock(file.nodeId(), owner.userId(),
                    process.identity().processUid(), process.executionEpoch() + 1,
                    fileLock.fencingToken()));
            assertEquals(true, transaction.vfs().releaseLock(file.nodeId(), owner.userId(),
                    process.identity().processUid(), process.executionEpoch(),
                    fileLock.fencingToken()));
            return null;
        });
    }
}
