package com.follarce.persistence.postgres.repository;

import com.follarce.persistence.postgres.mapper.JsonCodec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class JdbcProductionHardeningIT {
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
    void effectWorkerCanInspectRunnerLivenessButCannotMutateSchedulerState() throws Exception {
        try (Connection connection = effectConnection()) {
            JdbcEffectRepository effects = new JdbcEffectRepository(connection, new JsonCodec());
            assertTrue(effects.claimStalled(UUID.randomUUID(), Instant.now(), 300_000, 1)
                    .isEmpty());
            connection.commit();
        }
        try (Connection connection = effectConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () ->
                    statement.executeUpdate("UPDATE scheduler.runner SET status='FENCED'"));
            connection.rollback();
        }
    }

    @Test
    void loginBackoffSurvivesConnectionsAndSuccessfulClear() throws Exception {
        String principal = "login-" + UUID.randomUUID();
        Instant firstFailure = Instant.parse("2026-08-09T10:00:00.123Z");

        try (Connection connection = runtimeConnection()) {
            new JdbcAuthRepository(connection).recordLoginFailure(principal, firstFailure, 30_000);
            connection.commit();
        }
        try (Connection connection = runtimeConnection()) {
            JdbcAuthRepository repository = new JdbcAuthRepository(connection);
            assertEquals(Optional.of(firstFailure.plusMillis(250)),
                    repository.loginBlockedUntil(principal));
            Instant secondFailure = firstFailure.plusMillis(100);
            repository.recordLoginFailure(principal, secondFailure, 30_000);
            connection.commit();
        }
        try (Connection connection = runtimeConnection()) {
            JdbcAuthRepository repository = new JdbcAuthRepository(connection);
            assertEquals(Optional.of(firstFailure.plusMillis(600)),
                    repository.loginBlockedUntil(principal));
            repository.clearLoginFailures(principal);
            connection.commit();
        }
        try (Connection connection = runtimeConnection()) {
            assertEquals(Optional.empty(),
                    new JdbcAuthRepository(connection).loginBlockedUntil(principal));
        }
    }

    @Test
    void loginBackoffDoesNotMoveBackwardWhenOlderFailureCommitsLater() throws Exception {
        String principal = "out-of-order-login-" + UUID.randomUUID();
        Instant newerFailure = Instant.parse("2026-08-09T10:05:00Z");
        try (Connection connection = runtimeConnection()) {
            new JdbcAuthRepository(connection).recordLoginFailure(
                    principal, newerFailure, 30_000);
            connection.commit();
        }
        try (Connection connection = runtimeConnection()) {
            new JdbcAuthRepository(connection).recordLoginFailure(
                    principal, newerFailure.minusSeconds(5), 30_000);
            connection.commit();
        }
        try (Connection connection = runtimeConnection()) {
            assertEquals(Optional.of(newerFailure.plusMillis(500)),
                    new JdbcAuthRepository(connection).loginBlockedUntil(principal));
        }
    }

    @Test
    void garbageCollectionDeletesOnlyOldUnreachableObjects() throws Exception {
        UUID owner = UUID.randomUUID();
        byte[] unreachable = "old-unreachable".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] reachable = "old-reachable".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] recent = "recent-unreachable".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] unreachableHash = sha256(unreachable);
        byte[] reachableHash = sha256(reachable);
        byte[] recentHash = sha256(recent);
        Instant old = Instant.now().minus(Duration.ofHours(2));

        try (Connection connection = adminConnection()) {
            insertUser(connection, owner);
            insertObject(connection, owner, unreachable, old);
            insertObject(connection, owner, reachable, old);
            insertObject(connection, owner, recent, Instant.now());
            UUID root = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO vfs.node(node_id,owner_id,parent_node_id,node_name,node_type) "
                            + "VALUES (?,?,NULL,'/','DIRECTORY')")) {
                statement.setObject(1, root);
                statement.setObject(2, owner);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO vfs.node(node_id,owner_id,parent_node_id,node_name,node_type,"
                            + "current_object_hash) VALUES (?,?,?,'kept','FILE',?)")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, owner);
                statement.setObject(3, root);
                statement.setBytes(4, reachableHash);
                statement.executeUpdate();
            }
        }

        try (Connection connection = runtimeConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT object_store.gc_orphans(10)")) {
            rows.next();
            assertEquals(1, rows.getInt(1));
            connection.commit();
        }
        try (Connection connection = adminConnection()) {
            assertEquals(0, objectCount(connection, unreachableHash));
            assertEquals(1, objectCount(connection, reachableHash));
            assertEquals(1, objectCount(connection, recentHash));
        }
    }

    @Test
    void garbageCollectionWaitsForConcurrentRootsBeforeTakingReachabilitySnapshot() throws Exception {
        UUID owner = UUID.randomUUID();
        byte[] content = "concurrently-rooted".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] hash = sha256(content);
        UUID root = UUID.randomUUID();
        try (Connection connection = adminConnection()) {
            insertUser(connection, owner);
            insertObject(connection, owner, content, Instant.now().minus(Duration.ofHours(2)));
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO vfs.node(node_id,owner_id,parent_node_id,node_name,node_type) "
                            + "VALUES (?,?,NULL,'/','DIRECTORY')")) {
                statement.setObject(1, root);
                statement.setObject(2, owner);
                statement.executeUpdate();
            }
        }

        try (Connection writer = adminConnection();
             Connection collector = runtimeConnection();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement statement = writer.prepareStatement(
                    "INSERT INTO vfs.node(node_id,owner_id,parent_node_id,node_name,node_type,"
                            + "current_object_hash) VALUES (?,?,?,'kept-during-gc','FILE',?)")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, owner);
                statement.setObject(3, root);
                statement.setBytes(4, hash);
                statement.executeUpdate();
            }

            Future<Integer> collection = executor.submit(() -> {
                try (Statement statement = collector.createStatement();
                     ResultSet rows = statement.executeQuery(
                             "SELECT object_store.gc_orphans(10)")) {
                    rows.next();
                    int deleted = rows.getInt(1);
                    collector.commit();
                    return deleted;
                }
            });
            Thread.sleep(200);
            assertFalse(collection.isDone());
            writer.commit();
            collection.get(10, TimeUnit.SECONDS);
        }

        try (Connection connection = adminConnection()) {
            assertEquals(1, objectCount(connection, hash));
        }
    }

    @Test
    void garbageCollectorsSerializeBeforeDeleting() throws Exception {
        try (Connection first = runtimeConnection();
             Connection second = runtimeConnection();
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             Statement statement = first.createStatement();
             ResultSet firstRows = statement.executeQuery("SELECT object_store.gc_orphans(1)")) {
            assertTrue(firstRows.next());
            Future<Integer> waiting = executor.submit(() -> {
                try (Statement secondStatement = second.createStatement();
                     ResultSet rows = secondStatement.executeQuery(
                             "SELECT object_store.gc_orphans(1)")) {
                    rows.next();
                    int result = rows.getInt(1);
                    second.commit();
                    return result;
                }
            });
            Thread.sleep(200);
            assertFalse(waiting.isDone());
            first.commit();
            waiting.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void garbageCollectionDeletesOnlyUnreferencedManifestLeavesWithinLimit() throws Exception {
        UUID owner = UUID.randomUUID();
        Instant old = Instant.now().minus(Duration.ofHours(2));
        byte[] base = "manifest-base".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] firstTail = "manifest-tail-one".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] secondTail = "manifest-tail-two".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] firstDescriptor = "manifest-one".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] secondDescriptor = "manifest-two".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] baseHash = sha256(base);
        byte[] firstTailHash = sha256(firstTail);
        byte[] secondTailHash = sha256(secondTail);
        byte[] firstManifestHash = sha256(firstDescriptor);
        byte[] secondManifestHash = sha256(secondDescriptor);

        try (Connection connection = adminConnection()) {
            insertUser(connection, owner);
            insertObject(connection, owner, base, old);
            insertObject(connection, owner, firstTail, old);
            insertObject(connection, owner, secondTail, old);
            insertObject(connection, owner, firstDescriptor, old);
            insertObject(connection, owner, secondDescriptor, old);
            insertManifest(connection, firstManifestHash, null, baseHash, firstTailHash,
                    base.length + firstTail.length, firstTail.length);
            insertManifest(connection, secondManifestHash, firstManifestHash, null,
                    secondTailHash, base.length + firstTail.length + secondTail.length,
                    secondTail.length);
        }

        try (Connection connection = runtimeConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT object_store.gc_orphans(1)")) {
            rows.next();
            assertEquals(1, rows.getInt(1));
            connection.commit();
        }
        try (Connection connection = adminConnection()) {
            assertEquals(1, manifestCount(connection, firstManifestHash));
            assertEquals(0, manifestCount(connection, secondManifestHash));
            assertEquals(1, objectCount(connection, secondManifestHash));
        }
    }

    @Test
    void administratorGarbageCollectionBindsTheMappedDatabaseIdentity() throws Exception {
        UUID administrator = UUID.randomUUID();
        UUID ordinaryUser = UUID.randomUUID();
        try (Connection connection = adminConnection()) {
            provisionUserRole(connection, administrator, true);
            provisionUserRole(connection, ordinaryUser, false);
        }

        try (Connection connection = mappedUserConnection(administrator);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT object_store.admin_gc_orphans(?,1)")) {
            statement.setObject(1, administrator);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
            }
        }
        try (Connection connection = mappedUserConnection(ordinaryUser);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT object_store.admin_gc_orphans(?,1)")) {
            statement.setObject(1, ordinaryUser);
            SQLException denied = assertThrows(SQLException.class, statement::executeQuery);
            assertEquals("42501", denied.getSQLState());
        }
        try (Connection connection = mappedUserConnection(administrator);
             Statement statement = connection.createStatement()) {
            SQLException denied = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT object_store.gc_orphans(1)"));
            assertEquals("42501", denied.getSQLState());
        }
    }

    @Test
    void ipcPurgeReclaimsOnlyTheOwnersAgedTerminalMessages() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        UUID oldMessage = UUID.randomUUID();
        UUID recentMessage = UUID.randomUUID();
        UUID otherMessage = UUID.randomUUID();
        Instant now = Instant.now();
        try (Connection connection = adminConnection()) {
            provisionUserRole(connection, owner, false);
            provisionUserRole(connection, otherOwner, false);
            insertMessage(connection, oldMessage, owner, now.minus(Duration.ofHours(2)));
            insertMessage(connection, recentMessage, owner, now.minus(Duration.ofMinutes(5)));
            insertMessage(connection, otherMessage, otherOwner, now.minus(Duration.ofHours(2)));
        }

        try (Connection connection = mappedUserConnection(owner)) {
            assertEquals(1, new JdbcIpcRepository(connection).purgeMessages(
                    owner, now.minus(Duration.ofHours(1)), now, 10));
            connection.commit();
        }
        try (Connection connection = adminConnection()) {
            assertEquals(0, messageCount(connection, oldMessage));
            assertEquals(1, messageCount(connection, recentMessage));
            assertEquals(1, messageCount(connection, otherMessage));
        }
    }

    @Test
    void objectStorageQuotaRejectsOversizedOwnerAllocation() throws Exception {
        UUID owner = UUID.randomUUID();
        try (Connection connection = adminConnection()) {
            insertUser(connection, owner);
            byte[] content = {1};
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO object_store.object(object_hash,byte_size,media_type,content,"
                            + "created_by) VALUES (?,?,?,?,?)")) {
                statement.setBytes(1, sha256(content));
                statement.setLong(2, 4_294_967_297L);
                statement.setString(3, "application/octet-stream");
                statement.setBytes(4, content);
                statement.setObject(5, owner);
                SQLException exceeded = assertThrows(SQLException.class, statement::executeUpdate);
                assertEquals("54000", exceeded.getSQLState());
            }
        }
    }

    private static void insertUser(Connection connection, UUID userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO auth.user_account(user_id,username,postgres_role_name,status) "
                        + "VALUES (?,?,?,'ACTIVE')")) {
            statement.setObject(1, userId);
            statement.setString(2, "user-" + userId.toString().substring(0, 8));
            statement.setString(3, "cilexec_user_" + userId.toString().replace("-", ""));
            statement.executeUpdate();
        }
    }

    private static void insertObject(Connection connection, UUID owner, byte[] content,
                                     Instant createdAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO object_store.object(object_hash,byte_size,media_type,content,"
                        + "created_by,created_at) VALUES (?,?,?,?,?,?)")) {
            statement.setBytes(1, sha256(content));
            statement.setLong(2, content.length);
            statement.setString(3, "application/octet-stream");
            statement.setBytes(4, content);
            statement.setObject(5, owner);
            statement.setTimestamp(6, java.sql.Timestamp.from(createdAt));
            statement.executeUpdate();
        }
    }

    private static void insertManifest(Connection connection, byte[] manifestHash,
                                       byte[] previousManifestHash, byte[] baseObjectHash,
                                       byte[] tailObjectHash, long totalSize, int tailSize)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO object_store.chunk_manifest(manifest_hash,previous_manifest_hash,"
                        + "base_object_hash,tail_object_hash,total_size,tail_size) "
                        + "VALUES (?,?,?,?,?,?)")) {
            statement.setBytes(1, manifestHash);
            statement.setBytes(2, previousManifestHash);
            statement.setBytes(3, baseObjectHash);
            statement.setBytes(4, tailObjectHash);
            statement.setLong(5, totalSize);
            statement.setInt(6, tailSize);
            statement.executeUpdate();
        }
    }

    private static void insertMessage(Connection connection, UUID messageId, UUID ownerId,
                                      Instant createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ipc.message(message_id,owner_id,message_kind,topic_name,"
                        + "payload_type,payload_json,created_at) "
                        + "VALUES (?,?,'TOPIC','purge-test','json','{}'::jsonb,?)")) {
            statement.setObject(1, messageId);
            statement.setObject(2, ownerId);
            statement.setTimestamp(3, java.sql.Timestamp.from(createdAt));
            statement.executeUpdate();
        }
    }

    private static void provisionUserRole(Connection connection, UUID userId,
                                          boolean administrator) throws SQLException {
        insertUser(connection, userId);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT auth.provision_principal(?,?)")) {
            statement.setObject(1, userId);
            statement.setString(2, com.follarce.auth.PasswordPolicy.hash(
                    "production-hardening-test-password".toCharArray()));
            statement.executeQuery();
        }
        if (administrator) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO auth.user_capability(user_id,owner_id,capability_id,granted_by) "
                            + "SELECT ?,?,capability_id,? FROM auth.capability "
                            + "WHERE capability_key='system_admin'")) {
                statement.setObject(1, userId);
                statement.setObject(2, userId);
                statement.setObject(3, userId);
                statement.executeUpdate();
            }
        }
    }

    private static int objectCount(Connection connection, byte[] objectHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM object_store.object WHERE object_hash=?")) {
            statement.setBytes(1, objectHash);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static int manifestCount(Connection connection, byte[] objectHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM object_store.chunk_manifest WHERE manifest_hash=?")) {
            statement.setBytes(1, objectHash);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static int messageCount(Connection connection, UUID messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM ipc.message WHERE message_id=?")) {
            statement.setObject(1, messageId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static byte[] sha256(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }

    private static Connection runtimeConnection() throws Exception {
        Connection connection = adminConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE cilexec_runtime");
        }
        return connection;
    }

    private static Connection effectConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                "cilexec_effect_worker",
                com.follarce.persistence.postgres.PostgresTestBootstrap.DEFAULT_PASSWORD);
        connection.setAutoCommit(false);
        return connection;
    }

    private static Connection mappedUserConnection(UUID userId) throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                "cilexec_runtime",
                com.follarce.persistence.postgres.PostgresTestBootstrap.DEFAULT_PASSWORD);
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.cilexec_user_id', ?, true)")) {
            statement.setString(1, userId.toString());
            statement.executeQuery();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE cilexec_user_" + userId.toString().replace("-", ""));
        }
        return connection;
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
