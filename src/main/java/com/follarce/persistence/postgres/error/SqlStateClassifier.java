package com.follarce.persistence.postgres.error;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

/** The sole SQLSTATE-to-domain error mapping in the PostgreSQL adapter. */
public final class SqlStateClassifier {
    private SqlStateClassifier() {
    }

    public static PersistenceFailure classify(String operation, SQLException exception) {
        String state = deepestState(exception);
        if ("23505".equals(state)) {
            return failure(PersistenceFailure.Kind.UNIQUE_CONFLICT, false, operation, exception);
        }
        if ("23503".equals(state)) {
            return failure(PersistenceFailure.Kind.REFERENCE_CONFLICT, false, operation, exception);
        }
        if ("40001".equals(state)) {
            return failure(PersistenceFailure.Kind.SERIALIZATION_CONFLICT, true, operation, exception);
        }
        if ("40P01".equals(state)) {
            return failure(PersistenceFailure.Kind.DEADLOCK, true, operation, exception);
        }
        if (exception instanceof SQLTimeoutException
                || "57014".equals(state)
                || "57P01".equals(state)) {
            return failure(PersistenceFailure.Kind.RETRYABLE_TRANSIENT, true, operation, exception);
        }
        if ("08P01".equals(state)) {
            return failure(PersistenceFailure.Kind.GENERAL, false, operation, exception);
        }
        if ((state != null && state.startsWith("08"))
                || exception instanceof SQLTransientConnectionException
                || exception instanceof SQLNonTransientConnectionException
                || exception instanceof SQLRecoverableException) {
            return failure(PersistenceFailure.Kind.DATABASE_UNAVAILABLE, true, operation, exception);
        }
        return failure(PersistenceFailure.Kind.GENERAL, false, operation, exception);
    }

    public static PersistenceFailure optimisticConflict(String operation) {
        return new PersistenceFailure(PersistenceFailure.Kind.OPTIMISTIC_CONFLICT, true,
                operation + " affected no current row", null);
    }

    public static PersistenceFailure fenced(String message, Throwable cause) {
        return new PersistenceFailure(PersistenceFailure.Kind.RUNTIME_FENCED, false, message, cause);
    }

    private static PersistenceFailure failure(PersistenceFailure.Kind kind, boolean retryable,
                                              String operation, SQLException exception) {
        return new PersistenceFailure(kind, retryable,
                operation + " failed [SQLSTATE=" + deepestState(exception) + "]", exception);
    }

    private static String deepestState(SQLException exception) {
        String state = exception.getSQLState();
        SQLException next = exception.getNextException();
        while (state == null && next != null) {
            state = next.getSQLState();
            next = next.getNextException();
        }
        return state;
    }
}
