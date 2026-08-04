package com.follarce.persistence.postgres.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.junit.jupiter.api.Test;

class SqlStateClassifierTest {
    @Test
    void mapsStableSqlStatesInOnePlace() {
        assertKind("23505", PersistenceFailure.Kind.UNIQUE_CONFLICT, false);
        assertKind("23503", PersistenceFailure.Kind.REFERENCE_CONFLICT, false);
        assertKind("40001", PersistenceFailure.Kind.SERIALIZATION_CONFLICT, true);
        assertKind("40P01", PersistenceFailure.Kind.DEADLOCK, true);
        assertKind("08006", PersistenceFailure.Kind.DATABASE_UNAVAILABLE, true);
        assertKind("08P01", PersistenceFailure.Kind.GENERAL, false);
        assertKind("57014", PersistenceFailure.Kind.RETRYABLE_TRANSIENT, true);
        assertKind("57P01", PersistenceFailure.Kind.RETRYABLE_TRANSIENT, true);
        assertKind("22000", PersistenceFailure.Kind.GENERAL, false);
    }

    @Test
    void poolAndStatementTimeoutsAreRetryableTransientsNotFatalUnavailability() {
        PersistenceFailure failure = SqlStateClassifier.classify("test",
                new SQLTimeoutException("pool timeout", "57014"));
        assertEquals(PersistenceFailure.Kind.RETRYABLE_TRANSIENT, failure.kind());
        assertTrue(failure.retryable());
    }

    @Test
    void protocolViolationIsNotRetryable() {
        PersistenceFailure failure = SqlStateClassifier.classify("test",
                new SQLException("protocol violation", "08P01"));
        assertEquals(PersistenceFailure.Kind.GENERAL, failure.kind());
        assertFalse(failure.retryable());
    }

    private static void assertKind(String state, PersistenceFailure.Kind expected, boolean retryable) {
        PersistenceFailure failure = SqlStateClassifier.classify("test", new SQLException("boom", state));
        assertEquals(expected, failure.kind());
        if (retryable) {
            assertTrue(failure.retryable());
        } else {
            assertFalse(failure.retryable());
        }
    }
}
