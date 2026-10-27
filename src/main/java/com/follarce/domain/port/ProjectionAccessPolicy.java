package com.follarce.domain.port;

import java.util.Set;

/** Tables exposed to an authenticated SYSTEM_ADMIN through the system projection. */
public final class ProjectionAccessPolicy {
    private static final Set<String> SCHEMAS = Set.of("meta", "object_store", "package",
            "auth", "vfs", "program", "process", "scheduler", "ipc", "effect",
            "terminal", "audit");

    private ProjectionAccessPolicy() { }

    public static boolean canRead(String schema, String table) {
        return SCHEMAS.contains(schema) && table != null && !table.isBlank();
    }

    public static boolean canCreate(String schema, String table) {
        return canRead(schema, table);
    }

    public static boolean canDelete(String schema, String table) {
        return canRead(schema, table);
    }
}
