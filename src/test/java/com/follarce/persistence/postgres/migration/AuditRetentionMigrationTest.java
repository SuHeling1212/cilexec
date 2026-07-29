package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRetentionMigrationTest {
    @Test
    void detailsJsonIsAStringValuedObjectAtTheDatabaseBoundary() throws IOException {
        String sql = migration();

        assertTrue(sql.contains("ck_audit_event_details_string_object"));
        assertTrue(sql.contains("jsonb_typeof(details_json) = 'object'"));
        assertTrue(sql.contains("@.type() != \"string\""));
        assertTrue(sql.contains("ck_audit_retention_fixed_whole_seconds"));
    }

    @Test
    void purgeIsBoundedUsesDatabaseTimeAndDoesNotGrantTableDelete() throws IOException {
        String sql = migration();

        assertTrue(sql.contains("CREATE FUNCTION audit.purge_expired_events(p_limit integer)"));
        assertTrue(sql.contains("SECURITY DEFINER"));
        assertTrue(sql.contains("purge_at := clock_timestamp()"));
        assertTrue(sql.contains("p_limit > 10000"));
        assertTrue(sql.contains("FOR UPDATE OF candidate SKIP LOCKED"));
        assertTrue(sql.contains("LIMIT p_limit"));
        assertTrue(sql.contains("REVOKE UPDATE, DELETE ON audit.event"));
        assertTrue(sql.contains("GRANT EXECUTE ON FUNCTION audit.purge_expired_events(integer)"));
        assertFalse(sql.contains("p_now"));
        assertFalse(sql.contains("GRANT DELETE ON audit.event"));
    }

    private static String migration() throws IOException {
        return BaselineSql.load();
    }
}
