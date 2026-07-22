package com.follarce.persistence.sqlite;

import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.vfs.ObjectHash;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Opens package bytes through an immutable, query-only SQLite cache file. */
public final class SqlitePackageReader {
    private static final List<String> REQUIRED_TABLES = List.of(
            "package_metadata",
            "package_file",
            "package_module",
            "package_dependency",
            "package_entrypoint",
            "package_export",
            "package_capability",
            "package_signature"
    );
    private static final Set<String> LIFECYCLE_KEYS = Set.of(
            "pre_install", "post_install", "pre_upgrade", "post_upgrade",
            "pre_uninstall", "post_uninstall"
    );

    public PackageDescriptor inspect(byte[] databaseBytes) {
        if (databaseBytes == null || databaseBytes.length < 100) {
            throw new PackageDatabaseException("Package database is empty or truncated");
        }
        Path cache = null;
        try {
            cache = Files.createTempFile("cilexec-package-", ".db");
            Files.write(cache, databaseBytes);
            try (Connection connection = openImmutable(cache)) {
                validateSchema(connection);
                Map<String, String> metadata = readMetadata(connection);
                rejectLifecycleHooks(metadata);
                return new PackageDescriptor(
                        required(metadata, "namespace"),
                        required(metadata, "name"),
                        required(metadata, "version"),
                        required(metadata, "language_version"),
                        logicalHash(connection),
                        hash(databaseBytes),
                        readModules(connection),
                        readDependencies(connection),
                        readEntrypoints(connection),
                        readExports(connection),
                        readCapabilities(connection)
                );
            }
        } catch (PackageDatabaseException exception) {
            throw exception;
        } catch (SQLException | IOException | IllegalArgumentException exception) {
            throw new PackageDatabaseException("Cannot validate SQLite package database", exception);
        } finally {
            if (cache != null) {
                try {
                    Files.deleteIfExists(cache);
                } catch (IOException ignored) {
                    cache.toFile().deleteOnExit();
                }
            }
        }
    }

    static Connection openImmutable(Path database) throws SQLException {
        String uri = "jdbc:sqlite:file:" + database.toAbsolutePath()
                + "?mode=ro&immutable=1";
        Connection connection = DriverManager.getConnection(uri);
        try {
            harden(connection);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void harden(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA trusted_schema=OFF");
            statement.execute("PRAGMA foreign_keys=ON");
            try (ResultSet queryOnly = statement.executeQuery("PRAGMA query_only")) {
                if (!queryOnly.next() || queryOnly.getInt(1) != 1) {
                    throw new PackageDatabaseException("SQLite query_only mode was not enabled");
                }
            }
            try (ResultSet trustedSchema = statement.executeQuery("PRAGMA trusted_schema")) {
                if (!trustedSchema.next() || trustedSchema.getInt(1) != 0) {
                    throw new PackageDatabaseException("SQLite trusted_schema was not disabled");
                }
            }
            try (ResultSet databases = statement.executeQuery("PRAGMA database_list")) {
                int count = 0;
                while (databases.next()) {
                    if (!"main".equals(databases.getString("name"))) {
                        throw new PackageDatabaseException("Attached package databases are forbidden");
                    }
                    count++;
                }
                if (count != 1) {
                    throw new PackageDatabaseException("Package must expose exactly one SQLite database");
                }
            }
        }
    }

    private static void validateSchema(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        String query = "SELECT type,name,tbl_name,lower(coalesce(sql,'')) FROM sqlite_schema "
                + "WHERE name NOT LIKE 'sqlite_%' ORDER BY name";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                String type = rows.getString(1);
                String name = rows.getString(2);
                String tableName = rows.getString(3);
                String sql = rows.getString(4);
                switch (type) {
                    case "table" -> {
                        if (sql.contains("virtual table")) {
                            throw new PackageDatabaseException(
                                    "Virtual tables are forbidden: " + name);
                        }
                        tables.add(name);
                    }
                    case "index" -> {
                        if (sql.isBlank() || !REQUIRED_TABLES.contains(tableName)) {
                            throw new PackageDatabaseException(
                                    "Package index targets an unrecognized table: " + name);
                        }
                    }
                    case "view", "trigger" -> throw new PackageDatabaseException(
                            "Package views and triggers are forbidden: " + name);
                    default -> throw new PackageDatabaseException(
                            "Unrecognized SQLite schema object is forbidden: " + name);
                }
            }
        }
        if (!tables.containsAll(REQUIRED_TABLES)) {
            List<String> missing = REQUIRED_TABLES.stream()
                    .filter(name -> !tables.contains(name)).toList();
            throw new PackageDatabaseException("Package database is missing tables: " + missing);
        }
        List<String> extras = tables.stream()
                .filter(name -> !REQUIRED_TABLES.contains(name)).toList();
        if (!extras.isEmpty()) {
            throw new PackageDatabaseException("Unrecognized package tables are forbidden: " + extras);
        }
    }

    private static Map<String, String> readMetadata(Connection connection) throws SQLException {
        Map<String, String> metadata = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT metadata_key, metadata_value FROM package_metadata ORDER BY metadata_key")) {
            while (rows.next()) {
                String old = metadata.put(rows.getString(1), rows.getString(2));
                if (old != null) {
                    throw new PackageDatabaseException("Duplicate package metadata key");
                }
            }
        }
        return metadata;
    }

    private static void rejectLifecycleHooks(Map<String, String> metadata) {
        for (String key : LIFECYCLE_KEYS) {
            if (metadata.containsKey(key)) {
                throw new PackageDatabaseException("Package lifecycle hooks are forbidden: " + key);
            }
        }
    }

    private static String required(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            throw new PackageDatabaseException("Missing package metadata: " + key);
        }
        return value;
    }

    private static List<PackageIndex.Module> readModules(Connection connection)
            throws SQLException {
        List<PackageIndex.Module> modules = new ArrayList<>();
        String sql = "SELECT module_name,module_object_path,module_hash FROM package_module "
                + "ORDER BY module_name";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                modules.add(new PackageIndex.Module(rows.getString(1), rows.getString(2),
                        readHash(rows, 3, "module_hash")));
            }
        }
        return List.copyOf(modules);
    }

    private static List<PackageIndex.Dependency> readDependencies(Connection connection)
            throws SQLException {
        Set<String> columns = tableColumns(connection, "package_dependency");
        List<PackageIndex.Dependency> dependencies = new ArrayList<>();
        if (columns.containsAll(Set.of("dependency_namespace", "dependency_name",
                "version_constraint"))) {
            boolean hasOptional = columns.contains("optional");
            String sql = "SELECT dependency_namespace,dependency_name,version_constraint"
                    + (hasOptional ? ",optional" : "")
                    + " FROM package_dependency ORDER BY dependency_namespace,dependency_name";
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    dependencies.add(new PackageIndex.Dependency(rows.getString(1),
                            rows.getString(2), rows.getString(3),
                            hasOptional && rows.getBoolean(4)));
                }
            }
            return List.copyOf(dependencies);
        }
        if (!columns.contains("dependency_coordinate")) {
            throw new PackageDatabaseException(
                    "package_dependency has no supported dependency columns");
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT dependency_coordinate FROM package_dependency "
                             + "ORDER BY dependency_coordinate")) {
            while (rows.next()) {
                String coordinate = rows.getString(1);
                String[] parts = coordinate == null ? new String[0] : coordinate.split("/", 3);
                if (parts.length != 3) {
                    throw new PackageDatabaseException(
                            "Invalid package dependency coordinate: " + coordinate);
                }
                dependencies.add(new PackageIndex.Dependency(parts[0], parts[1], parts[2],
                        false));
            }
        }
        return List.copyOf(dependencies);
    }

    private static List<PackageIndex.Entrypoint> readEntrypoints(Connection connection)
            throws SQLException {
        List<PackageIndex.Entrypoint> entrypoints = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT entrypoint_name,module_name,function_name "
                             + "FROM package_entrypoint ORDER BY entrypoint_name")) {
            while (rows.next()) {
                entrypoints.add(new PackageIndex.Entrypoint(rows.getString(1),
                        rows.getString(2), rows.getString(3)));
            }
        }
        return List.copyOf(entrypoints);
    }

    private static List<PackageIndex.Export> readExports(Connection connection)
            throws SQLException {
        List<PackageIndex.Export> exports = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT export_name,module_name,symbol_name "
                             + "FROM package_export ORDER BY export_name")) {
            while (rows.next()) {
                exports.add(new PackageIndex.Export(rows.getString(1),
                        rows.getString(2), rows.getString(3)));
            }
        }
        return List.copyOf(exports);
    }

    private static List<PackageIndex.CapabilityRequirement> readCapabilities(
            Connection connection) throws SQLException {
        Set<String> columns = tableColumns(connection, "package_capability");
        String keyColumn;
        if (columns.contains("capability_key")) keyColumn = "capability_key";
        else if (columns.contains("capability_name")) keyColumn = "capability_name";
        else throw new PackageDatabaseException(
                    "package_capability has no capability key column");
        boolean hasRequired = columns.contains("required");
        boolean hasRationale = columns.contains("rationale");
        String sql = "SELECT " + keyColumn
                + (hasRequired ? ",required" : "")
                + (hasRationale ? ",rationale" : "")
                + " FROM package_capability ORDER BY " + keyColumn;
        List<PackageIndex.CapabilityRequirement> capabilities = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                int column = 2;
                boolean required = !hasRequired || rows.getBoolean(column++);
                String rationale = hasRationale ? rows.getString(column) : "";
                capabilities.add(new PackageIndex.CapabilityRequirement(rows.getString(1),
                        required, rationale == null ? "" : rationale));
            }
        }
        return List.copyOf(capabilities);
    }

    private static Set<String> tableColumns(Connection connection, String table)
            throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) columns.add(rows.getString("name").toLowerCase());
        }
        return Set.copyOf(columns);
    }

    private static ObjectHash readHash(ResultSet rows, int column, String name)
            throws SQLException {
        Object value = rows.getObject(column);
        if (value instanceof byte[] bytes && bytes.length == 32) {
            return new ObjectHash(HexFormat.of().formatHex(bytes));
        }
        if (value instanceof String text && text.matches("[0-9a-f]{64}")) {
            return new ObjectHash(text);
        }
        throw new PackageDatabaseException(name + " must contain a 32-byte SHA-256 hash");
    }

    private static String logicalHash(Connection connection) throws SQLException {
        MessageDigest digest = digest();
        for (String table : REQUIRED_TABLES) {
            if ("package_signature".equals(table)) {
                continue;
            }
            digest.update(table.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            List<byte[]> rows = canonicalRows(connection, table);
            rows.sort(SqlitePackageReader::compareUnsigned);
            for (byte[] row : rows) {
                digest.update(row);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<byte[]> canonicalRows(Connection connection, String table) throws SQLException {
        List<byte[]> canonical = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData columns = rows.getMetaData();
            while (rows.next()) {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    DataOutputStream output = new DataOutputStream(bytes);
                    for (int index = 1; index <= columns.getColumnCount(); index++) {
                        output.writeUTF(columns.getColumnName(index));
                        Object value = rows.getObject(index);
                        if (value == null) {
                            output.writeByte(0);
                        } else if (value instanceof byte[] blob) {
                            output.writeByte(1);
                            output.writeInt(blob.length);
                            output.write(blob);
                        } else {
                            output.writeByte(2);
                            output.writeUTF(value.getClass().getName());
                            output.writeUTF(String.valueOf(value));
                        }
                    }
                    output.flush();
                    canonical.add(bytes.toByteArray());
                } catch (IOException impossible) {
                    throw new AssertionError(impossible);
                }
            }
        }
        return canonical;
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Every Java runtime must provide SHA-256", impossible);
        }
    }
}
