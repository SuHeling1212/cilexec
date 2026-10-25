package com.follarce.persistence.postgres.repository;

import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.AdminProjectionRepository;
import com.follarce.persistence.postgres.error.SqlStateClassifier;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Generic administrator-only JSON access to the CilExec tables. It switches the current
 * transaction to the session (runtime) role for one operation and restores the tenant role
 * afterwards; the caller must have checked SYSTEM_ADMIN first.
 *
 * <p>This is deliberately not used by normal FCL resource functions. Values are passed through
 * {@code jsonb_populate_record}, so PostgreSQL performs the type conversion and enforces every
 * constraint, trigger, foreign key, and generated-column rule.
 */
public final class JdbcAdminProjectionRepository implements AdminProjectionRepository {
    private static final List<String> SCHEMAS = List.of(
            "meta", "object_store", "package", "auth", "vfs", "program", "process",
            "scheduler", "ipc", "effect", "terminal", "audit");
    private static final int MAX_LIMIT = 200;

    private final Connection connection;
    private final Map<String, TableDefinition> definitions = new HashMap<>();

    public JdbcAdminProjectionRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public List<String> schemas(UUID actorId) {
        return asAdmin(actorId, () -> SCHEMAS);
    }

    @Override
    public List<String> tables(UUID actorId, String schema) {
        requireSchema(schema);
        return asAdmin(actorId, () -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT c.relname
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
                      AND c.relname NOT LIKE 'pg\\_%'
                    ORDER BY c.relname
                    """)) {
                statement.setString(1, schema);
                try (ResultSet rows = statement.executeQuery()) {
                    List<String> result = new ArrayList<>();
                    while (rows.next()) result.add(rows.getString(1));
                    return List.copyOf(result);
                }
            }
        });
    }

    @Override
    public TableDefinition describe(UUID actorId, String schema, String table) {
        requireSchema(schema);
        String cacheKey = schema + "\0" + table;
        TableDefinition cached = definitions.get(cacheKey);
        if (cached != null) return cached;
        return asAdmin(actorId, () -> {
            List<String> columns = new ArrayList<>();
            Set<String> generated = new HashSet<>();
            Set<String> identity = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT a.attname, a.attgenerated, a.attidentity
                    FROM pg_attribute a
                    JOIN pg_class c ON c.oid = a.attrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0
                      AND NOT a.attisdropped
                    ORDER BY a.attnum
                    """)) {
                statement.setString(1, schema);
                statement.setString(2, table);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String name = rows.getString(1);
                        columns.add(name);
                        String generatedKind = rows.getString(2);
                        if (generatedKind != null && !generatedKind.isEmpty()) generated.add(name);
                        String identityKind = rows.getString(3);
                        if (identityKind != null && !identityKind.isEmpty()) identity.add(name);
                    }
                }
            }
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("Unknown table: " + schema + "." + table);
            }
            List<String> primaryKey = primaryKey(schema, table);
            if (primaryKey.isEmpty()) {
                throw new IllegalArgumentException(
                        "Table has no primary key: " + schema + "." + table);
            }
            TableDefinition definition = new TableDefinition(schema, table, primaryKey, columns,
                    generated, identity);
            definitions.put(cacheKey, definition);
            return definition;
        });
    }

    @Override
    public Optional<Row> readRow(UUID actorId, String schema, String table, String keyJson) {
        TableDefinition definition = describe(actorId, schema, table);
        JsonObject key = keyObject(keyJson, definition);
        return asAdmin(actorId, () -> read(definition, key));
    }

    @Override
    public List<Row> listRows(UUID actorId, String schema, String table,
                              Optional<String> afterKeyJson, int limit) {
        TableDefinition definition = describe(actorId, schema, table);
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        JsonObject after = afterKeyJson.map(value -> keyObject(value, definition)).orElse(null);
        return asAdmin(actorId, () -> {
            String keySelect = keySelect(definition, "t");
            StringBuilder sql = new StringBuilder()
                    .append("SELECT to_jsonb(t)::text AS row_json, ")
                    .append(keySelect)
                    .append(" AS key_json FROM ")
                    .append(table(definition))
                    .append(" AS t");
            if (after != null) {
                sql.append(", (SELECT jsonb_populate_record(NULL::")
                        .append(table(definition))
                        .append(", ?::jsonb) AS r) AS k");
            }
            if (after != null) {
                sql.append(" WHERE ").append(rowComparison(definition, "t", "k.r", ">"));
            }
            sql.append(" ORDER BY ").append(orderBy(definition, "t"))
                    .append(" LIMIT ").append(bounded);
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                if (after != null) statement.setString(1, after.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    List<Row> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(new Row(rows.getString("key_json"), rows.getString("row_json")));
                    }
                    return List.copyOf(result);
                }
            }
        });
    }

    @Override
    public Row writeRow(UUID actorId, String schema, String table, String rowJson) {
        TableDefinition definition = describe(actorId, schema, table);
        JsonObject values = rowObject(rowJson, definition);
        requirePrimaryKey(values, definition);
        return asAdmin(actorId, () -> {
            Optional<Row> existing = read(definition, keyObjectFromRow(values, definition));
            if (existing.isPresent()) {
                return update(definition, values);
            }
            return insert(definition, values);
        });
    }

    @Override
    public boolean deleteRow(UUID actorId, String schema, String table, String keyJson) {
        TableDefinition definition = describe(actorId, schema, table);
        JsonObject key = keyObject(keyJson, definition);
        return asAdmin(actorId, () -> {
            String sql = "DELETE FROM " + table(definition) + " AS t USING (SELECT "
                    + "jsonb_populate_record(NULL::" + table(definition)
                    + ", ?::jsonb) AS r) AS k WHERE "
                    + rowComparison(definition, "t", "k.r", "=");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, key.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    private Optional<Row> read(TableDefinition definition, JsonObject key) throws SQLException {
        String sql = "SELECT to_jsonb(t)::text AS row_json, " + keySelect(definition, "t")
                + " AS key_json FROM " + table(definition) + " AS t, (SELECT "
                + "jsonb_populate_record(NULL::" + table(definition)
                + ", ?::jsonb) AS r) AS k WHERE "
                + rowComparison(definition, "t", "k.r", "=");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new Row(rows.getString("key_json"), rows.getString("row_json")));
            }
        }
    }

    private Row update(TableDefinition definition, JsonObject values) throws SQLException {
        List<String> changing = new ArrayList<>();
        for (String column : definition.columns()) {
            if (!definition.primaryKey().contains(column) && values.has(column)) {
                changing.add(column);
            }
        }
        if (changing.isEmpty()) {
            return read(definition, keyObjectFromRow(values, definition)).orElseThrow();
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(table(definition))
                .append(" AS t SET ");
        for (int index = 0; index < changing.size(); index++) {
            if (index > 0) sql.append(", ");
            String column = quote(changing.get(index));
            sql.append(column).append(" = (s.r).").append(column);
        }
        sql.append(" FROM (SELECT jsonb_populate_record(NULL::").append(table(definition))
                .append(", ?::jsonb) AS r) AS s WHERE ")
                .append(rowComparison(definition, "t", "s.r", "="))
                .append(" RETURNING to_jsonb(t)::text AS row_json");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, values.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Projected row disappeared");
                String rowJson = rows.getString("row_json");
                return new Row(keyJsonFromRow(rowJson, definition), rowJson);
            }
        }
    }

    private Row insert(TableDefinition definition, JsonObject values) throws SQLException {
        List<String> provided = new ArrayList<>();
        for (String column : definition.columns()) {
            if (values.has(column)) provided.add(column);
        }
        if (provided.isEmpty()) throw new IllegalArgumentException("No writeable columns supplied");
        StringBuilder columns = new StringBuilder();
        StringBuilder selected = new StringBuilder();
        for (int index = 0; index < provided.size(); index++) {
            if (index > 0) {
                columns.append(", ");
                selected.append(", ");
            }
            String quoted = quote(provided.get(index));
            columns.append(quoted);
            selected.append("(s.r).").append(quoted);
        }
        boolean overriding = provided.stream().anyMatch(definition.identity()::contains);
        StringBuilder sql = new StringBuilder("WITH inserted AS (INSERT INTO ")
                .append(table(definition)).append(" (").append(columns).append(')');
        if (overriding) sql.append(" OVERRIDING SYSTEM VALUE");
        sql.append(" SELECT ").append(selected)
                .append(" FROM (SELECT jsonb_populate_record(NULL::")
                .append(table(definition)).append(", ?::jsonb) AS r) AS s RETURNING *)")
                .append(" SELECT to_jsonb(inserted)::text AS row_json FROM inserted");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, values.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Projected insert returned no row");
                String rowJson = rows.getString("row_json");
                return new Row(keyJsonFromRow(rowJson, definition), rowJson);
            }
        }
    }

    private List<String> primaryKey(String schema, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.attname
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY (i.indkey)
                WHERE n.nspname = ? AND c.relname = ? AND i.indisprimary
                ORDER BY a.attnum
                """)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rows.next()) result.add(rows.getString(1));
                return result;
            }
        }
    }

    private String table(TableDefinition definition) {
        return quote(definition.schema()) + "." + quote(definition.table());
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String keySelect(TableDefinition definition, String alias) {
        StringBuilder sql = new StringBuilder("jsonb_build_object(");
        for (int index = 0; index < definition.primaryKey().size(); index++) {
            if (index > 0) sql.append(", ");
            String column = definition.primaryKey().get(index);
            sql.append("'").append(column.replace("'", "''")).append("', ")
                    .append(alias).append('.').append(quote(column));
        }
        return sql.append(')').toString();
    }

    private static String orderBy(TableDefinition definition, String alias) {
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < definition.primaryKey().size(); index++) {
            if (index > 0) sql.append(", ");
            sql.append(alias).append('.').append(quote(definition.primaryKey().get(index)));
        }
        return sql.toString();
    }

    private static String rowComparison(TableDefinition definition, String left,
                                        String right, String operator) {
        List<String> primaryKey = definition.primaryKey();
        String leftField = left + "." + quote(primaryKey.getFirst());
        String rightField = "(" + right + ")." + quote(primaryKey.getFirst());
        if (primaryKey.size() == 1) {
            return leftField + " " + operator + " " + rightField;
        }
        String leftTuple = primaryKey.stream().map(column -> left + "." + quote(column))
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        String rightTuple = primaryKey.stream().map(column -> "(" + right + ")." + quote(column))
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        return "(" + leftTuple + ") " + operator + " (" + rightTuple + ")";
    }

    private JsonObject keyObject(String json, TableDefinition definition) {
        JsonObject object = parseObject(json, "key");
        for (String key : object.keySet()) {
            if (!definition.primaryKey().contains(key)) {
                throw new IllegalArgumentException("Key column is not part of the primary key: " + key);
            }
        }
        for (String key : definition.primaryKey()) {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                throw new IllegalArgumentException("Missing primary-key value: " + key);
            }
        }
        return object;
    }

    private JsonObject keyObjectFromRow(JsonObject row, TableDefinition definition) {
        JsonObject key = new JsonObject();
        for (String column : definition.primaryKey()) {
            JsonElement value = row.get(column);
            if (value == null || value.isJsonNull()) {
                throw new IllegalArgumentException("Missing primary-key value: " + column);
            }
            key.add(column, value);
        }
        return key;
    }

    private String keyJsonFromRow(String rowJson, TableDefinition definition) {
        return keyObjectFromRow(JsonParser.parseString(rowJson).getAsJsonObject(), definition)
                .toString();
    }

    private JsonObject rowObject(String json, TableDefinition definition) {
        JsonObject object = parseObject(json, "row");
        for (String key : object.keySet()) {
            if (!definition.columns().contains(key)) {
                throw new IllegalArgumentException("Unknown column: " + key);
            }
            if (definition.generated().contains(key)) {
                throw new IllegalArgumentException("Generated column is read-only: " + key);
            }
        }
        return object;
    }

    private static JsonObject parseObject(String json, String what) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) throw new IllegalArgumentException(what + " must be a JSON object");
            return element.getAsJsonObject();
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalArgumentException("Invalid " + what + " JSON", failure);
        }
    }

    private static void requirePrimaryKey(JsonObject values, TableDefinition definition) {
        for (String column : definition.primaryKey()) {
            if (!values.has(column) || values.get(column).isJsonNull()) {
                throw new IllegalArgumentException("Missing primary-key value: " + column);
            }
        }
    }

    private static void requireSchema(String schema) {
        if (!SCHEMAS.contains(schema)) {
            throw new IllegalArgumentException("Unsupported schema: " + schema);
        }
    }

    private <T> T asAdmin(UUID actorId, SqlWork<T> work) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET ROLE");
        } catch (SQLException exception) {
            throw SqlStateClassifier.classify("projection.resetRole", exception);
        }
        T result = null;
        RuntimeException failure = null;
        try {
            result = work.run();
        } catch (SQLException exception) {
            // A SQL failure aborts the transaction; the outer executor rolls it back.
            throw SqlStateClassifier.classify("projection.query", exception);
        } catch (RuntimeException runtime) {
            failure = runtime;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE " + UserAccount.roleNameFor(actorId));
        } catch (SQLException exception) {
            if (failure == null) {
                throw SqlStateClassifier.classify("projection.restoreRole", exception);
            }
            failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
        return result;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }
}
