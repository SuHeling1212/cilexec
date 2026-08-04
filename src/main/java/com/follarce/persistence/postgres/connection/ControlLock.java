package com.follarce.persistence.postgres.connection;

import com.follarce.config.DatabaseConfig;
import com.follarce.config.DockerSecretLoader;
import com.follarce.persistence.postgres.error.SqlStateClassifier;
import org.postgresql.PGConnection;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns the pool-external session that fences a single active Runtime. */
public final class ControlLock implements AutoCloseable {
    private static final String ACQUIRE_SQL = "SELECT pg_try_advisory_lock(?)";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Connection connection;
    private final long lockKey;
    private final ControlIdentity identity;
    private final AtomicBoolean held = new AtomicBoolean(true);
    private final AtomicBoolean monitoring = new AtomicBoolean();
    private Thread monitor;

    private ControlLock(Connection connection, long lockKey, ControlIdentity identity) {
        this.connection = connection;
        this.lockKey = lockKey;
        this.identity = identity;
    }

    public static ControlLock acquire(DatabaseConfig database, long lockKey) {
        DriverManager.setLoginTimeout(15);
        Properties properties = new Properties();
        properties.setProperty("user", database.username());
        properties.setProperty("ApplicationName", database.applicationName() + "-control");
        properties.setProperty("tcpKeepAlive", "true");
        // connectTimeout bounds the TCP connect and handshake; socketTimeout is deliberately
        // absent because the passive monitor relies on an indefinite notification read.
        properties.setProperty("connectTimeout", "15");
        try (DockerSecretLoader.SecretValue secret = DockerSecretLoader.read(database.passwordFile())) {
            properties.setProperty("password", secret.exposeForDriver());
        }
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(database.jdbcUrl(), properties);
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_SQL)) {
                statement.setLong(1, lockKey);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        connection.close();
                        throw SqlStateClassifier.fenced("Another CilExec Runtime owns the database", null);
                    }
                }
            }
            long proofKey = acquireProofLock(connection, lockKey);
            return new ControlLock(connection, lockKey, readIdentity(connection, proofKey));
        } catch (SQLException exception) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            throw SqlStateClassifier.classify("runtime.acquireControlLock", exception);
        }
    }

    public synchronized void monitor(Consumer<Throwable> onFence) {
        Objects.requireNonNull(onFence, "onFence");
        if (!monitoring.compareAndSet(false, true)) {
            throw new IllegalStateException("Control lock is already monitored");
        }
        monitor = Thread.ofVirtual().name("cilexec-control-lock").start(() -> {
            try {
                PGConnection postgres = connection.unwrap(PGConnection.class);
                while (monitoring.get() && held.get()) {
                    // A zero timeout is an indefinite socket wait in pgjdbc. It detects a broken
                    // control session without issuing heartbeat SQL while the Runtime is idle.
                    postgres.getNotifications(0);
                }
            } catch (Throwable failure) {
                if (monitoring.get() && held.get()) fence(failure, onFence);
            }
        });
    }

    public boolean isHeld() {
        return held.get();
    }

    public ControlIdentity identity() {
        return identity;
    }

    private static long acquireProofLock(Connection connection, long controlKey)
            throws SQLException {
        for (int attempt = 0; attempt < 8; attempt++) {
            long proofKey = RANDOM.nextLong();
            if (proofKey == controlKey) continue;
            try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_SQL)) {
                statement.setLong(1, proofKey);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) return proofKey;
                }
            }
        }
        throw new SQLException("Cannot acquire a unique control proof lock", "55P03");
    }

    private static ControlIdentity readIdentity(Connection connection, long proofKey)
            throws SQLException {
        String sql = "SELECT activity.pid,activity.backend_start "
                + "FROM pg_catalog.pg_stat_activity AS activity "
                + "WHERE activity.pid=pg_backend_pid()";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("Control backend identity is unavailable", "08006");
            }
            return new ControlIdentity(result.getInt("pid"),
                    result.getTimestamp("backend_start").toInstant(), proofKey);
        }
    }

    private void fence(Throwable failure, Consumer<Throwable> onFence) {
        if (held.compareAndSet(true, false)) {
            monitoring.set(false);
            onFence.accept(failure);
        }
    }

    @Override
    public synchronized void close() {
        monitoring.set(false);
        held.set(false);
        // Closing this physical session releases both advisory locks atomically and unblocks the
        // passive socket monitor without generating the interrupt-driven JDBC warning.
        try {
            connection.abort(Runnable::run);
        } catch (SQLException ignored) {
            // A subsequent close still handles drivers that do not support abort.
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing can recover this session during shutdown.
        }
        // The monitor is a virtual thread; it exits on its own once held/monitoring are cleared
        // or the aborted session fails its socket read. It is never joined here: a fence that
        // reaches onFence blocks it until shutdown completes, so joining it would deadlock the
        // shutdown thread against the monitor thread for the full join timeout.
        monitor = null;
    }

    /** Stable identity of the PostgreSQL backend holding the singleton lock. */
    public record ControlIdentity(int backendPid, Instant backendStartedAt,
                                  long proofLockKey) {
        public ControlIdentity {
            if (backendPid < 1) throw new IllegalArgumentException("backendPid must be positive");
            Objects.requireNonNull(backendStartedAt, "backendStartedAt");
        }
    }
}
