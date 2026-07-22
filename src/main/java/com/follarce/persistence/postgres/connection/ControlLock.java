package com.follarce.persistence.postgres.connection;

import com.follarce.config.DatabaseConfig;
import com.follarce.config.DockerSecretLoader;
import com.follarce.persistence.postgres.error.SqlStateClassifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

/** Owns the pool-external session that fences a single active Runtime. */
public final class ControlLock implements AutoCloseable {
    private static final String ACQUIRE_SQL = "SELECT pg_try_advisory_lock(?)";
    private static final String VERIFY_SQL = "SELECT pg_advisory_lock_held(?)";
    private static final String RELEASE_SQL = "SELECT pg_advisory_unlock(?)";

    private final Connection connection;
    private final long lockKey;
    private final AtomicBoolean held = new AtomicBoolean(true);
    private final AtomicBoolean monitoring = new AtomicBoolean();
    private Thread monitor;

    private ControlLock(Connection connection, long lockKey) {
        this.connection = connection;
        this.lockKey = lockKey;
    }

    public static ControlLock acquire(DatabaseConfig database, long lockKey) {
        Properties properties = new Properties();
        properties.setProperty("user", database.username());
        properties.setProperty("ApplicationName", database.applicationName() + "-control");
        properties.setProperty("tcpKeepAlive", "true");
        try (DockerSecretLoader.SecretValue secret = DockerSecretLoader.read(database.passwordFile())) {
            properties.setProperty("password", secret.exposeForDriver());
        }
        try {
            Connection connection = DriverManager.getConnection(database.jdbcUrl(), properties);
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
            return new ControlLock(connection, lockKey);
        } catch (SQLException exception) {
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

    private boolean serverStillOwnsLock() throws SQLException {
        // pg_locks is checked in-session because advisory locks are scoped to this connection.
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_locks "
                + "WHERE locktype='advisory' AND pid=pg_backend_pid() AND granted)";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.next() && result.getBoolean(1);
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
}
