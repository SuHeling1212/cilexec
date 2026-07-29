package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkedFileMigrationTest {
    @Test
    void registersImmutableChunkStorageAndKeepsReadsBounded() throws IOException {
        String sql = BaselineSql.load();
            assertTrue(sql.contains("CREATE TABLE object_store.chunk_manifest"));
            assertTrue(sql.contains("'object_store', 'chunk_manifest', 'SHARED_IMMUTABLE'"));
            assertTrue(sql.contains("p_maximum > 67108864"));
            assertTrue(sql.contains("IF p_offset > 2147483646"));
            assertTrue(sql.contains("SELECT meta.assert_security_invariants()"));
    }

    @Test
    void narrowsRangeReadsInANewMigrationWithoutRewritingTheAppliedSchemaHistory()
            throws IOException {
        String sql = BaselineSql.load();
            assertTrue(sql.contains("CREATE OR REPLACE FUNCTION object_store.read_object_range_as"));
            assertTrue(sql.contains("p_maximum > 4194304"));
            assertTrue(sql.contains("SELECT meta.assert_security_invariants()"));
    }
}
