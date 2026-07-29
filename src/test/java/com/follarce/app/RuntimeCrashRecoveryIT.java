package com.follarce.app;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import com.follarce.persistence.postgres.repository.JdbcProcessRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real JVM and PostgreSQL-container kill recovery, including committed FCL continuation replay. */
@Testcontainers(disabledWithoutDocker = true)
class RuntimeCrashRecoveryIT {
    private static final String PASSWORD = "runtime-crash-test-password";
    private static final String INSTANCE = "crash-recovery-test";
    private static final String LOCK_KEY = "570019330771";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres:18.0-alpine3.22");

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE cilexec_owner NOLOGIN");
            statement.execute("CREATE ROLE cilexec_migrator LOGIN CREATEROLE PASSWORD '" + PASSWORD + "'");
            statement.execute("CREATE ROLE cilexec_runtime LOGIN PASSWORD '" + PASSWORD + "'");
            statement.execute("CREATE ROLE cilexec_effect_worker LOGIN PASSWORD '" + PASSWORD + "'");
            statement.execute("CREATE ROLE cilexec_readonly LOGIN PASSWORD '" + PASSWORD + "'");
            statement.execute("GRANT cilexec_owner TO cilexec_migrator");
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
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("GRANT CONNECT ON DATABASE \""
                    + connection.getCatalog().replace("\"", "\"\"")
                    + "\" TO cilexec_runtime,cilexec_effect_worker");
            statement.execute("GRANT USAGE ON SCHEMA flyway TO cilexec_runtime");
            statement.execute("GRANT SELECT ON flyway.flyway_schema_history TO cilexec_runtime");
        }
    }

    @Test
    void forcedJvmKillRestartsRuntimeFromTheLastCommittedContinuation() throws Exception {
        Path secret = temporaryDirectory.resolve("database.password");
        Files.writeString(secret, PASSWORD);
        UUID processUid;

        Process first = startRuntime(secret, temporaryDirectory.resolve("runtime-1.log"));
        try {
            awaitActiveBoot(first, 1, Duration.ofSeconds(15));
            processUid = seedRunningFclProcess();
            first.destroyForcibly();
            assertTrue(first.waitFor(10, TimeUnit.SECONDS));
            assertNotEquals(0, first.exitValue());
        } finally {
            forceStop(first);
        }

        Path secondOutput = temporaryDirectory.resolve("runtime-2.log");
        Process second = startRuntime(secret, secondOutput);
        try {
            awaitActiveBoot(second, 1, Duration.ofSeconds(15));
            awaitProcessStatus(processUid, "TERMINATED", Duration.ofSeconds(15), secondOutput);
            assertEquals(1, count("SELECT count(*) FROM meta.boot WHERE status='CRASHED'"));
            assertEquals(1, count("SELECT count(*) FROM process.event WHERE process_uid='"
                    + processUid + "'::uuid AND event_type='STATE_COMMITTED' AND state_version=1"));
            second.destroy();
            assertTrue(second.waitFor(15, TimeUnit.SECONDS));
        } finally {
            forceStop(second);
        }

        assertEquals(1, count("SELECT count(*) FROM process.process WHERE process_uid='"
                + processUid + "'::uuid AND status='TERMINATED'"));
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT meta.assert_security_invariants()");
        }
    }

    private UUID seedRunningFclProcess() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID programId = UUID.randomUUID();
        UUID processUid = UUID.randomUUID();
        String source = "recovered = 42\n";
        FclProgram compiled = new FclCompiler().compile(source);
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        byte[] compiledBytes = new FclProgramCodec().toJson(compiled)
                .getBytes(StandardCharsets.UTF_8);
        ObjectHash sourceHash = ObjectHash.sha256(new BinaryContent(sourceBytes));
        ObjectHash compiledHash = ObjectHash.sha256(new BinaryContent(compiledBytes));

        try (Connection connection = adminConnection()) {
            String roleName = "cilexec_user_" + ownerId.toString().replace("-", "");
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO auth.user_account(user_id,username,postgres_role_name,status) "
                            + "VALUES (?,?,?,'ACTIVE')")) {
                statement.setObject(1, ownerId);
                statement.setString(2, "crash-owner");
                statement.setString(3, roleName);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT auth.provision_principal(?,?)")) {
                statement.setObject(1, ownerId);
                statement.setString(2, com.follarce.auth.PasswordPolicy.hash(
                        PASSWORD.toCharArray()));
                statement.execute();
            }
        }

        try (Connection connection = adminConnection()) {
            connection.setAutoCommit(false);
            insertObject(connection, sourceHash, sourceBytes, "text/x-fcl", ownerId);
            insertObject(connection, compiledHash, compiledBytes,
                    "application/vnd.cilexec.fcl-program+json; version=1", ownerId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO program.program(program_id,owner_id,program_hash,language_version,"
                            + "runtime_format_version,source_object_hash,compiled_object_hash,"
                            + "statement_count) VALUES (?,?,?,'fcl-1',1,?,?,1)")) {
                statement.setObject(1, programId);
                statement.setObject(2, ownerId);
                statement.setBytes(3, JdbcValues.hash(sourceHash));
                statement.setBytes(4, JdbcValues.hash(sourceHash));
                statement.setBytes(5, JdbcValues.hash(compiledHash));
                statement.executeUpdate();
            }
            Continuation continuation = new Continuation(programId, sourceHash, 0,
                    List.of(), List.of(), List.of(), List.of(), Optional.empty(), Map.of(),
                    Map.of(), "fcl-1", "1");
            Instant now = Instant.now();
            new JdbcProcessRepository(connection, new JsonCodec()).insert(new CilProcess(
                    new ProcessIdentity(processUid, 7001), ownerId, CilProcess.Status.RUNNING,
                    0, 1, continuation, Optional.empty(), now, now));
            connection.commit();
        }
        return processUid;
    }

    private static void insertObject(Connection connection, ObjectHash hash, byte[] content,
                                     String mediaType, UUID ownerId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO object_store.object(object_hash,byte_size,media_type,content,created_by) "
                        + "VALUES (?,?,?,?,?)")) {
            statement.setBytes(1, JdbcValues.hash(hash));
            statement.setLong(2, content.length);
            statement.setString(3, mediaType);
            statement.setBytes(4, content);
            statement.setObject(5, ownerId);
            statement.executeUpdate();
        }
    }

    private Process startRuntime(Path secret, Path output) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "com.follarce.Main", "runtime");
        Map<String, String> environment = builder.environment();
        environment.put("CILEXEC_DATABASE_URL", POSTGRES.getJdbcUrl());
        environment.put("CILEXEC_RUNTIME_DATABASE_USER", "cilexec_runtime");
        environment.put("CILEXEC_EFFECT_DATABASE_USER", "cilexec_effect_worker");
        environment.put("CILEXEC_MIGRATOR_DATABASE_USER", "cilexec_migrator");
        environment.put("CILEXEC_RUNTIME_DATABASE_PASSWORD_FILE", secret.toString());
        environment.put("CILEXEC_EFFECT_DATABASE_PASSWORD_FILE", secret.toString());
        environment.put("CILEXEC_MIGRATOR_DATABASE_PASSWORD_FILE", secret.toString());
        environment.put("CILEXEC_INSTANCE_NAME", INSTANCE);
        environment.put("CILEXEC_ADVISORY_LOCK_KEY", LOCK_KEY);
        environment.put("CILEXEC_SCHEDULER_WORKERS", "1");
        environment.put("CILEXEC_EFFECT_WORKERS", "1");
        environment.put("CILEXEC_RUNTIME_POOL_MAX", "4");
        environment.put("CILEXEC_RUNTIME_POOL_MIN_IDLE", "1");
        environment.put("CILEXEC_EFFECT_POOL_MAX", "2");
        environment.put("CILEXEC_EFFECT_POOL_MIN_IDLE", "1");
        environment.put("CILEXEC_LEASE_DURATION", "PT2S");
        environment.put("CILEXEC_SHUTDOWN_GRACE", "PT5S");
        environment.put("CILEXEC_HEALTH_PORT", Integer.toString(availablePort()));
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        return builder.start();
    }

    private static void awaitActiveBoot(Process runtime, int expected, Duration timeout)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!runtime.isAlive()) throw new AssertionError("Runtime exited before readiness");
            if (count("SELECT count(*) FROM meta.boot WHERE status='ACTIVE'") == expected) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Runtime did not become ready");
    }

    private static void awaitProcessStatus(UUID processUid, String status, Duration timeout,
                                           Path runtimeOutput)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (count("SELECT count(*) FROM process.process WHERE process_uid='" + processUid
                    + "'::uuid AND status='" + status + "'") == 1) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Process did not reach " + status + ". "
                + processSnapshot(processUid) + "\n" + Files.readString(runtimeOutput));
    }

    private static String processSnapshot(UUID processUid) throws Exception {
        try (Connection connection = adminConnection(); Statement statement =
                connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT status,state_version,execution_epoch,failure_code,failure_message "
                        + "FROM process.process WHERE process_uid='" + processUid + "'::uuid")) {
            if (!result.next()) return "process row missing";
            return "status=" + result.getString(1) + ", stateVersion=" + result.getLong(2)
                    + ", epoch=" + result.getLong(3) + ", failureCode=" + result.getString(4)
                    + ", failureMessage=" + result.getString(5);
        }
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = adminConnection(); Statement statement =
                connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void forceStop(Process process) throws InterruptedException {
        if (!process.isAlive()) return;
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
