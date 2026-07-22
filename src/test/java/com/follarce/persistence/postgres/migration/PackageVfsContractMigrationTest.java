package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageVfsContractMigrationTest {
    private static final String MIGRATION =
            "db/migration/V018__package_and_vfs_contract_hardening.sql";

    @Test
    void packageBundleEnforcesTheJavaIndexShapesBeforePublishing() throws IOException {
        String sql = migration();

        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION package.register_release_bundle_as"));
        assertTrue(sql.contains("package_import capability is required"));
        assertTrue(sql.contains("^[A-Za-z_][A-Za-z0-9_.-]{0,127}$"));
        assertTrue(sql.contains("module_object_path !~ '(^|/)[[:space:]]*($|/)'"));
        assertTrue(sql.contains("jsonb_typeof(item->'optional') IS DISTINCT FROM 'boolean'"));
        assertTrue(sql.contains("jsonb_typeof(item->'required') IS DISTINCT FROM 'boolean'"));
        assertTrue(sql.contains("jsonb_typeof(item->'rationale') IS DISTINCT FROM 'string'"));
        assertTrue(sql.contains("ck_package_release_entrypoint_domain"));
        assertTrue(sql.contains("ck_package_release_export_domain"));
        assertTrue(sql.contains("ck_package_release_capability_domain"));
    }

    @Test
    void revisionIdentityAndSequenceAreAssignedBehindAControlledFunction() throws IOException {
        String sql = migration();

        assertTrue(sql.contains("CREATE FUNCTION vfs.append_file_revision_as"));
        assertTrue(sql.contains("auth.resolve_cilexec_user_id(p_database_role, p_claim)"));
        assertTrue(sql.contains("capability.capability_key = 'vfs_write'"));
        assertTrue(sql.contains("max(revision.revision_number), 0) + 1"));
        assertTrue(sql.contains("p_object_hash, actor, clock_timestamp()"));
        assertTrue(sql.contains("REVOKE INSERT ON vfs.file_revision"));
        assertFalse(sql.contains("GRANT SELECT, INSERT ON vfs.file_revision TO %I"));
    }

    @Test
    void mountsAreReadOnlyCanonicalAndBoundToMountNodes() throws IOException {
        String sql = migration();

        assertTrue(sql.contains("ck_vfs_mount_read_only CHECK (read_only)"));
        assertTrue(sql.contains("ck_vfs_mount_container_path_domain"));
        assertTrue(sql.contains("container_path !~ '(^|/)[[:space:]]*($|/)'"));
        assertTrue(sql.contains("node.node_type = 'MOUNT'"));
        assertTrue(sql.contains("CREATE TRIGGER mount_enforce_node_type"));
        assertTrue(sql.contains("CREATE TRIGGER node_preserve_mounted_type"));
    }

    private static String migration() throws IOException {
        ClassLoader loader = PackageVfsContractMigrationTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(MIGRATION)) {
            if (input == null) throw new IOException("Missing migration " + MIGRATION);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
