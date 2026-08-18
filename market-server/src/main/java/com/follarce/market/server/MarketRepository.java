package com.follarce.market.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class MarketRepository {
    static final String API_VERSION = "cilexec.market/v1";
    static final long MAX_PACKAGE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_CATALOG_BYTES = 1024L * 1024;
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Gson JSON = new Gson();
    private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path repository;
    private final Path catalog;
    private volatile Snapshot snapshot;

    MarketRepository(Path repository, Path catalog) throws IOException, SQLException {
        this.repository = ensureRepository(repository).toRealPath(LinkOption.NOFOLLOW_LINKS);
        this.catalog = catalog.toAbsolutePath().normalize();
        ensureCatalog(this.catalog);
        this.snapshot = loadSnapshot();
    }

    /** Rebuilds and validates a complete repository view before publishing it atomically. */
    synchronized void refresh() throws IOException, SQLException {
        snapshot = loadSnapshot();
    }

    /** Loads the repository view for the live catalog file. */
    private Snapshot loadSnapshot() throws IOException, SQLException {
        return loadSnapshot(catalog);
    }

    byte[] index() {
        return snapshot.index().clone();
    }

    /** All published package records, sorted by coordinate. */
    List<PackageRecord> published() {
        return snapshot.packages().values().stream()
                .map(PublishedPackage::record)
                .sorted(Comparator.comparing(PackageRecord::coordinate))
                .toList();
    }

    /** Validates a candidate package database and returns its identity without touching the
     *  repository. The caller must still call {@link #publish} to make it visible. */
    synchronized StagedPackage stage(Path source) throws IOException, SQLException {
        Path real = validateSource(source);
        Map<String, String> metadata = readMetadata(real);
        String coordinate = required(metadata, "namespace") + "/"
                + required(metadata, "name") + "/" + required(metadata, "version");
        return new StagedPackage(real, coordinate,
                required(metadata, "package_kind"), sha256(real), Files.size(real));
    }

    /** Copies a staged package into the repository layout and atomically publishes it in the
     *  catalog. The catalog is fully re-validated before the atomic replace; a failed
     *  publication leaves both the repository and the catalog untouched. */
    synchronized void publish(StagedPackage staged, String summary, String description,
                              List<String> tags) throws IOException, SQLException {
        String[] identity = staged.coordinate().split("/", -1);
        Path target = repository.resolve("packages").resolve(identity[0]).resolve(identity[1])
                .resolve(identity[2]).resolve(identity[1] + ".db");
        rejectSymlinkComponents(target);
        target.getParent().toFile().mkdirs();
        boolean copied = false;
        boolean catalogCommitted = false;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && sha256(target).equals(staged.sha256())) {
                // Already published with identical content; only the catalog entry may change.
            } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Different package content already published for "
                        + staged.coordinate());
            } else {
                copied = true;
                Files.copy(staged.source(), target);
            }
            Map<String, Publication> next = new LinkedHashMap<>(readPublications(catalog));
            Publication previous = next.get(staged.coordinate());
            next.put(staged.coordinate(), new Publication(
                    summary != null ? summary
                            : previous != null ? previous.summary() : "",
                    description != null ? description
                            : previous != null ? previous.description() : "",
                    tags != null ? List.copyOf(tags)
                            : previous != null ? previous.tags() : List.of()));
            writeCatalogVerified(next);
            catalogCommitted = true;
            refresh();
        } catch (IOException | SQLException | RuntimeException failure) {
            // A failed publication must leave the repository untouched: roll the package
            // file back, but only as long as the catalog was not already committed (once
            // the catalog references the file, deleting it would corrupt the repository).
            if (copied && !catalogCommitted) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    /** The on-disk location a package coordinate is stored at (also used by publish). */
    String packageFile(String coordinate) {
        String[] identity = coordinate.split("/", -1);
        if (identity.length != 3) {
            throw new IllegalArgumentException("Invalid coordinate: " + coordinate);
        }
        return repository.resolve("packages").resolve(identity[0]).resolve(identity[1])
                .resolve(identity[2]).resolve(identity[1] + ".db").toString();
    }

    /** Removes one coordinate from the catalog, leaving its package file in place. Returns
     *  false when the coordinate was not published. */
    synchronized boolean unpublish(String coordinate) throws IOException, SQLException {
        Map<String, Publication> next = new LinkedHashMap<>(readPublications(catalog));
        if (next.remove(coordinate) == null) return false;
        writeCatalogVerified(next);
        refresh();
        return true;
    }

    /** Writes the catalog atomically after proving that it loads cleanly against the current
     *  repository contents. */
    private void writeCatalogVerified(Map<String, Publication> publications)
            throws IOException, SQLException {
        Path temporary = catalog.resolveSibling(catalog.getFileName() + ".pending");
        try {
            Map<String, Object> document = new LinkedHashMap<>();
            for (Map.Entry<String, Publication> entry : publications.entrySet()) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                if (!entry.getValue().summary().isEmpty()) {
                    metadata.put("summary", entry.getValue().summary());
                }
                if (!entry.getValue().description().isEmpty()) {
                    metadata.put("description", entry.getValue().description());
                }
                if (!entry.getValue().tags().isEmpty()) {
                    metadata.put("tags", entry.getValue().tags());
                }
                document.put(entry.getKey(), metadata);
            }
            Files.writeString(temporary, PRETTY_JSON.toJson(document) + "\n",
                    StandardCharsets.UTF_8);
            loadSnapshot(temporary);
            try {
                Files.move(temporary, catalog, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, catalog, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Snapshot loadSnapshot(Path catalogSource) throws IOException, SQLException {
        Map<String, Publication> publications = readPublications(catalogSource);
        List<PublishedPackage> loaded = new ArrayList<>();
        for (Map.Entry<String, Publication> entry : publications.entrySet()) {
            loaded.add(load(entry.getKey(), entry.getValue()));
        }
        loaded.sort(Comparator.comparing(value -> value.record().coordinate()));
        Map<String, PublishedPackage> byHash = new LinkedHashMap<>();
        for (PublishedPackage value : loaded) {
            if (byHash.put(value.record().sha256(), value) != null) {
                throw new IllegalArgumentException("Duplicate published package SHA-256");
            }
        }
        Map<String, PublishedPackage> packages = Map.copyOf(byHash);
        Map<String, Object> document = Map.of("apiVersion", API_VERSION,
                "packages", loaded.stream().map(value -> value.record().asMap()).toList());
        byte[] index = (PRETTY_JSON.toJson(document) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        return new Snapshot(packages, index);
    }

    /** Checks that a source file is a valid package database and returns its real path. */
    private Path validateSource(Path source) throws IOException {
        Path real = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(real) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Package source is not a regular file: " + source);
        }
        long bytes = Files.size(real);
        if (bytes < 1 || bytes > MAX_PACKAGE_BYTES) {
            throw new IllegalArgumentException("Package size must be from 1 byte to 64 MiB");
        }
        String jdbc = "jdbc:sqlite:" + real.toUri() + "?mode=ro&immutable=1";
        try (Connection connection = DriverManager.getConnection(jdbc);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                if (!version.next() || version.getInt(1) != 2) {
                    throw new IllegalArgumentException("Unsupported package format: "
                            + "expected SQLite user_version 2");
                }
            }
        } catch (SQLException invalid) {
            throw new IllegalArgumentException("Package source is not a SQLite database: " + source,
                    invalid);
        }
        return real;
    }

    /** Reads the metadata table of a package database (also used by one-shot publish). */
    public static Map<String, String> readMetadata(Path database) throws SQLException {
        Map<String, String> metadata = new LinkedHashMap<>();
        String jdbc = "jdbc:sqlite:" + database.toUri() + "?mode=ro&immutable=1";
        try (Connection connection = DriverManager.getConnection(jdbc);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT metadata_key,metadata_value FROM package_metadata")) {
                while (rows.next()) metadata.put(rows.getString(1), rows.getString(2));
            }
        }
        return metadata;
    }

    record StagedPackage(Path source, String coordinate, String kind, String sha256, long bytes) { }

    PublishedPackage require(String sha256) {
        return snapshot.packages().get(sha256);
    }

    boolean unchanged(PublishedPackage value) throws IOException {
        Path path = value.path();
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path)
                && Files.size(path) == value.record().bytes()
                && sha256(path).equals(value.record().sha256());
    }

    private PublishedPackage load(String coordinate, Publication publication)
            throws IOException, SQLException {
        String[] identity = coordinate.split("/", -1);
        if (identity.length != 3 || !safe(identity[0]) || !safe(identity[1])
                || !safe(identity[2])) {
            throw new IllegalArgumentException("Invalid catalog coordinate: " + coordinate);
        }
        Path candidate = repository.resolve("packages").resolve(identity[0]).resolve(identity[1])
                .resolve(identity[2]).resolve(identity[1] + ".db");
        rejectSymlinkComponents(candidate);
        if (Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Published package is not a regular file: "
                    + coordinate);
        }
        Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(repository)) {
            throw new IllegalArgumentException("Published package escapes repository: "
                    + coordinate);
        }
        long bytes = Files.size(real);
        if (bytes < 1 || bytes > MAX_PACKAGE_BYTES) {
            throw new IllegalArgumentException("Published package size is invalid: " + coordinate);
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        List<Dependency> dependencies = new ArrayList<>();
        String jdbc = "jdbc:sqlite:" + real.toUri() + "?mode=ro&immutable=1";
        try (Connection connection = DriverManager.getConnection(jdbc);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                if (!version.next() || version.getInt(1) != 2) {
                    throw new IllegalArgumentException("Unsupported package format: " + coordinate);
                }
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT metadata_key,metadata_value FROM package_metadata")) {
                while (rows.next()) metadata.put(rows.getString(1), rows.getString(2));
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT dependency_file_hash,optional FROM package_dependency "
                            + "ORDER BY dependency_file_hash LIMIT 258")) {
                while (rows.next()) {
                    if (dependencies.size() >= 256) {
                        throw new IllegalArgumentException("Too many package dependencies: "
                                + coordinate);
                    }
                    String dependency = rows.getString(1);
                    if (!SHA256.matcher(dependency).matches()) {
                        throw new IllegalArgumentException("Invalid package dependency: "
                                + coordinate);
                    }
                    dependencies.add(new Dependency(dependency, rows.getBoolean(2)));
                }
            }
        }
        String actualCoordinate = required(metadata, "namespace") + "/"
                + required(metadata, "name") + "/" + required(metadata, "version");
        if (!actualCoordinate.equals(coordinate)) {
            throw new IllegalArgumentException("Package identity does not match catalog: "
                    + coordinate);
        }
        String kind = required(metadata, "package_kind");
        String hash = sha256(real);
        PackageRecord record = new PackageRecord(identity[0], identity[1], identity[2], kind,
                coordinate, hash, "/market/v1/" + hash, bytes, List.copyOf(dependencies),
                publication.summary(), publication.description(), publication.tags());
        return new PublishedPackage(real, record);
    }

    private static Map<String, Publication> readPublications(Path catalog) throws IOException {
        if (Files.isSymbolicLink(catalog)
                || !Files.isRegularFile(catalog, LinkOption.NOFOLLOW_LINKS)
                || Files.size(catalog) > MAX_CATALOG_BYTES) {
            throw new IllegalArgumentException("Catalog must be a regular JSON file up to 1 MiB");
        }
        Object decoded = JSON.fromJson(Files.readString(catalog, StandardCharsets.UTF_8),
                Object.class);
        if (!(decoded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Catalog must contain a JSON object");
        }
        Map<String, Publication> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : root.entrySet()) {
            if (!(entry.getKey() instanceof String coordinate)
                    || !(entry.getValue() instanceof Map<?, ?> metadata)) {
                throw new IllegalArgumentException("Invalid catalog publication");
            }
            String summary = optional(metadata.get("summary"));
            String description = optional(metadata.get("description"));
            List<String> tags = strings(metadata.get("tags"));
            result.put(coordinate, new Publication(summary, description, tags));
        }
        return Map.copyOf(result);
    }

    static int compareVersions(String left, String right) {
        List<String> a = versionParts(left);
        List<String> b = versionParts(right);
        int count = Math.max(a.size(), b.size());
        for (int index = 0; index < count; index++) {
            String x = index < a.size() ? a.get(index) : "";
            String y = index < b.size() ? b.get(index) : "";
            boolean xn = x.chars().allMatch(Character::isDigit) && !x.isEmpty();
            boolean yn = y.chars().allMatch(Character::isDigit) && !y.isEmpty();
            int compared = xn && yn ? new java.math.BigInteger(x).compareTo(
                    new java.math.BigInteger(y)) : x.compareToIgnoreCase(y);
            if (compared != 0) return compared;
        }
        return left.compareTo(right);
    }

    private static List<String> versionParts(String value) {
        return List.of(value.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)|[._-]"));
    }

    private static Path requireDirectory(Path path) {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Repository is not a regular directory: " + path);
        }
        return path;
    }

    /**
     * A single-file deployment: the market creates its own layout on first start.
     * An existing repository is validated strictly; a missing one is created with
     * the packages subdirectory, and a missing catalog is initialized to an empty
     * object so the JAR needs no external setup step.
     */
    private static Path ensureRepository(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return requireDirectory(path);
        Files.createDirectories(path.resolve("packages"));
        return path;
    }

    private static void ensureCatalog(Path catalog) throws IOException {
        if (Files.exists(catalog, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(catalog)) {
            throw new IllegalArgumentException("Catalog must be a regular JSON file, not a symlink");
        }
        Files.writeString(catalog, "{}\n", StandardCharsets.UTF_8);
    }

    private void rejectSymlinkComponents(Path candidate) throws IOException {
        Path relative = repository.relativize(candidate.toAbsolutePath().normalize());
        Path current = repository;
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Published package path contains a symlink: "
                        + candidate);
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                for (int count; (count = input.read(buffer)) >= 0; ) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String required(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (!safe(value)) throw new IllegalArgumentException("Invalid package metadata: " + key);
        return value;
    }

    private static boolean safe(String value) {
        return value != null && SAFE.matcher(value).matches();
    }

    private static String optional(Object value) {
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > 16_384
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid catalog text");
        }
        return text;
    }

    private static List<String> strings(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> values) || values.size() > 128) {
            throw new IllegalArgumentException("Invalid catalog tags");
        }
        return values.stream().map(MarketRepository::optional).toList();
    }

    record PublishedPackage(Path path, PackageRecord record) { }
    private record Snapshot(Map<String, PublishedPackage> packages, byte[] index) {
        private Snapshot {
            packages = Map.copyOf(packages);
            index = index.clone();
        }
    }
    private record Publication(String summary, String description, List<String> tags) { }
    record Dependency(String sha256, boolean optional) { }

    record PackageRecord(String namespace, String name, String version, String kind,
                         String coordinate, String sha256, String download, long bytes,
                         List<Dependency> dependencies, String summary,
                         String description, List<String> tags) {
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("namespace", namespace);
            result.put("name", name);
            result.put("version", version);
            result.put("kind", kind);
            result.put("coordinate", coordinate);
            result.put("download", download);
            result.put("sha256", sha256);
            result.put("bytes", bytes);
            result.put("mediaType", "application/vnd.sqlite3");
            result.put("dependencies", dependencies.stream().map(dependency -> Map.of(
                    "sha256", dependency.sha256(), "optional", dependency.optional())).toList());
            if (!summary.isEmpty()) result.put("summary", summary);
            if (!description.isEmpty()) result.put("description", description);
            if (!tags.isEmpty()) result.put("tags", tags);
            return Map.copyOf(result);
        }
    }
}
