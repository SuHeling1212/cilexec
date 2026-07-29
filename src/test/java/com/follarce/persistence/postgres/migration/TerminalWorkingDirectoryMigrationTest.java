package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalWorkingDirectoryMigrationTest {
    @Test
    void persistsAnAbsoluteWorkingDirectoryPerTerminalSession() throws IOException {
        String sql = BaselineSql.load();
            assertTrue(sql.contains("ADD COLUMN working_directory text NOT NULL DEFAULT '/'"));
            assertTrue(sql.contains("working_directory LIKE '/%'"));
    }
}
