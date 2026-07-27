package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkedFileMigrationTest {
    @Test
    void registersImmutableChunkStorageAndKeepsReadsBounded() throws IOException {
        String resource = "db/migration/V028__chunked_file_objects.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing migration " + resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("CREATE TABLE object_store.chunk_manifest"));
            assertTrue(sql.contains("'object_store', 'chunk_manifest', 'SHARED_IMMUTABLE'"));
            assertTrue(sql.contains("p_maximum > 67108864"));
            assertTrue(sql.contains("IF p_offset > 2147483646"));
            assertTrue(sql.contains("SELECT meta.assert_security_invariants()"));
        }
    }
}
