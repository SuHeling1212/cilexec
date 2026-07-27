package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalWorkingDirectoryMigrationTest {
    @Test
    void persistsAnAbsoluteWorkingDirectoryPerTerminalSession() throws IOException {
        String resource = "db/migration/V027__terminal_working_directory.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing migration " + resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ADD COLUMN working_directory text NOT NULL DEFAULT '/'"));
            assertTrue(sql.contains("working_directory LIKE '/%'"));
        }
    }
}
