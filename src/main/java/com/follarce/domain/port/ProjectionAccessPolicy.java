package com.follarce.domain.port;

import java.util.Map;
import java.util.Set;

/** Reviewed projection surface. New tables and columns fail closed. */
public final class ProjectionAccessPolicy {
    private static final Map<String, Set<String>> READ_COLUMNS = Map.ofEntries(
            Map.entry("meta.table_security_classification", Set.of("schema_name", "table_name",
                    "classification", "owner_column", "exportable", "rationale", "registered_at")),
            Map.entry("meta.security_definer_public_allowlist", Set.of("function_signature", "rationale")),
            Map.entry("auth.user_account", Set.of("user_id", "username", "postgres_role_name",
                    "status", "credential_version", "created_at", "updated_at", "disabled_at")),
            Map.entry("auth.group_account", Set.of("group_id", "owner_id", "group_name",
                    "status", "created_at", "updated_at")),
            Map.entry("auth.group_member", Set.of("group_id", "owner_id", "member_user_id",
                    "granted_by", "created_at")),
            Map.entry("auth.capability", Set.of("capability_id", "capability_key", "description",
                    "system_capability", "created_at")),
            Map.entry("auth.user_capability", Set.of("user_id", "owner_id", "capability_id",
                    "granted_by", "expires_at", "created_at")),
            Map.entry("auth.group_capability", Set.of("group_id", "owner_id", "capability_id",
                    "granted_by", "expires_at", "created_at")),
            Map.entry("vfs.node", Set.of("node_id", "owner_id", "parent_node_id", "node_name",
                    "node_type", "created_at", "updated_at")),
            Map.entry("audit.retention_policy", Set.of("event_type", "retain_for", "enabled",
                    "updated_at")));
    private static final Map<String, Set<String>> UPDATABLE = Map.of(
            "auth.group_account", Set.of("group_name"),
            "auth.capability", Set.of("description"));

    private ProjectionAccessPolicy() { }

    public static boolean canRead(String schema, String table) {
        return READ_COLUMNS.containsKey(schema + "." + table);
    }

    public static Set<String> readColumns(String schema, String table) {
        return READ_COLUMNS.getOrDefault(schema + "." + table, Set.of());
    }

    public static Set<String> updateColumns(String schema, String table) {
        return UPDATABLE.getOrDefault(schema + "." + table, Set.of());
    }

    public static boolean canCreate(String schema, String table) {
        return false;
    }

    public static boolean canDelete(String schema, String table) {
        return false;
    }
}
