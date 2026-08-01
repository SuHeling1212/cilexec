package com.follarce.package_manager;

import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramLinker;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Builds one immutable SQLite package from an explicit manifest and exact input bytes. */
public final class PackageBuilder {
    public static final String MANIFEST_FILE = "package.json";
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final FclCompiler compiler = new FclCompiler();
    private final SqlitePackageReader reader = new SqlitePackageReader();

    public PackageManifest parseManifest(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("package.json exceeds the 1 MiB limit");
        }
        String json = utf8(bytes, "package manifest");
        PackageManifest manifest;
        try {
            manifest = gson.fromJson(json, PackageManifest.class);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid package.json", failure);
        }
        if (manifest == null) throw new IllegalArgumentException("package.json is empty");
        return manifest;
    }

    public byte[] build(byte[] manifestBytes, Function<String, byte[]> contentLoader) {
        PackageManifest manifest = parseManifest(manifestBytes);
        return build(manifest, contentLoader);
    }

    public byte[] build(PackageManifest manifest, Function<String, byte[]> contentLoader) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(contentLoader, "contentLoader");
        Map<String, byte[]> content = loadContent(manifest, contentLoader);
        validateModules(manifest, content);
        Path temporary = null;
        try {
            temporary = Files.createTempFile("cilexec-package-build-", ".db");
            writeDatabase(temporary, manifest, content);
            byte[] database = Files.readAllBytes(temporary);
            PackageDescriptor descriptor = reader.inspect(database);
            if (!descriptor.coordinate().equals(manifest.namespace() + "/" + manifest.name()
                    + "/" + manifest.version())) {
                throw new IllegalStateException("Built package coordinate changed during validation");
            }
            return database;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot build package database", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    temporary.toFile().deleteOnExit();
                }
            }
        }
    }

    public PackageDescriptor build(Path sourceDirectory, Path outputDatabase) {
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(outputDatabase, "outputDatabase");
        Path root;
        try {
            root = sourceDirectory.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Package source directory is unavailable", failure);
        }
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Package source is not a directory");
        Path manifestPath = root.resolve(MANIFEST_FILE);
        byte[] manifestBytes = readRegularFile(root, manifestPath, MANIFEST_FILE);
        byte[] database = build(manifestBytes,
                path -> readRegularFile(root, root.resolve(path), path));
        Path absolute = outputDatabase.toAbsolutePath().normalize();
        Path fileName = absolute.getFileName();
        if (fileName == null || !fileName.toString().endsWith(".db")) {
            throw new IllegalArgumentException("Package output must end with .db");
        }
        if (Files.exists(absolute)) throw new IllegalArgumentException("Package output already exists: " + absolute);
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException("Package output directory does not exist");
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".cilexec-package-", ".tmp");
            Files.write(temporary, database);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute);
            }
            temporary = null;
            return reader.inspect(database);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot publish package database", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    temporary.toFile().deleteOnExit();
                }
            }
        }
    }

    private Map<String, byte[]> loadContent(PackageManifest manifest,
                                            Function<String, byte[]> contentLoader) {
        Map<String, byte[]> content = new LinkedHashMap<>();
        long totalBytes = 0;
        manifest.contentPaths().stream().sorted().forEach(path -> {
            byte[] bytes = Objects.requireNonNull(contentLoader.apply(path),
                    "Package content loader returned null for " + path);
            if (bytes.length > SqlitePackageReader.MAX_PACKAGE_RESOURCE_BYTES) {
                throw new IllegalArgumentException(
                        "Package resource exceeds the 16 MiB limit: " + path);
            }
            content.put(path, bytes.clone());
        });
        for (byte[] bytes : content.values()) {
            totalBytes += bytes.length;
            if (totalBytes > SqlitePackageReader.MAX_PACKAGE_DATABASE_BYTES) {
                throw new IllegalArgumentException(
                        "Package content exceeds the 64 MiB package limit");
            }
        }
        return Map.copyOf(content);
    }

    private void validateModules(PackageManifest manifest, Map<String, byte[]> content) {
        Map<String, FclProgram> compiled = new LinkedHashMap<>();
        for (PackageManifest.Module module : manifest.modules()) {
            compiled.put(module.name(), compiler.compile(utf8(content.get(module.path()),
                    "module " + module.name())));
        }
        for (PackageManifest.Entrypoint entrypoint : manifest.entrypoints()) {
            FclProgram.Function function = compiled.get(entrypoint.module())
                    .function(entrypoint.function());
            if (function == null) throw new IllegalArgumentException("Entrypoint function is missing: "
                    + entrypoint.module() + "." + entrypoint.function());
            if (!function.parameters().isEmpty()) {
                throw new IllegalArgumentException("Package entrypoint must not require arguments: "
                        + entrypoint.name());
            }
        }
        for (PackageManifest.Export export : manifest.exports()) {
            if (compiled.get(export.module()).function(export.symbol()) == null) {
                throw new IllegalArgumentException("Exported function is missing: "
                        + export.module() + "." + export.symbol());
            }
        }
        List<FclProgramLinker.Module> libraries = manifest.modules().stream().map(module ->
                new FclProgramLinker.Module(manifest.namespace() + "/" + manifest.name()
                        + "/" + manifest.version(), module.name(),
                        utf8(content.get(module.path()), "module " + module.name()), List.of()))
                .toList();
        new FclProgramLinker().link(compiler.compile(""), libraries);
    }

    private void writeDatabase(Path database, PackageManifest manifest,
                               Map<String, byte[]> content) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA page_size=4096");
                statement.execute("PRAGMA journal_mode=OFF");
                statement.execute("PRAGMA synchronous=OFF");
                statement.execute("PRAGMA user_version=" + SqlitePackageReader.FORMAT_VERSION);
            }
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE package_metadata(metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL) WITHOUT ROWID");
                statement.execute("CREATE TABLE package_file(file_path TEXT PRIMARY KEY, content BLOB NOT NULL) WITHOUT ROWID");
                statement.execute("CREATE TABLE package_module(module_name TEXT PRIMARY KEY, module_object_path TEXT NOT NULL UNIQUE, module_hash TEXT NOT NULL) WITHOUT ROWID");
                statement.execute("CREATE TABLE package_dependency(dependency_file_hash TEXT PRIMARY KEY, optional INTEGER NOT NULL) WITHOUT ROWID");
                statement.execute("CREATE TABLE package_entrypoint(entrypoint_name TEXT PRIMARY KEY, module_name TEXT NOT NULL, function_name TEXT NOT NULL) WITHOUT ROWID");
                statement.execute("CREATE TABLE package_export(export_name TEXT PRIMARY KEY, module_name TEXT NOT NULL, symbol_name TEXT NOT NULL) WITHOUT ROWID");
                statement.execute("CREATE TABLE package_capability(capability_key TEXT PRIMARY KEY, required INTEGER NOT NULL, rationale TEXT NOT NULL) WITHOUT ROWID");
            }
            insertMetadata(connection, manifest);
            insertFiles(connection, content);
            insertModules(connection, manifest, content);
            insertDependencies(connection, manifest);
            insertEntrypoints(connection, manifest);
            insertExports(connection, manifest);
            insertCapabilities(connection, manifest);
            connection.commit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot write package SQLite database", failure);
        }
    }

    private static void insertMetadata(Connection connection, PackageManifest manifest)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO package_metadata(metadata_key,metadata_value) VALUES (?,?)")) {
            Map<String, String> values = Map.of("namespace", manifest.namespace(),
                    "name", manifest.name(), "version", manifest.version(),
                    "language_version", manifest.languageVersion(),
                    "package_kind", manifest.kind().wireName());
            for (Map.Entry<String, String> entry : values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertFiles(Connection connection, Map<String, byte[]> content)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO package_file(file_path,content) VALUES (?,?)")) {
            for (Map.Entry<String, byte[]> entry : content.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                statement.setString(1, entry.getKey());
                statement.setBytes(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertModules(Connection connection, PackageManifest manifest,
                                      Map<String, byte[]> content) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO package_module(module_name,module_object_path,module_hash) VALUES (?,?,?)")) {
            for (PackageManifest.Module module : sorted(manifest.modules(), PackageManifest.Module::name)) {
                statement.setString(1, module.name());
                statement.setString(2, module.path());
                statement.setString(3, sha256(content.get(module.path())));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertDependencies(Connection connection, PackageManifest manifest)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO package_dependency(dependency_file_hash,optional) VALUES (?,?)")) {
            for (PackageManifest.Dependency dependency : sorted(manifest.dependencies(),
                    PackageManifest.Dependency::sha256)) {
                statement.setString(1, dependency.sha256());
                statement.setBoolean(2, dependency.optional());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertEntrypoints(Connection connection, PackageManifest manifest)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO package_entrypoint(entrypoint_name,module_name,function_name) VALUES (?,?,?)")) {
            for (PackageManifest.Entrypoint entrypoint : sorted(manifest.entrypoints(),
                    PackageManifest.Entrypoint::name)) {
                statement.setString(1, entrypoint.name());
                statement.setString(2, entrypoint.module());
                statement.setString(3, entrypoint.function());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertExports(Connection connection, PackageManifest manifest)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO package_export(export_name,module_name,symbol_name) VALUES (?,?,?)")) {
            for (PackageManifest.Export export : sorted(manifest.exports(), PackageManifest.Export::name)) {
                statement.setString(1, export.name());
                statement.setString(2, export.module());
                statement.setString(3, export.symbol());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertCapabilities(Connection connection, PackageManifest manifest)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO package_capability(capability_key,required,rationale) VALUES (?,?,?)")) {
            for (PackageManifest.Capability capability : sorted(manifest.capabilities(),
                    PackageManifest.Capability::key)) {
                statement.setString(1, capability.key());
                statement.setBoolean(2, capability.required());
                statement.setString(3, capability.rationale());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static byte[] readRegularFile(Path root, Path candidate, String logicalPath) {
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(root) || !Files.isRegularFile(real)) {
                throw new IllegalArgumentException("Package content escapes the source directory: "
                        + logicalPath);
            }
            long size = Files.size(real);
            int limit = MANIFEST_FILE.equals(logicalPath) ? MAX_MANIFEST_BYTES
                    : SqlitePackageReader.MAX_PACKAGE_RESOURCE_BYTES;
            if (size > limit) {
                throw new IllegalArgumentException("Package content exceeds its size limit: "
                        + logicalPath);
            }
            return Files.readAllBytes(real);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read package content: " + logicalPath, failure);
        }
    }

    private static String utf8(byte[] bytes, String description) {
        if (bytes == null) throw new IllegalArgumentException(description + " is missing");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(description + " is not valid UTF-8", failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
    }

    private static <T> List<T> sorted(List<T> values, Function<T, String> key) {
        List<T> result = new ArrayList<>(values);
        result.sort(Comparator.comparing(key));
        return List.copyOf(result);
    }

}
