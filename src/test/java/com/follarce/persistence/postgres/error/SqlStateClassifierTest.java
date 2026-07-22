package com.follarce.persistence.postgres.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SqlStateClassifierTest {
    @Test
    void mapsStableSqlStatesInOnePlace() {
        assertKind("23505", PersistenceFailure.Kind.UNIQUE_CONFLICT, false);
        assertKind("23503", PersistenceFailure.Kind.REFERENCE_CONFLICT, false);
        assertKind("40001", PersistenceFailure.Kind.SERIALIZATION_CONFLICT, true);
        assertKind("40P01", PersistenceFailure.Kind.DEADLOCK, true);
        assertKind("08006", PersistenceFailure.Kind.DATABASE_UNAVAILABLE, true);
        assertKind("22000", PersistenceFailure.Kind.GENERAL, false);
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
