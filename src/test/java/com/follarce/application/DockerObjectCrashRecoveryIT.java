package com.follarce.application;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclObjectValue;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import com.follarce.persistence.postgres.repository.JdbcProcessRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Runs the packaged Runtime in Docker, force-kills its PID 1, and proves that an object
 * value objects, an explicit link relationship, and an active try region committed before the
 * kill resume from PostgreSQL without introducing persisted object identity.
 */
@org.testcontainers.junit.jupiter.Testcontainers
class DockerObjectCrashRecoveryIT {
    private static final String PASSWORD = "docker-object-crash-password";
    private static final String INSTANCE = "docker-object-crash";
    private static final String LOCK_KEY = "570019330772";
    private static final DockerImageName RUNTIME_IMAGE = DockerImageName.parse(System.getProperty(
            "cilexec.test.runtime.image", "maven:3.9.16-eclipse-temurin-26-noble"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getProperty("cilexec.test.postgres.image", "postgres:17.10-alpine3.23"));

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = adminConnection()) {
            com.follarce.persistence.postgres.PostgresTestBootstrap.createServiceRoles(
                    connection, PASSWORD);
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(),
                        com.follarce.persistence.postgres.PostgresTestBootstrap.MIGRATOR_ROLE,
                        PASSWORD)
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
    void dockerKillNinePreservesCommittedObjectValuesLinksAndTryHandlers() throws Exception {
        Path application = Path.of("target", "cilexec-app.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(application), "Failsafe must run after the packaged JAR");
        Path secret = Files.createTempFile("cilexec-docker-object-crash-", ".password");
        Files.writeString(secret, PASSWORD, StandardCharsets.UTF_8);
        UUID processUid = UUID.randomUUID();

        GenericContainer<?> first = startRuntime(application, secret);
        try {
            first.start();
            awaitActiveBoot(Duration.ofSeconds(20), first);
            seedSleepingObjectProcess(processUid);
            awaitProcessStatus(processUid, "WAITING_TIMER", Duration.ofSeconds(20), first);

            DockerClientFactory.instance().client().killContainerCmd(first.getContainerId())
                    .withSignal("KILL").exec();
            awaitStopped(first.getContainerId(), Duration.ofSeconds(10));
            assertFalse(isRunning(first.getContainerId()));
        } finally {
            if (isRunning(first.getContainerId())) first.stop();
        }

        makeTimerDue(processUid);
        GenericContainer<?> second = startRuntime(application, secret);
        try {
            second.start();
            awaitActiveBoot(Duration.ofSeconds(20), second);
            awaitProcessStatus(processUid, "TERMINATED", Duration.ofSeconds(20), second);
        } finally {
            if (isRunning(second.getContainerId())) second.stop();
            Files.deleteIfExists(secret);
        }

        FclContinuation restored = persistedRuntime(processUid);
        assertEquals(42L, restored.scope().get("recovered"));
        assertEquals("UndefinedFunction", restored.scope().get("caughtType"));
        FclObjectValue root = (FclObjectValue) restored.scope().get("root");
        FclObjectValue alias = (FclObjectValue) restored.scope().get("rootAlias");
        FclObjectValue shared = (FclObjectValue) restored.scope().get("rootShared");
        assertEquals(root, alias);
        assertNotSame(root, alias);
        assertSame(root, shared, "the restored name must still follow root");
        assertEquals(42L, ((FclObjectValue) root.field("next")).field("value"));
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT meta.assert_security_invariants()");
        }
    }

    private static GenericContainer<?> startRuntime(Path application, Path secret) {
        return new GenericContainer<>(RUNTIME_IMAGE)
                .withNetworkMode("container:" + POSTGRES.getContainerId())
                .withCreateContainerCmdModifier(command -> command
                        .withEntrypoint("sh", "-c")
                        .withCmd("exec java --enable-native-access=ALL-UNNAMED -jar "
                                + "/opt/cilexec/cilexec-app.jar runtime"))
                .withCopyFileToContainer(MountableFile.forHostPath(application),
                        "/opt/cilexec/cilexec-app.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(secret),
                        "/run/secrets/database-password")
                .withEnv("CILEXEC_DATABASE_URL", "jdbc:postgresql://localhost:5432/"
                        + POSTGRES.getDatabaseName())
                .withEnv("CILEXEC_RUNTIME_DATABASE_USER", "cilexec_runtime")
                .withEnv("CILEXEC_EFFECT_DATABASE_USER", "cilexec_effect_worker")
                .withEnv("CILEXEC_MIGRATOR_DATABASE_USER", "cilexec_migrator")
                .withEnv("CILEXEC_RUNTIME_DATABASE_PASSWORD_FILE", "/run/secrets/database-password")
                .withEnv("CILEXEC_EFFECT_DATABASE_PASSWORD_FILE", "/run/secrets/database-password")
                .withEnv("CILEXEC_MIGRATOR_DATABASE_PASSWORD_FILE", "/run/secrets/database-password")
                .withEnv("CILEXEC_INSTANCE_NAME", INSTANCE)
                .withEnv("CILEXEC_ADVISORY_LOCK_KEY", LOCK_KEY)
                .withEnv("CILEXEC_SCHEDULER_WORKERS", "1")
                .withEnv("CILEXEC_EFFECT_WORKERS", "1")
                .withEnv("CILEXEC_RUNTIME_POOL_MAX", "4")
                .withEnv("CILEXEC_RUNTIME_POOL_MIN_IDLE", "1")
                .withEnv("CILEXEC_EFFECT_POOL_MAX", "2")
                .withEnv("CILEXEC_EFFECT_POOL_MIN_IDLE", "1")
                .withEnv("CILEXEC_LEASE_DURATION", "PT2S")
                .withEnv("CILEXEC_SHUTDOWN_GRACE", "PT5S");
    }

    private static void seedSleepingObjectProcess(UUID processUid) throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID programId = UUID.randomUUID();
        String source = """
                class Node {
                    value = 0
                    next = null
                }
                rootShared link root
                try {
                    util.sleep(600000)
                    missing()
                } catch (e) {
                    caughtType = e.type
                    recovered = rootShared.next.value
                }
                """;
        FclProgram compiled = new FclCompiler().compile(source);
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        byte[] compiledBytes = new FclProgramCodec().toJson(compiled)
                .getBytes(StandardCharsets.UTF_8);
        ObjectHash sourceHash = ObjectHash.sha256(new BinaryContent(sourceBytes));
        ObjectHash compiledHash = ObjectHash.sha256(new BinaryContent(compiledBytes));
        Instant now = Instant.now();

        FclContinuation runtime = new FclContinuation();
        FclObjectValue child = new FclObjectValue("Node", nodeFields(42L, null));
        FclObjectValue root = new FclObjectValue("Node", nodeFields(0L, child));
        runtime.scope().put("root", root);
        runtime.scope().put("rootAlias", root);

        Program program = new Program(programId, sourceHash, ProgramService.LANGUAGE_VERSION,
                FclProgramCodec.FORMAT_VERSION, sourceHash, Optional.of(compiledHash), 2, now);
        Continuation initial = new Continuation(programId, sourceHash, 0,
                List.of(), List.of(), List.of(), List.of(), Optional.empty(), Map.of(), Map.of(),
                ProgramService.LANGUAGE_VERSION, Integer.toString(FclProgramCodec.FORMAT_VERSION));
        Continuation persisted = new FclPersistenceBridge(new FclContinuationCodec()).persist(
                processUid, program, initial, runtime);

        try (Connection connection = adminConnection()) {
            String roleName = "cilexec_user_" + ownerId.toString().replace("-", "");
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO auth.user_account(user_id,username,postgres_role_name,status) "
                            + "VALUES (?,?,?,'ACTIVE')")) {
                statement.setObject(1, ownerId);
                statement.setString(2, "docker-object-owner");
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
            insertObject(connection, sourceHash, sourceBytes, ProgramService.SOURCE_MEDIA_TYPE, ownerId);
            insertObject(connection, compiledHash, compiledBytes, ProgramService.COMPILED_MEDIA_TYPE,
                    ownerId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO program.program(program_id,owner_id,program_hash,language_version,"
                            + "runtime_format_version,source_object_hash,compiled_object_hash,"
                            + "statement_count) VALUES (?,?,?,'fcl-0.0.2',?,?,?,2)")) {
                statement.setObject(1, programId);
                statement.setObject(2, ownerId);
                statement.setBytes(3, JdbcValues.hash(sourceHash));
                statement.setInt(4, FclProgramCodec.FORMAT_VERSION);
                statement.setBytes(5, JdbcValues.hash(sourceHash));
                statement.setBytes(6, JdbcValues.hash(compiledHash));
                statement.executeUpdate();
            }
            new JdbcProcessRepository(connection, new JsonCodec()).insert(new CilProcess(
                    new ProcessIdentity(processUid, 7101), ownerId, CilProcess.Status.READY,
                    0, 0, persisted, Optional.empty(), now, now));
            try (PreparedStatement queue = connection.prepareStatement(
                    "INSERT INTO scheduler.queue(process_uid,owner_id,queue_state,ready_at,enqueued_at) "
                            + "VALUES (?,?,'READY',clock_timestamp(),clock_timestamp())")) {
                queue.setObject(1, processUid);
                queue.setObject(2, ownerId);
                queue.executeUpdate();
            }
            try (Statement notify = connection.createStatement()) {
                notify.execute("SELECT pg_notify('cilexec_scheduler_work', '')");
            }
            connection.commit();
        }
    }

    private static Map<String, Object> nodeFields(long value, Object next) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("value", value);
        fields.put("next", next);
        return fields;
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

    private static void makeTimerDue(UUID processUid) throws Exception {
        try (Connection connection = adminConnection(); PreparedStatement statement =
                connection.prepareStatement("UPDATE process.timer SET wake_at=clock_timestamp() "
                        + "WHERE process_uid=? AND status='SCHEDULED'")) {
            statement.setObject(1, processUid);
            assertEquals(1, statement.executeUpdate());
            try (Statement notify = connection.createStatement()) {
                notify.execute("SELECT pg_notify('cilexec_timer_work', '')");
            }
        }
    }

    private static FclContinuation persistedRuntime(UUID processUid) throws Exception {
        try (Connection connection = adminConnection()) {
            CilProcess process = new JdbcProcessRepository(connection, new JsonCodec()).findByUid(processUid)
                    .orElseThrow();
            return new FclPersistenceBridge(new FclContinuationCodec()).restore(process.continuation());
        }
    }

    private static void awaitActiveBoot(Duration timeout, GenericContainer<?> runtime) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (count("SELECT count(*) FROM meta.boot WHERE status='ACTIVE'") == 1) return;
            if (!isRunning(runtime.getContainerId())) {
                throw new AssertionError("Runtime exited before readiness: " + diagnostics(runtime));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Runtime did not become ready: " + diagnostics(runtime));
    }

    private static void awaitProcessStatus(UUID processUid, String status, Duration timeout,
                                           GenericContainer<?> runtime) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (count("SELECT count(*) FROM process.process WHERE process_uid='" + processUid
                    + "'::uuid AND status='" + status + "'") == 1) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Process did not reach " + status + ": " + diagnostics(runtime));
    }

    private static void awaitStopped(String containerId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!isRunning(containerId)) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Docker container did not stop after SIGKILL");
    }

    private static boolean isRunning(String containerId) {
        return DockerClientFactory.instance().client().inspectContainerCmd(containerId).exec()
                .getState().getRunning();
    }

    private static String diagnostics(GenericContainer<?> runtime) {
        var inspected = DockerClientFactory.instance().client()
                .inspectContainerCmd(runtime.getContainerId()).exec();
        return "running=" + inspected.getState().getRunning()
                + ", exitCode=" + inspected.getState().getExitCodeLong()
                + ", entrypoint=" + java.util.Arrays.toString(inspected.getConfig().getEntrypoint())
                + ", command=" + java.util.Arrays.toString(inspected.getConfig().getCmd())
                + ", logs=" + runtime.getLogs();
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
