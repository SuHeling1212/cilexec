package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalCommandHistoryMigrationTest {
    @Test
    void createsUserScopedHistorySeparateFromProcessInput() throws IOException {
        String resource = "db/migration/V030__terminal_command_history.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing migration " + resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("CREATE TABLE terminal.command_history"));
            assertTrue(sql.contains("ENABLE ROW LEVEL SECURITY"));
            assertTrue(sql.contains("FORCE ROW LEVEL SECURITY"));
            assertTrue(sql.contains("owner_id = auth.current_cilexec_user_id()"));
            assertTrue(sql.contains("terminal.command_history TO PUBLIC"));
            assertTrue(sql.contains("'terminal', 'command_history', 'USER_SCOPED'"));
        }
    }
}
