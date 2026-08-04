package com.follarce.exporter;

import com.follarce.app.BuildInfo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads one committed PostgreSQL snapshot without exporting runtime machinery. */
final class PostgresLogicalExportSource implements LogicalSnapshotProducer {
    static final Set<String> EXCLUDED_RELATIONS = Set.of(
            "meta.kernel_instance",
            "scheduler.lease",
            "scheduler.runner");
    static final Map<String, List<String>> EXCLUDED_COLUMNS = Map.of(
            "meta.boot", List.of(
                    "control_backend_pid",
                    "control_backend_started_at",
                    "control_proof_lock_key"));

    private static final List<String> APPLICATION_SCHEMAS = List.of(
            "audit", "auth", "effect", "ipc", "meta", "object_store", "package",
            "process", "program", "scheduler", "terminal", "vfs");

    private final DataSource dataSource;

    PostgresLogicalExportSource(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void writeSnapshot(SqliteLogicalExportWriter writer, BuildInfo buildInfo,
                              Instant exportedAt) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(buildInfo, "buildInfo");
        Objects.requireNonNull(exportedAt, "exportedAt");
        try (Connection source = dataSource.getConnection()) {
            beginConsistentRead(source);
            try {
                int schemaVersion = schemaVersion(source);
                if (schemaVersion < buildInfo.minimumSchema()
                        || schemaVersion > buildInfo.maximumSchema()) {
                    throw new LogicalExportException("Database schema " + schemaVersion
                            + " is outside this build's supported range "
                            + buildInfo.minimumSchema() + ".." + buildInfo.maximumSchema());
                }
                assumeOwnerRole(source);
                writer.begin(metadata(source, buildInfo, exportedAt, schemaVersion));
                for (String table : discoverTables(source)) {
                    writer.beginTable(table);
                    copyRows(source, table, writer);
                    writer.endTable();
                }
                writer.finish();
                source.rollback();
            } catch (SQLException | RuntimeException failure) {
                rollback(source, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new LogicalExportException("Cannot read PostgreSQL logical snapshot", failure);
        }
    }

    private static void beginConsistentRead(Connection source) throws SQLException {
        if (!source.getAutoCommit()) source.rollback();
        source.setAutoCommit(false);
        source.setReadOnly(true);
        source.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        try (Statement statement = source.createStatement()) {
            statement.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE, READ ONLY, DEFERRABLE");
            statement.execute("SET LOCAL TIME ZONE 'UTC'");
            statement.execute("SET LOCAL bytea_output = 'hex'");
            statement.execute("SET LOCAL DateStyle = 'ISO, YMD'");
            statement.execute("SET LOCAL IntervalStyle = 'iso_8601'");
            statement.execute("SET LOCAL extra_float_digits = 3");
        }
        if (!"serializable".equals(setting(source, "transaction_isolation"))
                || !"on".equals(setting(source, "transaction_read_only"))) {
            throw new LogicalExportException(
                    "PostgreSQL refused a serializable read-only export transaction");
        }
    }

    private static void assumeOwnerRole(Connection source) throws SQLException {
        try (Statement statement = source.createStatement()) {
            statement.execute("SET LOCAL ROLE cilexec_owner");
        }
        if (!"cilexec_owner".equals(scalar(source, "SELECT current_user"))) {
            throw new LogicalExportException("Export database role cannot assume cilexec_owner");
        }
    }

    private static Map<String, String> metadata(Connection source, BuildInfo buildInfo,
                                                Instant exportedAt, int schemaVersion)
            throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("export.format", "cilexec-logical-export");
        values.put("export.format.version",
                Integer.toString(SqliteLogicalExportWriter.FORMAT_VERSION));
        values.put("export.created_at", exportedAt.toString());
        values.put("application.name", buildInfo.applicationName());
        values.put("application.version", buildInfo.applicationVersion());
        values.put("build.revision", buildInfo.revision());
        values.put("fcl.runtime.format", Integer.toString(buildInfo.fclRuntimeFormat()));
        values.put("database.schema.version", Integer.toString(schemaVersion));
        values.put("database.schema.minimum", Integer.toString(buildInfo.minimumSchema()));
        values.put("database.schema.maximum", Integer.toString(buildInfo.maximumSchema()));
        values.put("source.database", scalar(source, "SELECT current_database()"));
        values.put("source.postgresql.version", setting(source, "server_version"));
        values.put("source.transaction.isolation", "serializable");
        values.put("source.transaction.read_only", "true");
        values.put("source.transaction.deferrable", "true");
        values.put("source.excluded.relations",
                String.join(",", EXCLUDED_RELATIONS.stream().sorted().toList()));
        values.put("source.excluded.columns", EXCLUDED_COLUMNS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + String.join(",", entry.getValue()))
                .collect(java.util.stream.Collectors.joining(";")));
        return values;
    }

    private static int schemaVersion(Connection source) throws SQLException {
        // The version check runs as the DataSource role (production: cilexec_runtime) before
        // SET LOCAL ROLE cilexec_owner; docker/postgres/init/00-cilexec-bootstrap.sh grants
        // that role USAGE on the flyway schema and SELECT on migrator-owned tables. A
        // deployment that skips those grants fails here by design: an export with an
        // unchecked schema version must not be produced.
        String version = scalar(source, "SELECT max(version::integer)::text "
                + "FROM flyway.flyway_schema_history "
                + "WHERE success AND version ~ '^[0-9]+$'");
        if (version == null) {
            throw new LogicalExportException("Database has no successful numeric schema migration");
        }
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException failure) {
            throw new LogicalExportException("Database schema version is not an integer", failure);
        }
    }

    private static List<String> discoverTables(Connection source) throws SQLException {
        String schemas = APPLICATION_SCHEMAS.stream()
                .map(name -> "'" + name + "'")
                .collect(java.util.stream.Collectors.joining(","));
        // Only plain relations ('r') are exported: a partitioned parent ('p') has no storage
        // of its own, so exporting it would duplicate the rows that live in its leaf
        // partitions, which are plain relations and are exported individually.
        String query = "SELECT n.nspname,c.relname FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE c.relkind = 'r' AND n.nspname IN (" + schemas + ") "
                + "ORDER BY n.nspname,c.relname";
        List<String> tables = new ArrayList<>();
        try (Statement statement = source.createStatement();
             ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                String table = rows.getString(1) + "." + rows.getString(2);
                if (!EXCLUDED_RELATIONS.contains(table)) tables.add(table);
            }
        }
        if (tables.isEmpty()) {
            throw new LogicalExportException("No CilExec semantic tables were discovered");
        }
        return List.copyOf(tables);
    }

    private static void copyRows(Connection source, String qualifiedTable,
                                 SqliteLogicalExportWriter writer) throws SQLException {
        String[] identifiers = qualifiedTable.split("\\.", -1);
        if (identifiers.length != 2) {
            throw new LogicalExportException("Invalid discovered table name: " + qualifiedTable);
        }
        String relation = quote(identifiers[0]) + "." + quote(identifiers[1]);
        String rowExpression = "pg_catalog.to_jsonb(source_row)";
        List<String> excludedColumns = EXCLUDED_COLUMNS.get(qualifiedTable);
        if (excludedColumns != null) {
            String keys = excludedColumns.stream()
                    .map(column -> "'" + column + "'")
                    .collect(java.util.stream.Collectors.joining(","));
            rowExpression += " - ARRAY[" + keys + "]::text[]";
        }
        // Sorting the full canonical row text on the server is expensive on large tables, but
        // it is what makes row hashes deterministic regardless of physical row order; this
        // must not be replaced with an index-order sort without a proven textual-equivalence
        // argument.
        String query = "SELECT (" + rowExpression + ")::text AS row_json FROM "
                + relation + " AS source_row ORDER BY pg_catalog.convert_to(("
                + rowExpression + ")::text,'UTF8')";
        try (Statement statement = source.createStatement()) {
            statement.setFetchSize(500);
            try (ResultSet rows = statement.executeQuery(query)) {
                while (rows.next()) writer.appendRow(rows.getString(1));
            }
        }
    }

    private static String quote(String identifier) {
        if (!identifier.matches("[a-z_][a-z0-9_]*")) {
            throw new LogicalExportException("Unsafe PostgreSQL identifier: " + identifier);
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String setting(Connection source, String name) throws SQLException {
        return scalar(source, "SELECT current_setting('" + name + "')");
    }

    private static String scalar(Connection source, String query) throws SQLException {
        try (Statement statement = source.createStatement();
             ResultSet rows = statement.executeQuery(query)) {
            if (!rows.next()) throw new LogicalExportException("Metadata query returned no row");
            return rows.getString(1);
        }
    }

    private static void rollback(Connection source, Throwable original) {
        try {
            source.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
