package com.follarce.persistence.postgres.connection;

import com.follarce.config.DatabaseConfig;
import com.follarce.config.DockerSecretLoader;
import com.follarce.persistence.postgres.error.SqlStateClassifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

/** Owns the pool-external session that fences a single active Runtime. */
public final class ControlLock implements AutoCloseable {
    private static final String ACQUIRE_SQL = "SELECT pg_try_advisory_lock(?)";
    private static final String ACQUIRE_PROOF_SQL = "SELECT pg_try_advisory_lock(?)";
    private static final String RELEASE_SQL = "SELECT pg_advisory_unlock(?)";
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
        Properties properties = new Properties();
        properties.setProperty("user", database.username());
        properties.setProperty("ApplicationName", database.applicationName() + "-control");
        properties.setProperty("tcpKeepAlive", "true");
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

    public synchronized void monitor(Duration interval, Consumer<Throwable> onFence) {
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(onFence, "onFence");
        if (!monitoring.compareAndSet(false, true)) {
            throw new IllegalStateException("Control lock is already monitored");
        }
        monitor = Thread.ofVirtual().name("cilexec-control-lock").start(() -> {
            while (monitoring.get() && held.get()) {
                LockSupport.parkNanos(interval.toNanos());
                if (!monitoring.get()) {
                    return;
                }
                try {
                    if (!connection.isValid(2) || !serverStillOwnsLock()) {
                        fence(new IllegalStateException("PostgreSQL advisory lock was lost"), onFence);
                    }
                } catch (Throwable failure) {
                    fence(failure, onFence);
                }
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
            try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_PROOF_SQL)) {
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

    private boolean serverStillOwnsLock() throws SQLException {
        // Both keys must remain on this exact backend. Checking for merely any
        // advisory lock could leave the Runtime alive after its singleton lock
        // was released while the per-boot proof lock was still present.
        return ownsAdvisoryLock(connection, lockKey)
                && ownsAdvisoryLock(connection, identity.proofLockKey());
    }

    private static boolean ownsAdvisoryLock(Connection connection, long key)
            throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks "
                + "WHERE locktype='advisory' AND pid=pg_backend_pid() AND granted "
                + "AND database=(SELECT oid FROM pg_catalog.pg_database "
                + "WHERE datname=current_database()) "
                + "AND classid::bigint=((? >> 32) & 4294967295::bigint) "
                + "AND objid::bigint=(? & 4294967295::bigint) AND objsubid=1)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            statement.setLong(2, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
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
        if (monitor != null) {
            monitor.interrupt();
        }
        if (held.compareAndSet(true, false)) {
            try (PreparedStatement statement = connection.prepareStatement(RELEASE_SQL)) {
                statement.setLong(1, lockKey);
                statement.execute();
            } catch (SQLException ignored) {
                // Closing the physical session also releases the advisory lock.
            }
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing can recover this session during shutdown.
        }
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
