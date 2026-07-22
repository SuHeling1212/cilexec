package com.follarce.exporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Recomputes every count and digest before an export file is published. */
public final class SqliteLogicalExportVerifier {
    private static final int READ_ONLY_TRIGGER_COUNT = SqliteLogicalExportWriter.TABLES.size() * 3;

    public LogicalExportReport verify(Path database) {
        if (database == null || !Files.isRegularFile(database)) {
            throw new LogicalExportException("Logical export database does not exist: " + database);
        }
        try (Connection connection = openImmutable(database)) {
            validatePragmas(connection);
            validateSchema(connection);
            Map<String, String> metadata = readMetadata(connection);
            List<LogicalExportHashes.TableSummary> summaries = readSummaries(connection);
            long rowCount = verifyRows(connection, summaries);
            verifyCounts(metadata, summaries.size(), rowCount);

            String expectedManifest = required(metadata, "export.manifest.sha256");
            Map<String, String> coveredMetadata = new TreeMap<>(metadata);
            coveredMetadata.remove("export.manifest.sha256");
            String actualManifest = LogicalExportHashes.manifest(coveredMetadata, summaries);
            if (!actualManifest.equals(expectedManifest)) {
                throw new LogicalExportException("Logical export manifest hash does not match");
            }
            return new LogicalExportReport(database, summaries.size(), rowCount, actualManifest);
        } catch (SQLException failure) {
            throw new LogicalExportException("Cannot verify logical export database", failure);
        }
    }

    private static Connection openImmutable(Path database) throws SQLException {
        String uri = "jdbc:sqlite:file:" + database.toAbsolutePath().normalize()
                + "?mode=ro&immutable=1";
        Connection connection = DriverManager.getConnection(uri);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA trusted_schema=OFF");
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return connection;
    }

    private static void validatePragmas(Connection connection) throws SQLException {
        if (pragmaInteger(connection, "application_id") != SqliteLogicalExportWriter.APPLICATION_ID) {
            throw new LogicalExportException("Unexpected SQLite application_id");
        }
        if (pragmaInteger(connection, "user_version") != SqliteLogicalExportWriter.FORMAT_VERSION) {
            throw new LogicalExportException("Unsupported logical export format version");
        }
        if (pragmaInteger(connection, "query_only") != 1
                || pragmaInteger(connection, "trusted_schema") != 0) {
            throw new LogicalExportException("Logical export was not opened in hardened read-only mode");
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA integrity_check")) {
            int results = 0;
            while (rows.next()) {
                results++;
                if (!"ok".equals(rows.getString(1))) {
                    throw new LogicalExportException(
                            "SQLite integrity_check failed: " + rows.getString(1));
                }
            }
            if (results != 1) {
                throw new LogicalExportException("SQLite integrity_check returned no conclusive result");
            }
        }
    }

    private static int pragmaInteger(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA " + pragma)) {
            if (!rows.next()) throw new LogicalExportException("Missing PRAGMA " + pragma);
            return rows.getInt(1);
        }
    }

    private static void validateSchema(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        int triggers = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT type,name FROM sqlite_schema "
                             + "WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name")) {
            while (rows.next()) {
                switch (rows.getString(1)) {
                    case "table" -> tables.add(rows.getString(2));
                    case "trigger" -> {
                        if (!rows.getString(2).startsWith("guard_export_")) {
                            throw new LogicalExportException(
                                    "Unexpected logical export trigger: " + rows.getString(2));
                        }
                        triggers++;
                    }
                    default -> throw new LogicalExportException(
                            "Unexpected logical export schema object: " + rows.getString(2));
                }
            }
        }
        if (!tables.equals(new java.util.TreeSet<>(SqliteLogicalExportWriter.TABLES))) {
            throw new LogicalExportException("Logical export has an unexpected table set: " + tables);
        }
        if (triggers != READ_ONLY_TRIGGER_COUNT) {
            throw new LogicalExportException("Logical export read-only guards are incomplete");
        }
    }

    private static Map<String, String> readMetadata(Connection connection) throws SQLException {
        Map<String, String> metadata = new TreeMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT metadata_key,metadata_value FROM export_metadata "
                             + "ORDER BY metadata_key")) {
            while (rows.next()) metadata.put(rows.getString(1), rows.getString(2));
        }
        if (!"cilexec-logical-export".equals(required(metadata, "export.format"))
                || !Integer.toString(SqliteLogicalExportWriter.FORMAT_VERSION)
                .equals(required(metadata, "export.format.version"))) {
            throw new LogicalExportException("File is not a supported CilExec logical export");
        }
        required(metadata, "application.name");
        required(metadata, "application.version");
        required(metadata, "build.revision");
        required(metadata, "fcl.runtime.format");
        required(metadata, "database.schema.version");
        if (!"SHA-256".equals(required(metadata, "hash.algorithm"))) {
            throw new LogicalExportException("Unsupported logical export hash algorithm");
        }
        return metadata;
    }

    private static List<LogicalExportHashes.TableSummary> readSummaries(Connection connection)
            throws SQLException {
        List<LogicalExportHashes.TableSummary> summaries = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT table_name,row_count,content_sha256 FROM export_table "
                             + "ORDER BY table_name")) {
            while (rows.next()) {
                summaries.add(new LogicalExportHashes.TableSummary(
                        rows.getString(1), rows.getLong(2), rows.getString(3)));
            }
        }
        return List.copyOf(summaries);
    }

    private static long verifyRows(Connection connection,
                                   List<LogicalExportHashes.TableSummary> summaries)
            throws SQLException {
        long total = 0;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT row_number,row_json,row_sha256 FROM export_row "
                        + "WHERE table_name=? ORDER BY row_number")) {
            for (LogicalExportHashes.TableSummary table : summaries) {
                query.setString(1, table.tableName());
                MessageDigest digest = LogicalExportHashes.sha256();
                long count = 0;
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        count++;
                        if (rows.getLong(1) != count) {
                            throw new LogicalExportException(
                                    "Non-contiguous row numbering in " + table.tableName());
                        }
                        String json = rows.getString(2);
                        if (!CanonicalJson.normalizeObject(json).equals(json)) {
                            throw new LogicalExportException(
                                    "Non-canonical JSON row in " + table.tableName());
                        }
                        if (!LogicalExportHashes.sha256(json).equals(rows.getString(3))) {
                            throw new LogicalExportException(
                                    "Row hash mismatch in " + table.tableName());
                        }
                        LogicalExportHashes.frame(digest, json);
                    }
                }
                String actualTableHash = HexFormat.of().formatHex(digest.digest());
                if (count != table.rowCount()
                        || !actualTableHash.equals(table.contentSha256())) {
                    throw new LogicalExportException(
                            "Table count or hash mismatch in " + table.tableName());
                }
                total = Math.addExact(total, count);
            }
        }
        return total;
    }

    private static void verifyCounts(Map<String, String> metadata, int tables, long rows) {
        try {
            if (Integer.parseInt(required(metadata, "export.table_count")) != tables
                    || Long.parseLong(required(metadata, "export.row_count")) != rows) {
                throw new LogicalExportException("Logical export aggregate counts do not match");
            }
        } catch (NumberFormatException failure) {
            throw new LogicalExportException("Logical export counts are not integers", failure);
        }
    }

    private static String required(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            throw new LogicalExportException("Missing logical export metadata: " + key);
        }
        return value;
    }
}
