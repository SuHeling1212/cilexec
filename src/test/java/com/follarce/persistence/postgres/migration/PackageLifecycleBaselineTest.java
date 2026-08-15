package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageLifecycleBaselineTest {
    @Test
    void baselineAddsThePerUserInstallationLedger() throws IOException {
        String sql = baseline();

        assertTrue(sql.contains("CREATE TABLE package.release_identity"));
        assertTrue(sql.contains("CREATE TABLE package.installation_root"));
        assertTrue(sql.contains("CREATE TABLE package.installation_member"));
        assertTrue(sql.contains("UNIQUE (owner_id, root_package_hash, source)"));
        assertTrue(sql.contains("PRIMARY KEY (installation_id, package_hash)"));
        assertTrue(sql.contains("UNIQUE (database_file_hash)"));
    }

    @Test
    void baselineAddsPrivateDataSpacesWithQuotas() throws IOException {
        String sql = baseline();

        assertTrue(sql.contains("CREATE TABLE package.data_space"));
        assertTrue(sql.contains("CREATE TABLE package.data_entry"));
        assertTrue(sql.contains("CREATE TABLE package.data_policy"));
        assertTrue(sql.contains("CREATE TABLE package.data_quota_override"));
        assertTrue(sql.contains("UNIQUE (owner_id, package_hash)"));
        assertTrue(sql.contains("entry_type IN ('FILE', 'DIRECTORY')"));
        assertTrue(sql.contains("logical_bytes bigint"));
        assertTrue(sql.contains("default_quota_bytes bigint"));
    }

    @Test
    void baselineTracksManagedNodesAndCountsThemOnUninstall() throws IOException {
        String sql = baseline();

        assertTrue(sql.contains("CREATE TABLE package.managed_node"));
        assertTrue(sql.contains("purpose IN ('MARKET_CACHE', 'PACKAGE_DATA')"));
        assertTrue(sql.contains("CREATE FUNCTION package.register_managed_node_as"));
        assertTrue(sql.contains("doomed_nodes"));
        assertTrue(sql.contains("deleted_nodes"));
        assertTrue(sql.contains("SELECT count(*) INTO cache_files_removed FROM deleted_nodes"));
        assertTrue(sql.contains("'cacheFilesRemoved', cache_files_removed"));
    }

    @Test
    void baselineCarriesATestOnlyFaultInjectionHook() throws IOException {
        String sql = baseline();

        assertTrue(sql.contains("app.cilexec_test_fail"));
        assertTrue(sql.contains("injected uninstall failure"));
    }

    @Test
    void baselineProvidesAnAdministratorRecoveryReport() throws IOException {
        String sql = baseline();

        assertTrue(sql.contains("CREATE FUNCTION package.recover_report_as"));
        assertTrue(sql.contains("data_usage_mismatch"));
        assertTrue(sql.contains("installation_missing_release"));
        assertTrue(sql.contains("binding_without_installation"));
        assertTrue(sql.contains("space_without_installation"));
        assertTrue(sql.contains("auth.require_system_administrator_as"));
    }

    @Test
    void baselineEnforcesForcedRlsAndSecurityDefinerEntryPoints() throws IOException {
        String sql = baseline();

        assertTrue(sql.contains("'installation_root', 'installation_member'"));
        assertTrue(sql.contains("'data_space', 'data_quota_override'"));
        assertTrue(sql.contains("data_entry_principal ON package.data_entry"));
        assertTrue(sql.contains("ENABLE ROW LEVEL SECURITY"));
        assertTrue(sql.contains("FORCE ROW LEVEL SECURITY"));
        assertTrue(sql.contains("CREATE FUNCTION package.publish_installation_as"));
        assertTrue(sql.contains("CREATE FUNCTION package.uninstall_package_as"));
        assertTrue(sql.contains("CREATE FUNCTION package.data_write_as"));
        assertTrue(sql.contains("CREATE FUNCTION package.data_read_as"));
        assertTrue(sql.contains("CREATE FUNCTION package.set_data_quota_as"));
        assertTrue(sql.contains("auth.resolve_cilexec_user_id(p_database_role, p_claim)"));
        assertTrue(sql.contains("app.cilexec_gc"));
    }

    @Test
    void baselineUninstallPurgesProcessesDataAndUnreferencedPayloads() throws IOException {
        String sql = baseline();

        assertTrue(sql.contains("clear_effects"));
        assertTrue(sql.contains("clear_locks"));
        assertTrue(sql.contains("clear_queue"));
        assertTrue(sql.contains("clear_leases"));
        assertTrue(sql.contains("deleted_entries"));
        assertTrue(sql.contains("deleted_roots"));
        assertTrue(sql.contains("gced_releases"));
        assertTrue(sql.contains("gced_objects"));
        assertTrue(sql.contains("p_force"));
        assertTrue(sql.contains("p_caller_process_uid"));
    }

    @Test
    void baselineMakesLifecycleStateExportableAndDefinesReleaseOnce() throws IOException {
        String sql = baseline();

        assertTrue(sql.contains("SET exportable = true"));
        assertTrue(sql.contains("('package', 'installation_root')"));
        assertTrue(sql.contains("('package', 'data_entry')"));
        assertTrue(sql.contains("('package', 'release_identity')"));
        assertTrue(sql.contains("('package', 'managed_node')"));
        assertEquals(1, countOccurrences(sql, "CREATE TABLE package.release ("),
                "the release table must be defined exactly once");
    }

    @Test
    void baselineGrantsLifecycleAccessToProvisionedUserRoles() throws IOException {
        String sql = baseline();

        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION auth.grant_login_role_access"));
        assertTrue(sql.contains("package.publish_installation(uuid, bytea, text, jsonb, timestamptz)"));
        assertTrue(sql.contains("package.uninstall_package(bytea, boolean, uuid)"));
        assertTrue(sql.contains("package.data_write(bytea, text, bytea, text, bigint)"));
        assertTrue(sql.contains("package.installation_root, package.installation_member"));
        assertTrue(sql.contains("package.data_space, package.data_entry"));
        assertTrue(sql.contains("package.register_managed_node(uuid, bytea, text)"));
        assertTrue(sql.contains("package.recover_report()"));
        assertTrue(sql.contains("package.managed_node"));
    }

    private static String baseline() throws IOException {
        return BaselineSql.load();
    }

    private static long countOccurrences(String source, String needle) {
        long count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + "; expected " + expected + " but was " + actual);
        }
    }
}
