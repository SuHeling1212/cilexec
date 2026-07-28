package com.follarce.app;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sends a real SIGTERM to the production entry point and verifies durable clean shutdown. */
@Testcontainers(disabledWithoutDocker = true)
class RuntimeSignalIT {
    private static final String PASSWORD = "runtime-signal-test-password";

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
    void sigtermRunsTheBoundedShutdownAndMarksTheBootClean() throws Exception {
        Path secret = temporaryDirectory.resolve("database.password");
        Files.writeString(secret, PASSWORD);
        Path output = temporaryDirectory.resolve("runtime.log");
        int healthPort = availablePort();
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
        environment.put("CILEXEC_INSTANCE_NAME", "signal-test");
        environment.put("CILEXEC_ADVISORY_LOCK_KEY", "4411099817001");
        environment.put("CILEXEC_SCHEDULER_WORKERS", "1");
        environment.put("CILEXEC_EFFECT_WORKERS", "1");
        environment.put("CILEXEC_RUNTIME_POOL_MAX", "4");
        environment.put("CILEXEC_RUNTIME_POOL_MIN_IDLE", "1");
        environment.put("CILEXEC_EFFECT_POOL_MAX", "2");
        environment.put("CILEXEC_EFFECT_POOL_MIN_IDLE", "1");
        environment.put("CILEXEC_HEARTBEAT_INTERVAL", "PT0.2S");
        environment.put("CILEXEC_LEASE_DURATION", "PT2S");
        environment.put("CILEXEC_SHUTDOWN_GRACE", "PT5S");
        environment.put("CILEXEC_HEALTH_PORT", Integer.toString(healthPort));
        builder.redirectErrorStream(true).redirectOutput(output.toFile());

        Process runtime = builder.start();
        try {
            awaitActiveBoot(runtime, output, Duration.ofSeconds(15));
            assertTrue(runtime.isAlive(), diagnostic(output));

            runtime.destroy();
            assertTrue(runtime.waitFor(15, TimeUnit.SECONDS), diagnostic(output));
            assertTrue(runtime.exitValue() == 0 || runtime.exitValue() == 143,
                    diagnostic(output));

            try (Connection connection = adminConnection(); Statement statement =
                    connection.createStatement(); ResultSet result = statement.executeQuery(
                    "SELECT boot.status,boot.shutdown_reason,runtime.status,instance.status "
                            + "FROM meta.boot AS boot "
                            + "JOIN meta.kernel_instance AS runtime ON runtime.kernel_instance_id="
                            + "boot.kernel_instance_id JOIN meta.instance AS instance ON "
                            + "instance.instance_id=boot.instance_id ORDER BY boot.started_at DESC LIMIT 1")) {
                assertTrue(result.next());
                assertEquals("CLEAN", result.getString(1));
                assertEquals("SIGTERM", result.getString(2));
                assertEquals("STOPPED", result.getString(3));
                assertEquals("STOPPED", result.getString(4));
            }
        } finally {
            if (runtime.isAlive()) {
                runtime.destroyForcibly();
                runtime.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void defaultEntryPointRunsThePersistentFclTerminalAndShutsDownOnExit() throws Exception {
        Path secret = temporaryDirectory.resolve("terminal.password");
        Files.writeString(secret, PASSWORD);
        Path output = temporaryDirectory.resolve("terminal.log");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "com.follarce.Main");
        configure(builder.environment(), secret, availablePort(), "signal-test",
                "4411099817001");
        builder.environment().put("CILEXEC_TERMINAL_USERNAME", "local");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());

        Process terminal = builder.start();
        try {
            String alicePassword = "alice123";
            terminal.getOutputStream().write((PASSWORD + "\n" + PASSWORD + "\n"
                    + "login\nlocal\nwrong-password-value\n"
                    + "login\nlocal\n" + PASSWORD + "\n"
                    + "answer = 40 + 2\n:logout\n"
                    + "login\nlocal\n" + PASSWORD + "\nanswer + 1\n{\"answer\": answer}\n:logout\n"
                    + "create\nalice\n" + alicePassword + "\n" + alicePassword + "\n"
                    + "n\nuser.isLocal()\n:logout\nlogin\nalice\n" + alicePassword + "\n"
                    + "user.isLocal()\n:exit\n").getBytes(StandardCharsets.UTF_8));
            terminal.getOutputStream().flush();
            terminal.getOutputStream().close();

            assertTrue(terminal.waitFor(25, TimeUnit.SECONDS), diagnostic(output));
            assertEquals(0, terminal.exitValue(), diagnostic(output));
            String transcript = Files.readString(output);
            assertTrue(transcript.contains("CilExec access"), transcript);
            assertTrue(transcript.contains("invalid username or password"), transcript);
            assertTrue(transcript.contains("authenticated as local"), transcript);
            assertTrue(transcript.contains("authenticated as alice"), transcript);
            assertTrue(transcript.contains("43"), transcript);
            assertTrue(transcript.contains("\"answer\":42"), transcript);
            assertTrue(transcript.contains("false"), transcript);
            assertEquals(1, count("SELECT count(*) FROM auth.user_account account "
                    + "JOIN auth.user_capability assignment USING (user_id) "
                    + "JOIN auth.capability capability USING (capability_id) "
                    + "WHERE account.username='local' AND capability.capability_key='system_admin'"));
            assertEquals(9, count("SELECT count(*) FROM auth.user_account account "
                    + "JOIN auth.user_capability assignment USING (user_id) "
                    + "WHERE account.username='alice'"));
            assertEquals(0, count("SELECT count(*) FROM auth.user_account account "
                    + "JOIN auth.user_capability assignment USING (user_id) "
                    + "JOIN auth.capability capability USING (capability_id) "
                    + "WHERE account.username='alice' "
                    + "AND capability.capability_key='system_admin'"));
            assertEquals(1, count("SELECT count(*) FROM vfs.node node "
                    + "JOIN auth.user_account account ON account.user_id=node.owner_id "
                    + "WHERE account.username='alice' AND node.parent_node_id IS NULL "
                    + "AND node.node_name='/'"));
            assertEquals(5, count("SELECT count(*) FROM audit.event "
                    + "WHERE action='terminal.repl.submit'"));
        } finally {
            if (terminal.isAlive()) {
                terminal.destroyForcibly();
                terminal.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = adminConnection(); Statement statement =
                connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void configure(Map<String, String> environment, Path secret, int healthPort,
                                  String instance, String lockKey) {
        environment.put("CILEXEC_DATABASE_URL", POSTGRES.getJdbcUrl());
        environment.put("CILEXEC_RUNTIME_DATABASE_USER", "cilexec_runtime");
        environment.put("CILEXEC_EFFECT_DATABASE_USER", "cilexec_effect_worker");
        environment.put("CILEXEC_MIGRATOR_DATABASE_USER", "cilexec_migrator");
        environment.put("CILEXEC_RUNTIME_DATABASE_PASSWORD_FILE", secret.toString());
        environment.put("CILEXEC_EFFECT_DATABASE_PASSWORD_FILE", secret.toString());
        environment.put("CILEXEC_MIGRATOR_DATABASE_PASSWORD_FILE", secret.toString());
        environment.put("CILEXEC_INSTANCE_NAME", instance);
        environment.put("CILEXEC_ADVISORY_LOCK_KEY", lockKey);
        environment.put("CILEXEC_SCHEDULER_WORKERS", "1");
        environment.put("CILEXEC_EFFECT_WORKERS", "1");
        environment.put("CILEXEC_RUNTIME_POOL_MAX", "4");
        environment.put("CILEXEC_RUNTIME_POOL_MIN_IDLE", "1");
        environment.put("CILEXEC_EFFECT_POOL_MAX", "2");
        environment.put("CILEXEC_EFFECT_POOL_MIN_IDLE", "1");
        environment.put("CILEXEC_HEARTBEAT_INTERVAL", "PT0.2S");
        environment.put("CILEXEC_LEASE_DURATION", "PT2S");
        environment.put("CILEXEC_SHUTDOWN_GRACE", "PT5S");
        environment.put("CILEXEC_HEALTH_PORT", Integer.toString(healthPort));
    }

    private static void awaitActiveBoot(Process runtime, Path output, Duration timeout)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!runtime.isAlive()) {
                throw new AssertionError("Runtime exited before readiness. " + diagnostic(output));
            }
            try (Connection connection = adminConnection(); Statement statement =
                    connection.createStatement(); ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM meta.boot WHERE status='ACTIVE'")) {
                result.next();
                if (result.getInt(1) == 1) return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Runtime did not become ready. " + diagnostic(output));
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String diagnostic(Path output) {
        try {
            return Files.exists(output) ? Files.readString(output) : "no runtime output";
        } catch (IOException ignored) {
            return "runtime output is unreadable";
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
