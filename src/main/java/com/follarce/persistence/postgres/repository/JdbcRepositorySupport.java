package com.follarce.persistence.postgres.repository;

import com.follarce.persistence.postgres.error.PersistenceFailure;
import com.follarce.persistence.postgres.error.SqlStateClassifier;
import java.sql.Connection;
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
}
