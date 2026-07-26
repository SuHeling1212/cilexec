package com.follarce.persistence.postgres.repository;

import com.follarce.domain.effect.EffectAttempt;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import com.follarce.persistence.postgres.mapper.JsonCodec;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcProcessEffectIT {
    private static final Instant T0 = Instant.parse("2026-07-22T08:00:00Z");

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
    void synchronizesContinuationProjectionsRelationshipsEventsAndEffectAttempts()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID programId = UUID.randomUUID();
        UUID bootId = UUID.randomUUID();
        ObjectHash programHash = hash("program");
        seed(ownerId, programId, programHash, bootId);

        JsonCodec json = new JsonCodec();
        UUID parentUid = UUID.randomUUID();
        UUID childUid = UUID.randomUUID();
        UUID validRunningUid = UUID.randomUUID();
        UUID corruptRunningUid = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        UUID frameId = UUID.randomUUID();
        UUID timerId = UUID.randomUUID();
        Continuation parentContinuation = continuation(programId, programHash, Optional.empty(),
                List.of(), List.of(), List.of(), Map.of());
        Continuation childContinuation = continuation(programId, programHash,
                Optional.of(new Continuation.WaitState(Continuation.WaitKind.TIMER,
                        Optional.of(timerId), Optional.empty())),
                List.of(new Continuation.CallFrame(frameId, "work", 8, scopeId)),
                List.of(new Continuation.ScopeFrame(scopeId, Optional.empty(), Map.of(
                        "local", value("number", "7"),
                        "nothing", value("null", "{\"type\":\"null\"}")))),
                List.of(new Continuation.ExceptionFrame(12, scopeId,
                        Optional.of(value("error", "pending")))),
                Map.of("global", value("text", "ready")));

        try (Connection connection = runtimeConnection()) {
            JdbcProcessRepository processes = new JdbcProcessRepository(connection, json);
            CilProcess parent = new CilProcess(new ProcessIdentity(parentUid, 100), ownerId,
                    CilProcess.Status.READY, 0, 0, parentContinuation, Optional.empty(), T0, T0);
            CilProcess child = new CilProcess(new ProcessIdentity(childUid, 101), ownerId,
                    CilProcess.Status.WAITING_TIMER, 0, 0, childContinuation,
                    Optional.of(parentUid), T0, T0);
            processes.insert(parent);
            processes.insert(child);

            assertEquals(2, count(connection,
                    "SELECT count(*) FROM process.call_frame WHERE process_uid=?", childUid));
            assertEquals(2, count(connection,
                    "SELECT count(*) FROM process.scope WHERE process_uid=?", childUid));
            assertEquals(3, count(connection,
                    "SELECT count(*) FROM process.variable WHERE process_uid=?", childUid));
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM process.exception_frame WHERE process_uid=?", childUid));
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM process.wait_state WHERE process_uid=?", childUid));
            assertEquals(2, count(connection,
                    "SELECT count(*) FROM process.relationship WHERE process_uid IN (?,?)",
                    childUid, parentUid));

            CilProcess restored = processes.findByUid(childUid).orElseThrow();
            assertEquals(value("number", "7"), restored.continuation().scopeStack()
                    .getFirst().variables().get("local"));
            assertEquals(value("null", "{\"type\":\"null\"}"),
                    restored.continuation().scopeStack().getFirst().variables().get("nothing"));

            Map<String, Continuation.PersistedValue> changedGlobals = Map.of(
                    "global", value("text", "committed"));
            Continuation readyContinuation = continuation(programId, programHash,
                    Optional.empty(), restored.continuation().callStack(),
                    restored.continuation().scopeStack(), restored.continuation().exceptionStack(),
                    changedGlobals);
            CilProcess ready = restored.commitStatement(readyContinuation,
                    CilProcess.Status.READY, restored.stateVersion(), restored.executionEpoch(),
                    T0.plusSeconds(1));
            assertEquals(ProcessRepository.UpdateResult.UPDATED,
                    processes.update(ready, restored.stateVersion(), restored.executionEpoch()));
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM process.wait_state WHERE process_uid=?", childUid));
            assertEquals(2, count(connection,
                    "SELECT count(*) FROM process.event WHERE process_uid=?", childUid));

            JdbcEffectRepository effects = new JdbcEffectRepository(connection, json);
            UUID workerId = UUID.randomUUID();
            effects.registerWorker(workerId, bootId, T0);
            EffectRequest request = EffectRequest.prepare(UUID.randomUUID(), childUid,
                    "test.effect", value("json", "{\"request\":true}"),
                    new EffectRequest.Policy(false, Optional.empty(), false, false,
                            EffectRequest.UnknownAction.MANUAL), T0);
            effects.save(request);
            EffectRequest claimed = effects.claimPending(workerId, T0, 1).getFirst();
            EffectRequest executing = claimed.start(T0);
            assertTrue(effects.update(executing, EffectRequest.Status.CLAIMED));
            EffectAttempt attempt = EffectAttempt.claim(request.effectId(),
                    effects.nextAttemptNumber(request.effectId()), workerId, T0).start();
            effects.saveAttempt(attempt);
            Continuation.PersistedValue result = value("text", "done");
            assertTrue(effects.updateAttempt(attempt.succeed(result, T0.plusSeconds(1)),
                    EffectAttempt.Status.EXECUTING));
            assertTrue(effects.update(executing.complete(result, T0.plusSeconds(1)),
                    EffectRequest.Status.EXECUTING));

            assertEquals(EffectAttempt.Status.SUCCEEDED,
                    effects.findAttempts(request.effectId()).getFirst().status());

            Continuation runningContinuation = continuation(programId, programHash,
                    Optional.empty(), List.of(), List.of(), List.of(), Map.of());
            processes.insert(new CilProcess(new ProcessIdentity(validRunningUid, 102), ownerId,
                    CilProcess.Status.RUNNING, 0, 1, runningContinuation, Optional.empty(),
                    T0, T0));
            processes.insert(new CilProcess(new ProcessIdentity(corruptRunningUid, 103), ownerId,
                    CilProcess.Status.RUNNING, 0, 1, runningContinuation, Optional.empty(),
                    T0, T0));
            assertNotEquals(parentUid, childUid);
            connection.commit();
        }

        try (Connection connection = adminConnection(); PreparedStatement corrupt =
                connection.prepareStatement(
                        "DELETE FROM process.scope WHERE process_uid=?")) {
            corrupt.setObject(1, corruptRunningUid);
            corrupt.executeUpdate();
        }
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        RecoveryCoordinator.RecoveryReport report = new RecoveryCoordinator(dataSource)
                .recover(bootId, T0.plusSeconds(10));

        assertEquals(1, report.recoveredProcesses());
        assertEquals(1, report.failedRecoveryProcesses());
        try (Connection connection = adminConnection()) {
            assertEquals("READY", text(connection,
                    "SELECT status FROM process.process WHERE process_uid=?", validRunningUid));
            assertEquals("FAILED_RECOVERY", text(connection,
                    "SELECT status FROM process.process WHERE process_uid=?", corruptRunningUid));
            assertEquals(CilProcess.Status.FAILED_RECOVERY,
                    new JdbcProcessRepository(connection, new JsonCodec())
                            .findByUid(corruptRunningUid).orElseThrow().status());
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM scheduler.queue WHERE process_uid=?", validRunningUid));
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM scheduler.queue WHERE process_uid=?", corruptRunningUid));
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM process.event WHERE process_uid=? "
                            + "AND event_type='RECOVERY_VALIDATION_FAILED'",
                    corruptRunningUid));
        }
    }

    private static Continuation continuation(
            UUID programId,
            ObjectHash programHash,
            Optional<Continuation.WaitState> wait,
            List<Continuation.CallFrame> calls,
            List<Continuation.ScopeFrame> scopes,
            List<Continuation.ExceptionFrame> exceptions,
            Map<String, Continuation.PersistedValue> globals
    ) {
        List<Continuation.ControlFrame> controls = scopes.isEmpty() ? List.of()
                : List.of(new Continuation.ControlFrame(Continuation.ControlKind.BLOCK,
                0, 20, scopes.getFirst().scopeId()));
        return new Continuation(programId, programHash, 3, calls, scopes, exceptions, controls,
                wait, globals, Map.of(), "fcl-1", "1");
    }

    private static void seed(UUID ownerId, UUID programId, ObjectHash programHash, UUID bootId)
            throws Exception {
        try (Connection connection = adminConnection()) {
            UUID instanceId = UUID.randomUUID();
            UUID runtimeId = UUID.randomUUID();
            String roleName = "cilexec_user_" + ownerId.toString().replace("-", "");
            try (PreparedStatement user = connection.prepareStatement(
                    "INSERT INTO auth.user_account(user_id,username,postgres_role_name,status) "
                            + "VALUES (?,?,?,'ACTIVE')")) {
                user.setObject(1, ownerId);
                user.setString(2, "projection-test");
                user.setString(3, roleName);
                user.executeUpdate();
            }
            try (PreparedStatement instance = connection.prepareStatement(
                    "INSERT INTO meta.instance(instance_id,instance_name,advisory_lock_key,status) "
                            + "VALUES (?,'test',91,'ACTIVE')")) {
                instance.setObject(1, instanceId);
                instance.executeUpdate();
            }
            try (PreparedStatement runtime = connection.prepareStatement(
                    "INSERT INTO meta.kernel_instance(kernel_instance_id,instance_id,runtime_version,"
                            + "fcl_runtime_format_version,hostname,status) VALUES (?,?,'test',1,'test','ACTIVE')")) {
                runtime.setObject(1, runtimeId);
                runtime.setObject(2, instanceId);
                runtime.executeUpdate();
            }
            try (PreparedStatement boot = connection.prepareStatement(
                    "INSERT INTO meta.boot(boot_id,instance_id,kernel_instance_id,status,"
                            + "runtime_version,schema_version,fcl_runtime_format_version,"
                            + "recovery_completed_at,ready_at) VALUES (?,?,?,'ACTIVE','test','15',1,?,?)")) {
                boot.setObject(1, bootId);
                boot.setObject(2, instanceId);
                boot.setObject(3, runtimeId);
                boot.setTimestamp(4, java.sql.Timestamp.from(T0));
                boot.setTimestamp(5, java.sql.Timestamp.from(T0));
                boot.executeUpdate();
            }
            byte[] source = "value = 1".getBytes(StandardCharsets.UTF_8);
            ObjectHash sourceHash = ObjectHash.sha256(new BinaryContent(source));
            try (PreparedStatement object = connection.prepareStatement(
                    "INSERT INTO object_store.object(object_hash,byte_size,media_type,content,"
                            + "created_by) VALUES (?,?,?,?,?)")) {
                object.setBytes(1, JdbcValues.hash(sourceHash));
                object.setLong(2, source.length);
                object.setString(3, "text/plain");
                object.setBytes(4, source);
                object.setObject(5, ownerId);
                object.executeUpdate();
            }
            try (PreparedStatement program = connection.prepareStatement(
                    "INSERT INTO program.program(program_id,owner_id,program_hash,language_version,"
                            + "runtime_format_version,source_object_hash,statement_count) "
                            + "VALUES (?,?,?,'fcl-1',1,?,1)")) {
                program.setObject(1, programId);
                program.setObject(2, ownerId);
                program.setBytes(3, JdbcValues.hash(programHash));
                program.setBytes(4, JdbcValues.hash(sourceHash));
                program.executeUpdate();
            }
        }
    }

    private static int count(Connection connection, String sql, Object... values)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static String text(Connection connection, String sql, Object value)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static Continuation.PersistedValue value(String type, String payload) {
        return new Continuation.PersistedValue(type, payload);
    }

    private static ObjectHash hash(String value) {
        return ObjectHash.sha256(new BinaryContent(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Connection runtimeConnection() throws Exception {
        Connection connection = adminConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE cilexec_runtime");
        }
        return connection;
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
