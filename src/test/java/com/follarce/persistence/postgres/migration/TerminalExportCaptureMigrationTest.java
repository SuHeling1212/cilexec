package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TerminalExportCaptureMigrationTest {
    @Test
    void createsUserScopedTemporaryExportCapture() throws IOException {
        String sql = BaselineSql.load();
            assertTrue(sql.contains("CREATE TABLE terminal.export_capture"));
            assertTrue(sql.contains("CREATE TABLE terminal.export_capture_operation"));
            assertFalse(sql.contains("terminal.operation_log"));
            assertTrue(sql.contains("ENABLE ROW LEVEL SECURITY"));
            assertTrue(sql.contains("owner_id = auth.current_cilexec_user_id()"));
            assertTrue(sql.contains("ON DELETE CASCADE"));
            assertTrue(sql.contains("'terminal', 'export_capture', 'USER_SCOPED'"));
    }
}
