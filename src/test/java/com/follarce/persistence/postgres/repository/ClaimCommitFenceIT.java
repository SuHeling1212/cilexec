package com.follarce.persistence.postgres.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the final statement-commit fence against a real PostgreSQL lock table. */
@Testcontainers
class ClaimCommitFenceIT {
    private static final long CONTROL_KEY = 8_201_407_713L;
    private static final long PROOF_KEY = -7_114_553_029L;

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
    void acceptsOnlyLiveLeaseBackedByBothLocksOnTheRecordedControlBackend()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID kernelId = UUID.randomUUID();
        UUID bootId = UUID.randomUUID();
        UUID programId = UUID.randomUUID();
        UUID processUid = UUID.randomUUID();
        UUID runnerId = UUID.randomUUID();

        try (Connection control = adminConnection()) {
            lock(control, CONTROL_KEY);
            lock(control, PROOF_KEY);
            Backend backend = backend(control);
            seed(ownerId, instanceId, kernelId, bootId, programId, processUid,
                    runnerId, backend);

            try (Connection runtime = adminConnection(); Statement role = runtime.createStatement()) {
                role.execute("SET ROLE cilexec_runtime");
                assertTrue(authorizes(runtime, ownerId, bootId, processUid, runnerId));

                assertTrue(advisory(control, "pg_advisory_unlock", PROOF_KEY));
                assertFalse(authorizes(runtime, ownerId, bootId, processUid, runnerId));

                assertTrue(advisory(control, "pg_try_advisory_lock", PROOF_KEY));
                expireLease(processUid);
                assertFalse(authorizes(runtime, ownerId, bootId, processUid, runnerId));
            }
        }
    }

    private static void seed(UUID ownerId, UUID instanceId, UUID kernelId, UUID bootId,
                             UUID programId, UUID processUid, UUID runnerId,
                             Backend backend) throws Exception {
        byte[] source = "value = 1".getBytes(StandardCharsets.UTF_8);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(source);
        try (Connection connection = adminConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO auth.user_account(user_id,username,postgres_role_name,status) "
                            + "VALUES (?,?,?,'ACTIVE')")) {
                statement.setObject(1, ownerId);
                statement.setString(2, "claim-fence-test");
                statement.setString(3, "cilexec_user_" + ownerId.toString().replace("-", ""));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO meta.instance(instance_id,instance_name,advisory_lock_key,status) "
                            + "VALUES (?,'claim-fence',?,'ACTIVE')")) {
                statement.setObject(1, instanceId);
                statement.setLong(2, CONTROL_KEY);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO meta.kernel_instance(kernel_instance_id,instance_id,runtime_version,"
                            + "fcl_runtime_format_version,hostname,status) "
                            + "VALUES (?,?,'test',1,'test','ACTIVE')")) {
                statement.setObject(1, kernelId);
                statement.setObject(2, instanceId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO meta.boot(boot_id,instance_id,kernel_instance_id,status,"
                            + "runtime_version,schema_version,fcl_runtime_format_version,"
                            + "recovery_completed_at,ready_at,control_backend_pid,"
                            + "control_backend_started_at,control_proof_lock_key) "
                            + "VALUES (?,?,?,'ACTIVE','test','20',1,clock_timestamp(),"
                            + "clock_timestamp(),?,?,?)")) {
                statement.setObject(1, bootId);
                statement.setObject(2, instanceId);
                statement.setObject(3, kernelId);
                statement.setInt(4, backend.pid());
                statement.setTimestamp(5, java.sql.Timestamp.from(backend.startedAt()));
                statement.setLong(6, PROOF_KEY);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO object_store.object(object_hash,byte_size,media_type,content,created_by) "
                            + "VALUES (?,?,'text/x-fcl',?,?)")) {
                statement.setBytes(1, hash);
                statement.setLong(2, source.length);
                statement.setBytes(3, source);
                statement.setObject(4, ownerId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO program.program(program_id,owner_id,program_hash,language_version,"
                            + "runtime_format_version,source_object_hash,statement_count) "
                            + "VALUES (?,?,?,'fcl-1',1,?,1)")) {
                statement.setObject(1, programId);
                statement.setObject(2, ownerId);
                statement.setBytes(3, hash);
                statement.setBytes(4, hash);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO process.process(process_uid,pid,owner_id,program_id,status,"
                            + "program_counter,state_version,execution_epoch,runtime_format_version,"
                            + "language_version,continuation_json,last_boot_id) "
                            + "VALUES (?,1,?,?,'RUNNING',0,1,1,1,'fcl-1','{}'::jsonb,?)")) {
                statement.setObject(1, processUid);
                statement.setObject(2, ownerId);
                statement.setObject(3, programId);
                statement.setObject(4, bootId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO scheduler.runner(runner_id,boot_id,runner_kind,status) "
                            + "VALUES (?,?,'SCHEDULER','ACTIVE')")) {
                statement.setObject(1, runnerId);
                statement.setObject(2, bootId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO scheduler.lease(process_uid,owner_id,runner_id,boot_id,"
                            + "execution_epoch,claimed_at,heartbeat_at,expires_at) "
                            + "VALUES (?,?,?,?,1,clock_timestamp(),clock_timestamp(),"
                            + "clock_timestamp()+interval '2 minutes')")) {
                statement.setObject(1, processUid);
                statement.setObject(2, ownerId);
                statement.setObject(3, runnerId);
                statement.setObject(4, bootId);
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    private static boolean authorizes(Connection connection, UUID ownerId, UUID bootId,
                                      UUID processUid, UUID runnerId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT scheduler.claim_authorizes_commit(?,?,?,?,1)")) {
            statement.setObject(1, processUid);
            statement.setObject(2, ownerId);
            statement.setObject(3, runnerId);
            statement.setObject(4, bootId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static void expireLease(UUID processUid) throws Exception {
        try (Connection connection = adminConnection(); PreparedStatement statement =
                connection.prepareStatement("UPDATE scheduler.lease SET claimed_at=clock_timestamp()"
                        + "-interval '3 minutes',heartbeat_at=clock_timestamp()-interval '2 minutes',"
                        + "expires_at=clock_timestamp()-interval '1 minute' WHERE process_uid=?")) {
            statement.setObject(1, processUid);
            statement.executeUpdate();
        }
    }

    private static Backend backend(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result =
                statement.executeQuery("SELECT pid,backend_start FROM pg_catalog.pg_stat_activity "
                        + "WHERE pid=pg_backend_pid()")) {
            result.next();
            return new Backend(result.getInt(1), result.getTimestamp(2).toInstant());
        }
    }

    private static boolean advisory(Connection connection, String function, long key)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + function + "(?)")) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static void lock(Connection connection, long key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private record Backend(int pid, Instant startedAt) {
    }
}
