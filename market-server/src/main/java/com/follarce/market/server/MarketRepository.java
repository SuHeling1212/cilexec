package com.follarce.market.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
    private final Map<String, PublishedPackage> packages;
    private final byte[] index;

    MarketRepository(Path repository, Path catalog) throws IOException, SQLException {
        this.repository = requireDirectory(repository).toRealPath(LinkOption.NOFOLLOW_LINKS);
        Map<String, Publication> publications = readPublications(catalog);
        List<PublishedPackage> loaded = new ArrayList<>();
        for (Map.Entry<String, Publication> entry : publications.entrySet()) {
            loaded.add(load(entry.getKey(), entry.getValue()));
        }
        markLatest(loaded);
        loaded.sort(Comparator.comparing(value -> value.record().coordinate()));
        Map<String, PublishedPackage> byHash = new LinkedHashMap<>();
        for (PublishedPackage value : loaded) {
            if (byHash.put(value.record().sha256(), value) != null) {
                throw new IllegalArgumentException("Duplicate published package SHA-256");
            }
        }
        this.packages = Map.copyOf(byHash);
        Map<String, Object> document = Map.of("apiVersion", API_VERSION,
                "packages", loaded.stream().map(value -> value.record().asMap()).toList());
        this.index = (PRETTY_JSON.toJson(document) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    byte[] index() {
        return index.clone();
    }

    PublishedPackage require(String sha256) {
        return packages.get(sha256);
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
                coordinate, hash, "/market/v1/" + hash, bytes, List.copyOf(dependencies), false,
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

    private static void markLatest(List<PublishedPackage> packages) {
        Map<String, PublishedPackage> latest = new LinkedHashMap<>();
        for (PublishedPackage value : packages) {
            String key = value.record().namespace() + "\n" + value.record().name();
            PublishedPackage previous = latest.get(key);
            if (previous == null || compareVersions(value.record().version(),
                    previous.record().version()) > 0) latest.put(key, value);
        }
        Set<String> latestIds = latest.values().stream().map(value -> value.record().sha256())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (int index = 0; index < packages.size(); index++) {
            PublishedPackage value = packages.get(index);
            packages.set(index, new PublishedPackage(value.path(), value.record().withLatest(
                    latestIds.contains(value.record().sha256()))));
        }
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
    private record Publication(String summary, String description, List<String> tags) { }
    record Dependency(String sha256, boolean optional) { }

    record PackageRecord(String namespace, String name, String version, String kind,
                         String coordinate, String sha256, String download, long bytes,
                         List<Dependency> dependencies, boolean latest, String summary,
                         String description, List<String> tags) {
        PackageRecord withLatest(boolean value) {
            return new PackageRecord(namespace, name, version, kind, coordinate, sha256,
                    download, bytes, dependencies, value, summary, description, tags);
        }

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
            result.put("latest", latest);
            if (!summary.isEmpty()) result.put("summary", summary);
            if (!description.isEmpty()) result.put("description", description);
            if (!tags.isEmpty()) result.put("tags", tags);
            return Map.copyOf(result);
        }
    }
}
