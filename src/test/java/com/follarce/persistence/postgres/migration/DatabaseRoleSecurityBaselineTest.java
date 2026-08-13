package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseRoleSecurityBaselineTest {
    @Test
    void baselineSeparatesExporterCredentialsAndReadonlyDiagnostics() throws IOException {
        String sql = BaselineSql.load();
        String bootstrap = Files.readString(
                Path.of("docker/postgres/init/00-cilexec-bootstrap.sh"));

        assertTrue(bootstrap.contains("CREATE ROLE cilexec_exporter LOGIN NOINHERIT"));
        assertTrue(bootstrap.contains("CILEXEC_EXPORTER_PASSWORD_FILE"));
        assertTrue(bootstrap.contains("cilexec_exporter SET default_transaction_read_only TO on"));
        assertTrue(sql.contains("exportable boolean NOT NULL DEFAULT false"));
        assertTrue(sql.contains("CREATE POLICY cilexec_exporter_read"));
        assertTrue(sql.contains("REVOKE ALL ON auth.user_credential FROM cilexec_readonly, cilexec_exporter"));
        assertTrue(sql.contains("CREATE VIEW diagnostic.account_status"));
        assertFalse(sql.contains("GRANT SELECT ON ALL TABLES IN SCHEMA auth TO cilexec_readonly"));
        assertFalse(sql.contains("user_account_readonly_control"));
    }

    @Test
    void finalInvariantChecksOwnersRolesPoliciesAndDefinerAcls() throws IOException {
        String sql = BaselineSql.load();

        assertTrue(sql.contains("meta.security_definer_public_allowlist"));
        assertTrue(sql.contains("CilExec schema or relation has an unexpected owner"));
        assertTrue(sql.contains("CilExec owner-role membership is invalid"));
        assertTrue(sql.contains("SECURITY DEFINER PUBLIC EXECUTE ACL is outside the reviewed allowlist"));
        assertTrue(sql.contains("exportable user table %.% lacks its exporter SELECT policy"));
        assertTrue(sql.contains("readonly has a direct RLS policy on %.%"));
    }

    @Test
    void effectWorkerCanInspectRunnerLivenessWithoutSchedulerWriteAccess() throws IOException {
        String sql = BaselineSql.load();

        assertTrue(sql.contains("GRANT USAGE ON SCHEMA meta, scheduler, effect, process, audit "
                + "TO cilexec_effect_worker"));
        assertTrue(sql.contains("GRANT SELECT ON scheduler.runner TO cilexec_effect_worker"));
        assertFalse(sql.contains("GRANT SELECT, INSERT, UPDATE, DELETE ON scheduler.runner "
                + "TO cilexec_effect_worker"));
    }
}
