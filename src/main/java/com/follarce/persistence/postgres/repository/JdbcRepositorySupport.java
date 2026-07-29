package com.follarce.persistence.postgres.repository;

import com.follarce.persistence.postgres.error.PersistenceFailure;
import com.follarce.persistence.postgres.error.SqlStateClassifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

abstract class JdbcRepositorySupport {
    protected final Connection connection;

    protected JdbcRepositorySupport(Connection connection) {
        this.connection = connection;
    }

    protected PersistenceFailure failure(String operation, SQLException exception) {
        return SqlStateClassifier.classify(operation, exception);
    }

    protected static void requireOne(String operation, int affected) {
        if (affected != 1) {
            throw SqlStateClassifier.optimisticConflict(operation);
        }
    }

    /** PostgreSQL delivers pg_notify only after this transaction commits. */
    protected void notifyWork(String channel, String operation) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_notify(?, '')")) {
            statement.setString(1, channel);
            statement.execute();
        } catch (SQLException exception) {
            throw failure(operation, exception);
        }
    }
}
