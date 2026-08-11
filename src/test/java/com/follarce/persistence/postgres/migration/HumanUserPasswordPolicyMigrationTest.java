package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanUserPasswordPolicyMigrationTest {
    @Test
    void requiresSixCharacterHumanPasswords() throws IOException {
        String sql = BaselineSql.load();
            assertTrue(sql.contains("CREATE OR REPLACE FUNCTION auth.provision_login_role"));
            assertTrue(sql.contains("CREATE OR REPLACE FUNCTION auth.admin_create_user_as"));
            assertTrue(sql.contains("length(p_password) < 6"));
            assertTrue(sql.contains("SELECT meta.assert_security_invariants()"));
    }
}
