package com.follarce.app;

import com.follarce.config.LocalDatabaseCredentials;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Owns a private PostgreSQL process and its durable directory beside the executable JAR. */
final class LocalPostgres implements AutoCloseable {
    private static final String FORMAT = "0.0.5";
    private static final String FORMAT_FILE = "format.version";
    static final String OLD_DATA_MESSAGE = "Existing cilexec-data is not CilExec " + FORMAT
            + "; old data are neither migrated nor deleted. Use a new JAR directory";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String[] SERVICE_ROLES = {"cilexec_migrator", "cilexec_runtime",
            "cilexec_effect_worker", "cilexec_readonly", "cilexec_exporter"};
    private final EmbeddedPostgres postgres;
    private final boolean newDatabase;

    private LocalPostgres(EmbeddedPostgres postgres, boolean newDatabase) {
        this.postgres = postgres;
        this.newDatabase = newDatabase;
    }

    static LocalPostgres start() {
        try {
            Path directory = jarDirectory().resolve("cilexec-data");
            boolean existingDatabase = requireDataFormat(directory);
            requireNoRunningServer(directory);
            Files.createDirectories(directory);
            Path formatFile = directory.resolve(FORMAT_FILE);
            if (!existingDatabase && !Files.exists(formatFile)) {
                Files.writeString(formatFile, FORMAT + "\n",
                        java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            // The embedded library otherwise creates and sweeps its working directory in /tmp.
            System.setProperty("ot.epg.working-dir", directory.resolve("epg").toString());
            boolean newDatabase = !existingDatabase;
            Map<String, String> passwords = new HashMap<>();
            for (String role : SERVICE_ROLES) passwords.put(role, randomPassword());
            passwords.put("postgres", randomPassword());
            EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                    .setDataDirectory(directory.resolve("postgres"))
                    .setOverrideWorkingDirectory(directory.resolve("bin").toFile())
                    .setCleanDataDirectory(false)
                    .setRegisterShutdownHook(false)
                    .setPGStartupWait(Duration.ofSeconds(30))
                    .setServerConfig("listen_addresses", "localhost")
                    .setServerConfig("unix_socket_directories", "")
                    .setServerConfig("fsync", "on")
                    .setServerConfig("synchronous_commit", "on")
                    .setDataDirectoryCustomizer(data -> writeHba(data.toPath(), true))
                    .start();
            try {
                bootstrap(postgres, passwords, directory.resolve("postgres"));
                LocalDatabaseCredentials.install(passwords);
                return new LocalPostgres(postgres, newDatabase);
            } catch (SQLException | RuntimeException failure) {
                postgres.close();
                LocalDatabaseCredentials.clear();
                throw failure;
            }
        } catch (IOException | SQLException | URISyntaxException failure) {
            throw new IllegalStateException("Cannot start the local PostgreSQL database beside the JAR", failure);
        }
    }

    int port() {
        return postgres.getPort();
    }

    boolean newDatabase() {
        return newDatabase;
    }

    /**
     * Rejects old data before the caller creates a directory, writes the format marker, starts
     * PostgreSQL, or runs a migration. The refusal only reads the existing files, so a failed
     * start leaves old data byte-identical; nothing is migrated, renamed, or deleted.
     *
     * @return whether an initialized PostgreSQL data directory already exists
     */
    static boolean requireDataFormat(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path formatFile = directory.resolve(FORMAT_FILE);
        if (Files.isRegularFile(formatFile)
                && !FORMAT.equals(Files.readString(formatFile).trim())) {
            throw new DataFormatMismatchException();
        }
        boolean existingDatabase = Files.exists(directory.resolve("postgres/PG_VERSION"));
        if (existingDatabase && !Files.isRegularFile(formatFile)) {
            throw new DataFormatMismatchException();
        }
        return existingDatabase;
    }

    /** Pre-start refusal for a data directory that belongs to another CilExec format. */
    static final class DataFormatMismatchException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        DataFormatMismatchException() {
            super(OLD_DATA_MESSAGE);
        }
    }

    /**
     * Refuses to start a second embedded server for a data directory whose previous
     * postmaster is still alive. This normally means a JAR was killed without a graceful
     * shutdown; CilExec never kills that process automatically.
     */
    static void requireNoRunningServer(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path dataDirectory = directory.resolve("postgres").toAbsolutePath().normalize();
        Path pidFile = dataDirectory.resolve("postmaster.pid");
        if (!Files.isRegularFile(pidFile)) return;
        java.util.List<String> lines;
        try {
            lines = Files.readAllLines(pidFile);
        } catch (IOException unreadable) {
            return;
        }
        if (lines.isEmpty()) return;
        long pid;
        try {
            pid = Long.parseLong(lines.getFirst().trim());
        } catch (NumberFormatException invalid) {
            return;
        }
        if (pid <= 0) return;
        if (lines.size() > 1) {
            try {
                Path recorded = Path.of(lines.get(1).trim()).toAbsolutePath().normalize();
                // A reused PID for an unrelated process is not a reason to refuse startup.
                if (!recorded.equals(dataDirectory)) return;
            } catch (RuntimeException invalid) {
                return;
            }
        }
        ProcessHandle process = ProcessHandle.of(pid).orElse(null);
        if (process != null && process.isAlive()) {
            throw new RunningServerException(pid);
        }
    }

    /** Pre-start refusal when the previous embedded PostgreSQL is still holding the data. */
    static final class RunningServerException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        RunningServerException(long pid) {
            super("cilexec-data is still in use by the embedded PostgreSQL server (pid " + pid
                    + "). A previous CilExec JAR did not shut down cleanly. Stop that server "
                    + "before starting CilExec again, for example: "
                    + "cilexec-data/bin/PG-*/bin/pg_ctl -D cilexec-data/postgres stop -m fast -w");
        }
    }

    private static Path jarDirectory() throws URISyntaxException {
        Path location = Path.of(CilExecApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath();
        return Files.isDirectory(location) ? location : location.getParent();
    }

    private static void bootstrap(EmbeddedPostgres postgres, Map<String, String> passwords,
                                  Path dataDirectory) throws SQLException {
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            for (String role : new String[]{"cilexec_owner", "cilexec_migrator",
                    "cilexec_runtime", "cilexec_effect_worker", "cilexec_readonly",
                    "cilexec_exporter"}) {
                boolean exists;
                try (ResultSet result = statement.executeQuery(
                        "SELECT 1 FROM pg_roles WHERE rolname = '" + role + "'")) {
                    exists = result.next();
                }
                if (!exists) {
                    boolean owner = role.equals("cilexec_owner");
                    boolean migrator = role.equals("cilexec_migrator");
                    statement.execute("CREATE ROLE " + role + (owner ? " NOLOGIN" : " LOGIN")
                            + (migrator ? " INHERIT CREATEROLE" :
                            " NOINHERIT NOCREATEROLE")
                            + " NOSUPERUSER NOCREATEDB NOREPLICATION NOBYPASSRLS");
                }
            }
            statement.execute("ALTER ROLE cilexec_owner NOLOGIN INHERIT NOSUPERUSER "
                    + "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("ALTER ROLE cilexec_migrator LOGIN INHERIT NOSUPERUSER "
                    + "NOCREATEDB CREATEROLE NOREPLICATION NOBYPASSRLS");
            for (String role : new String[]{"cilexec_runtime", "cilexec_effect_worker",
                    "cilexec_readonly", "cilexec_exporter"}) {
                statement.execute("ALTER ROLE " + role + " LOGIN NOINHERIT NOSUPERUSER "
                        + "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            }
            statement.execute("GRANT cilexec_owner TO cilexec_migrator");
            statement.execute("ALTER ROLE cilexec_readonly SET default_transaction_read_only TO on");
            statement.execute("ALTER ROLE cilexec_exporter SET default_transaction_read_only TO on");
            boolean databaseExists;
            try (ResultSet result = statement.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = 'cilexec'")) {
                databaseExists = result.next();
            }
            if (!databaseExists) {
                statement.execute("CREATE DATABASE cilexec OWNER cilexec_owner "
                        + "ENCODING 'UTF8' TEMPLATE template0");
            }
            statement.execute("REVOKE ALL ON DATABASE cilexec FROM PUBLIC");
            statement.execute("GRANT CONNECT ON DATABASE cilexec TO cilexec_migrator, "
                    + "cilexec_runtime, cilexec_effect_worker, cilexec_readonly, cilexec_exporter");
        }
        try (Connection connection = postgres.getDatabase("postgres", "cilexec")
                .getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("CREATE SCHEMA IF NOT EXISTS flyway AUTHORIZATION cilexec_migrator");
            statement.execute("REVOKE ALL ON SCHEMA flyway FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA flyway TO cilexec_migrator");
            statement.execute("GRANT USAGE ON SCHEMA flyway TO cilexec_runtime, cilexec_exporter");
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_migrator IN SCHEMA flyway "
                    + "GRANT SELECT ON TABLES TO cilexec_runtime, cilexec_exporter");
            // Each start rotates database passwords in memory. The stored PostgreSQL role
            // hashes survive restart; the temporary trust rule exists only during bootstrap.
            for (Map.Entry<String, String> entry : passwords.entrySet()) {
                statement.execute("ALTER ROLE " + entry.getKey() + " PASSWORD '"
                        + entry.getValue() + "'");
            }
            writeHba(dataDirectory, false);
            statement.execute("SELECT pg_reload_conf()");
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void writeHba(Path dataDirectory, boolean bootstrap) {
        String access = bootstrap ? "trust" : "scram-sha-256";
        String rules = "# Managed by the CilExec JAR. Only its loopback database is reachable.\n"
                + "local all all reject\n"
                + "host all all 127.0.0.1/32 " + access + "\n"
                + "host all all ::1/128 " + access + "\n";
        try {
            Files.writeString(dataDirectory.resolve("pg_hba.conf"), rules);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot configure local database authentication", failure);
        }
    }

    private volatile boolean closed;

    @Override
    public void close() {
        if (closed) return;
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        try {
            postgres.close();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot stop local PostgreSQL", failure);
        } finally {
            LocalDatabaseCredentials.clear();
        }
    }
}
