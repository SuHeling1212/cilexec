package com.follarce.persistence.postgres.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaVerifierTest {
    @Test
    void rejectsServersWithoutSecurityCorrectSetRoleBehavior() {
        assertThrows(IllegalStateException.class,
                () -> SchemaVerifier.requireSecurePostgresql(170000));
        assertDoesNotThrow(() -> SchemaVerifier.requireSecurePostgresql(170001));
        assertDoesNotThrow(() -> SchemaVerifier.requireSecurePostgresql(180000));
    }
}
