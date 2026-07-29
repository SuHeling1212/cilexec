package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalCommandHistoryMigrationTest {
    @Test
    void createsUserScopedHistorySeparateFromProcessInput() throws IOException {
        String sql = BaselineSql.load();
            assertTrue(sql.contains("CREATE TABLE terminal.command_history"));
            assertTrue(sql.contains("ENABLE ROW LEVEL SECURITY"));
            assertTrue(sql.contains("FORCE ROW LEVEL SECURITY"));
            assertTrue(sql.contains("owner_id = auth.current_cilexec_user_id()"));
            assertTrue(sql.contains("terminal.command_history TO PUBLIC"));
            assertTrue(sql.contains("'terminal', 'command_history', 'USER_SCOPED'"));
    }
}
