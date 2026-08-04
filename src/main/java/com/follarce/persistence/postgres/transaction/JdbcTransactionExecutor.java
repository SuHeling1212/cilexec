package com.follarce.persistence.postgres.transaction;

import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.port.TransactionWork;
import com.follarce.persistence.postgres.error.PersistenceFailure;
import com.follarce.persistence.postgres.error.SqlStateClassifier;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;

/**
 * Explicit outer transaction with bounded retry only for replay-safe database conflicts.
 * Work lambdas must be replay-safe: a serialization conflict or deadlock replays the entire
 * work lambda, so work must not perform side effects outside the database.
 */
public final class JdbcTransactionExecutor implements TransactionExecutor, UserTransactionExecutor {
    private static final int MAX_CONFLICT_ATTEMPTS = 3;
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(JdbcTransactionExecutor.class);
    private final DataSource dataSource;
    private final JsonCodec json = new JsonCodec();

    public JdbcTransactionExecutor(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> T inTransaction(Isolation isolation, TransactionWork<T> work) {
        return execute(isolation, null, work);
    }

    @Override
    public <T> T inUserTransaction(UUID userId, Isolation isolation, TransactionWork<T> work) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        return execute(isolation, userId, work);
    }

    private <T> T execute(Isolation isolation, UUID userId, TransactionWork<T> work) {
        for (int attempt = 1; attempt <= MAX_CONFLICT_ATTEMPTS; attempt++) {
            Connection connection;
            try {
                connection = dataSource.getConnection();
            } catch (SQLException exception) {
                throw SqlStateClassifier.classify("transaction.acquire", exception);
            }
            try (Connection ignored = connection) {
                try {
                    prepare(connection, isolation);
                    if (userId != null) {
                        applyUserIdentity(connection, userId);
                    }
                } catch (SQLException exception) {
                    throw SqlStateClassifier.classify("transaction.begin", exception);
                }
                try (JdbcTransactionContext transaction = new JdbcTransactionContext(connection, json)) {
                    T result = work.execute(transaction);
                    if (!transaction.isOpen()) {
                        throw new IllegalStateException("Transaction work must not finish its own transaction");
                    }
                    transaction.commit();
                    return result;
                } catch (Throwable failure) {
                    rollbackQuietly(connection);
                    throw failure;
                }
            } catch (PersistenceFailure failure) {
                if (isConflict(failure) && attempt < MAX_CONFLICT_ATTEMPTS) {
                    LOG.warn("Replaying transaction work after {} (attempt {}/{})",
                            failure.kind(), attempt, MAX_CONFLICT_ATTEMPTS);
                    jitter(attempt);
                    continue;
                }
                throw failure;
            } catch (SQLException exception) {
                PersistenceFailure failure = SqlStateClassifier.classify("transaction.work", exception);
                if (isConflict(failure) && attempt < MAX_CONFLICT_ATTEMPTS) {
                    LOG.warn("Replaying transaction work after {} (attempt {}/{})",
                            failure.kind(), attempt, MAX_CONFLICT_ATTEMPTS);
                    jitter(attempt);
                    continue;
                }
                throw failure;
            }
        }
        throw new AssertionError("bounded transaction loop did not terminate");
    }

    private static void prepare(Connection connection, Isolation isolation) throws SQLException {
        connection.setAutoCommit(false);
        connection.setReadOnly(false);
        connection.setTransactionIsolation(switch (isolation) {
            case READ_COMMITTED -> Connection.TRANSACTION_READ_COMMITTED;
            case SERIALIZABLE -> Connection.TRANSACTION_SERIALIZABLE;
        });
    }

    private static void applyUserIdentity(Connection connection, UUID userId) throws SQLException {
        String role = UserAccount.roleNameFor(userId);
        if (!role.matches("cilexec_user_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid stable user Role name");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE " + role);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.cilexec_user_id', ?, true)")) {
            statement.setString(1, userId.toString());
            statement.execute();
        }
    }

    private static boolean isConflict(PersistenceFailure failure) {
        return failure.kind() == PersistenceFailure.Kind.SERIALIZATION_CONFLICT
                || failure.kind() == PersistenceFailure.Kind.DEADLOCK;
    }

    private static void jitter(int attempt) {
        long ceilingMillis = 10L << (attempt - 1);
        long delayMillis = ThreadLocalRandom.current().nextLong(1, ceilingMillis + 1);
        LockSupport.parkNanos(java.time.Duration.ofMillis(delayMillis).toNanos());
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Interrupted while retrying database transaction");
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure remains the useful signal; the connection will be discarded.
        }
    }
}
