package com.follarce.exporter;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Streaming writer for the deliberately small, self-verifying export schema. */
final class SqliteLogicalExportWriter implements AutoCloseable {
    static final int APPLICATION_ID = 0x43494c45; // ASCII "CILE"
    static final int FORMAT_VERSION = 1;
    static final Set<String> TABLES = Set.of(
            "export_metadata", "export_table", "export_row");

    private static final Set<String> RESERVED_METADATA = Set.of(
            "export.table_count", "export.row_count", "export.manifest.sha256");

    private final Connection connection;
    private final PreparedStatement insertRow;
    private final List<LogicalExportHashes.TableSummary> summaries = new ArrayList<>();

    private Map<String, String> metadata;
    private String activeTable;
    private MessageDigest activeTableDigest;
    private long activeRowCount;
    private long totalRows;
    private boolean complete;

    SqliteLogicalExportWriter(Path database) {
        Objects.requireNonNull(database, "database");
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        } catch (SQLException failure) {
            throw new LogicalExportException("Cannot open SQLite logical export", failure);
        }
        try {
            configure();
            createSchema();
            insertRow = connection.prepareStatement(
                    "INSERT INTO export_row(table_name,row_number,row_json,row_sha256) "
                            + "VALUES (?,?,?,?)");
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw new LogicalExportException("Cannot initialize SQLite logical export", failure);
        }
    }

    void begin(Map<String, String> sourceMetadata) {
        if (metadata != null) throw new IllegalStateException("Export metadata was already set");
        Objects.requireNonNull(sourceMetadata, "sourceMetadata");
        TreeMap<String, String> copy = new TreeMap<>();
        sourceMetadata.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || RESERVED_METADATA.contains(key)) {
                throw new IllegalArgumentException("Invalid or reserved export metadata key: " + key);
            }
            if (copy.put(key, value) != null) {
                throw new IllegalArgumentException("Duplicate export metadata key: " + key);
            }
        });
        require(copy, "export.format", "cilexec-logical-export");
        require(copy, "export.format.version", Integer.toString(FORMAT_VERSION));
        requirePresent(copy, "application.name");
        requirePresent(copy, "application.version");
        requirePresent(copy, "build.revision");
        requirePresent(copy, "fcl.runtime.format");
        requirePresent(copy, "database.schema.version");
        copy.put("hash.algorithm", "SHA-256");
        copy.put("hash.framing", "unsigned-64-bit-big-endian-length-prefix");
        copy.put("row.encoding", "canonical-json-utf8");
        metadata = copy;
    }

    void beginTable(String tableName) {
        requireBegun();
        if (activeTable != null) throw new IllegalStateException("Previous table is still open");
        if (tableName == null || !tableName.matches(
                "[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid qualified table name: " + tableName);
        }
        if (!summaries.isEmpty()
                && summaries.getLast().tableName().compareTo(tableName) >= 0) {
            throw new IllegalArgumentException("Export tables must be unique and sorted");
        }
        activeTable = tableName;
        activeTableDigest = LogicalExportHashes.sha256();
        activeRowCount = 0;
    }

    void appendRow(String rowJson) {
        if (activeTable == null) throw new IllegalStateException("No export table is open");
        String canonical = CanonicalJson.normalizeObject(Objects.requireNonNull(rowJson, "rowJson"));
        String rowHash = LogicalExportHashes.sha256(canonical);
        LogicalExportHashes.frame(activeTableDigest, canonical);
        activeRowCount++;
        totalRows++;
        try {
            insertRow.setString(1, activeTable);
            insertRow.setLong(2, activeRowCount);
            insertRow.setString(3, canonical);
            insertRow.setString(4, rowHash);
            insertRow.executeUpdate();
        } catch (SQLException failure) {
            throw new LogicalExportException("Cannot append logical export row", failure);
        }
    }

    void endTable() {
        if (activeTable == null) throw new IllegalStateException("No export table is open");
        String hash = HexFormat.of().formatHex(activeTableDigest.digest());
        LogicalExportHashes.TableSummary summary = new LogicalExportHashes.TableSummary(
                activeTable, activeRowCount, hash);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO export_table(table_name,row_count,content_sha256) VALUES (?,?,?)")) {
            insert.setString(1, summary.tableName());
            insert.setLong(2, summary.rowCount());
            insert.setString(3, summary.contentSha256());
            insert.executeUpdate();
            summaries.add(summary);
        } catch (SQLException failure) {
            throw new LogicalExportException("Cannot finish logical export table", failure);
        } finally {
            activeTable = null;
            activeTableDigest = null;
            activeRowCount = 0;
        }
    }

    void finish() {
        requireBegun();
        if (activeTable != null) throw new IllegalStateException("Export table is still open");
        if (complete) throw new IllegalStateException("Export was already completed");

        Map<String, String> finalized = new TreeMap<>(metadata);
        finalized.put("export.table_count", Integer.toString(summaries.size()));
        finalized.put("export.row_count", Long.toString(totalRows));
        String manifest = LogicalExportHashes.manifest(finalized, summaries);
        finalized.put("export.manifest.sha256", manifest);

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO export_metadata(metadata_key,metadata_value) VALUES (?,?)")) {
            for (Map.Entry<String, String> entry : finalized.entrySet()) {
                insert.setString(1, entry.getKey());
                insert.setString(2, entry.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
            createReadOnlyTriggers();
            connection.commit();
            complete = true;
        } catch (SQLException failure) {
            rollback(failure);
            throw new LogicalExportException("Cannot finalize logical export", failure);
        }
    }

    @Override
    public void close() {
        SQLException failure = null;
        if (!complete) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure = rollbackFailure;
            }
        }
        try {
            insertRow.close();
        } catch (SQLException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) {
            throw new LogicalExportException("Cannot close logical export", failure);
        }
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA trusted_schema=OFF");
            statement.execute("PRAGMA application_id=" + APPLICATION_ID);
            statement.execute("PRAGMA user_version=" + FORMAT_VERSION);
        }
        connection.setAutoCommit(false);
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE export_metadata("
                    + "metadata_key TEXT PRIMARY KEY CHECK(length(metadata_key)>0),"
                    + "metadata_value TEXT NOT NULL) STRICT, WITHOUT ROWID");
            statement.execute("CREATE TABLE export_table("
                    + "table_name TEXT PRIMARY KEY CHECK(length(table_name)>2),"
                    + "row_count INTEGER NOT NULL CHECK(row_count>=0),"
                    + "content_sha256 TEXT NOT NULL CHECK(content_sha256 GLOB '[0-9a-f]*' "
                    + "AND length(content_sha256)=64)) STRICT, WITHOUT ROWID");
            statement.execute("CREATE TABLE export_row("
                    + "table_name TEXT NOT NULL,"
                    + "row_number INTEGER NOT NULL CHECK(row_number>0),"
                    + "row_json TEXT NOT NULL CHECK(json_valid(row_json)),"
                    + "row_sha256 TEXT NOT NULL CHECK(row_sha256 GLOB '[0-9a-f]*' "
                    + "AND length(row_sha256)=64),"
                    + "PRIMARY KEY(table_name,row_number),"
                    + "FOREIGN KEY(table_name) REFERENCES export_table(table_name) "
                    + "ON UPDATE RESTRICT ON DELETE RESTRICT "
                    + "DEFERRABLE INITIALLY DEFERRED) STRICT, WITHOUT ROWID");
        }
    }

    private void createReadOnlyTriggers() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : readOnlyTriggers().values()) statement.execute(sql);
        }
    }

    static Map<String, String> readOnlyTriggers() {
        Map<String, String> triggers = new TreeMap<>();
        for (String table : TABLES.stream().sorted().toList()) {
            for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
                String trigger = "guard_" + table + "_" + operation.toLowerCase();
                triggers.put(trigger, "CREATE TRIGGER " + trigger + " BEFORE " + operation
                        + " ON " + table + " BEGIN SELECT RAISE(ABORT,"
                        + "'CilExec logical export is read-only'); END");
            }
        }
        return Map.copyOf(triggers);
    }

    private void requireBegun() {
        if (metadata == null) throw new IllegalStateException("Export metadata was not set");
    }

    private static void require(Map<String, String> values, String key, String expected) {
        if (!expected.equals(values.get(key))) {
            throw new IllegalArgumentException("Export metadata " + key + " must be " + expected);
        }
    }

    private static void requirePresent(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing export metadata: " + key);
        }
    }

    private void rollback(SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
