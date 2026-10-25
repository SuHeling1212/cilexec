package com.follarce.domain.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Administrator-only, policy-limited JSON projection over reviewed CilExec tables.
 * Ordinary FCL code must keep using the typed repositories.
 *
 * <p>The implementation temporarily switches the current transaction from the tenant role to
 * the runtime role (the session role) for the duration of one operation, then restores the
 * tenant role. The caller must have checked SYSTEM_ADMIN before invoking any method.
 */
public interface AdminProjectionRepository {
    List<String> schemas(UUID actorId);

    List<String> tables(UUID actorId, String schema);

    TableDefinition describe(UUID actorId, String schema, String table);

    Optional<Row> readRow(UUID actorId, String schema, String table, String keyJson);

    List<Row> listRows(UUID actorId, String schema, String table, Optional<String> afterKeyJson,
                       int limit);

    /** Update one existing row's explicitly allowlisted columns; never inserts. */
    Row writeRow(UUID actorId, String schema, String table, String rowJson);

    /** Reserved for the file API; currently always denied. */
    boolean deleteRow(UUID actorId, String schema, String table, String keyJson);

    record TableDefinition(
            String schema,
            String table,
            List<String> primaryKey,
            List<String> columns,
            Set<String> generated,
            Set<String> identity
    ) {
        public TableDefinition {
            primaryKey = List.copyOf(primaryKey);
            columns = List.copyOf(columns);
            generated = Set.copyOf(generated);
            identity = Set.copyOf(identity);
        }
    }

    record Row(String keyJson, String rowJson) {
    }
}
