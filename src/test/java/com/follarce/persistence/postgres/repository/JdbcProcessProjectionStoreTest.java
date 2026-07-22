package com.follarce.persistence.postgres.repository;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class JdbcProcessProjectionStoreTest {
    @Test
    void normalizedIdsAreStableWithinOneProcessAndIsolatedAcrossProcesses() {
        UUID domainFrame = UUID.randomUUID();
        UUID domainScope = UUID.randomUUID();
        UUID firstProcess = UUID.randomUUID();
        UUID secondProcess = UUID.randomUUID();

        assertEquals(JdbcProcessProjectionStore.databaseFrameId(firstProcess, domainFrame),
                JdbcProcessProjectionStore.databaseFrameId(firstProcess, domainFrame));
        assertNotEquals(JdbcProcessProjectionStore.databaseFrameId(firstProcess, domainFrame),
                JdbcProcessProjectionStore.databaseFrameId(secondProcess, domainFrame));
        assertNotEquals(JdbcProcessProjectionStore.databaseScopeId(firstProcess, domainScope),
                JdbcProcessProjectionStore.databaseScopeId(secondProcess, domainScope));
        assertNotEquals(JdbcProcessProjectionStore.rootFrameId(firstProcess),
                JdbcProcessProjectionStore.rootFrameId(secondProcess));
    }
}
