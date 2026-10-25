package com.follarce.persistence.postgres.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Docker-free decision tests for the 0.0.5 database-format fence. */
class DatabaseFormatFenceTest {
    private static final int SCHEMA = 5;

    @Test
    void emptyDatabaseWithoutHistoryIsAFreshInitialization() {
        DatabaseFormatFence.Inspection inspection =
                new DatabaseFormatFence.Inspection(false, null, false, false);

        assertEquals(DatabaseFormatFence.Decision.FRESH,
                DatabaseFormatFence.classify(inspection, SCHEMA, SCHEMA));
        assertDoesNotThrow(() -> DatabaseFormatFence.requireMigratable(inspection, SCHEMA));
    }

    @Test
    void currentSchemaVersionAllowsMigration() {
        DatabaseFormatFence.Inspection inspection =
                new DatabaseFormatFence.Inspection(true, SCHEMA, false, false);

        assertEquals(DatabaseFormatFence.Decision.CURRENT,
                DatabaseFormatFence.classify(inspection, SCHEMA, SCHEMA));
        assertDoesNotThrow(() -> DatabaseFormatFence.requireMigratable(inspection, SCHEMA));
    }

    @Test
    void versionFourIsRefusedAsOldDataAndNeverMigrated() {
        DatabaseFormatFence.Inspection inspection =
                new DatabaseFormatFence.Inspection(true, 4, false, false);

        assertEquals(DatabaseFormatFence.Decision.LEGACY,
                DatabaseFormatFence.classify(inspection, SCHEMA, SCHEMA));
        DatabaseFormatFence.RefusalException refusal = assertThrows(
                DatabaseFormatFence.RefusalException.class,
                () -> DatabaseFormatFence.requireMigratable(inspection, SCHEMA));
        assertTrue(refusal.getMessage().contains("CilExec 0.0.5"));
        assertTrue(refusal.getMessage().contains("schema 4"));
        assertTrue(refusal.getMessage().contains("neither migrated nor deleted"));
        assertTrue(refusal.getMessage().contains("Use a fresh database"));
    }

    @Test
    void persistentDataWithoutCilExecHistoryIsUnknownAndRefused() {
        DatabaseFormatFence.Inspection inspection =
                new DatabaseFormatFence.Inspection(false, null, false, true);

        assertEquals(DatabaseFormatFence.Decision.UNKNOWN,
                DatabaseFormatFence.classify(inspection, SCHEMA, SCHEMA));
        DatabaseFormatFence.RefusalException refusal = assertThrows(
                DatabaseFormatFence.RefusalException.class,
                () -> DatabaseFormatFence.requireMigratable(inspection, SCHEMA));
        assertTrue(refusal.getMessage().contains("no CilExec migration history"));
        assertTrue(refusal.getMessage().contains("neither migrated nor deleted"));
    }

    @Test
    void newerDatabaseForbidsDowngrades() {
        DatabaseFormatFence.Inspection inspection =
                new DatabaseFormatFence.Inspection(true, 6, false, false);

        assertEquals(DatabaseFormatFence.Decision.FUTURE,
                DatabaseFormatFence.classify(inspection, SCHEMA, SCHEMA));
        DatabaseFormatFence.RefusalException refusal = assertThrows(
                DatabaseFormatFence.RefusalException.class,
                () -> DatabaseFormatFence.requireMigratable(inspection, SCHEMA));
        assertTrue(refusal.getMessage().contains("newer than CilExec 0.0.5"));
        assertTrue(refusal.getMessage().contains("downgrades are not supported"));
    }

    @Test
    void failedMigrationHistoryIsRefused() {
        DatabaseFormatFence.Inspection failed =
                new DatabaseFormatFence.Inspection(true, 4, true, false);
        DatabaseFormatFence.Inspection noSuccessful =
                new DatabaseFormatFence.Inspection(true, null, false, false);

        assertEquals(DatabaseFormatFence.Decision.FAILED,
                DatabaseFormatFence.classify(failed, SCHEMA, SCHEMA));
        assertEquals(DatabaseFormatFence.Decision.FAILED,
                DatabaseFormatFence.classify(noSuccessful, SCHEMA, SCHEMA));
        assertThrows(DatabaseFormatFence.RefusalException.class,
                () -> DatabaseFormatFence.requireMigratable(failed, SCHEMA));
        assertThrows(DatabaseFormatFence.RefusalException.class,
                () -> DatabaseFormatFence.requireMigratable(noSuccessful, SCHEMA));
    }

    @Test
    void supportedRangeKeepsStartupSemanticsForOlderAndNewerReleases() {
        DatabaseFormatFence.Inspection versionFour =
                new DatabaseFormatFence.Inspection(true, 4, false, false);
        DatabaseFormatFence.Inspection versionSix =
                new DatabaseFormatFence.Inspection(true, 6, false, false);

        assertEquals(DatabaseFormatFence.Decision.CURRENT,
                DatabaseFormatFence.classify(versionFour, 4, 6));
        assertEquals(DatabaseFormatFence.Decision.CURRENT,
                DatabaseFormatFence.classify(versionSix, 4, 6));
        assertEquals(DatabaseFormatFence.Decision.LEGACY,
                DatabaseFormatFence.classify(versionFour, 5, 6));
        assertEquals(DatabaseFormatFence.Decision.FUTURE,
                DatabaseFormatFence.classify(versionSix, 4, 5));
    }

    @Test
    void rejectsInconsistentSnapshotsAndRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseFormatFence.Inspection(false, SCHEMA, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseFormatFence.classify(
                        new DatabaseFormatFence.Inspection(false, null, false, false), 6, 5));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseFormatFence.refusalMessage(DatabaseFormatFence.Decision.CURRENT,
                        new DatabaseFormatFence.Inspection(true, SCHEMA, false, false),
                        SCHEMA, SCHEMA));
    }

    @Test
    void emptyDatabaseWithoutMigrationExplainsHowToInitialize() {
        String message = DatabaseFormatFence.missingMigrationMessage(SCHEMA);

        assertTrue(message.contains("no successful CilExec 0.0.5 migration"));
        assertTrue(message.contains("migrate"));
        assertTrue(message.contains("database.migrate-on-start"));
    }
}
