package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanUserPasswordPolicyMigrationTest {
    @Test
    void lowersOnlyHumanPrincipalCreationToEightCharacters() throws IOException {
        String resource = "db/migration/V026__human_user_password_policy.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing migration " + resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("CREATE OR REPLACE FUNCTION auth.provision_login_role"));
            assertTrue(sql.contains("CREATE OR REPLACE FUNCTION auth.admin_create_user_as"));
            assertTrue(sql.contains("length(p_password) < 8"));
            assertTrue(sql.contains("SELECT meta.assert_security_invariants()"));
        }
    }
}
