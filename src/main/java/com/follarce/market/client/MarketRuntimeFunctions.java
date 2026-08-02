package com.follarce.market.client;

import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclSuspension;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Java implementation of the built-in, user-scoped CilExec package market client. */
public final class MarketRuntimeFunctions {
    public static final String API_VERSION = "cilexec.market/v1";
    public static final String CLIENT_VERSION = "2.0.0";
    public static final String ORIGIN_VARIABLE = "MARKET_ORIGIN";

    private static final String ROOT = "/market";
    private static final String PACKAGE_ROOT = ROOT + "/packages";
    private static final String INDEX_PATH = ROOT + "/index.json";
    private static final String RECEIPTS_PATH = ROOT + "/installed.json";
    private static final int MAX_PACKAGES = 10_000;
    private static final int MAX_DEPENDENCIES = 256;
    private static final int MAX_DEPENDENCY_DEPTH = 64;
    private static final long MAX_PACKAGE_BYTES = 64L * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Gson JSON = new Gson();

    private final Host host;

    public MarketRuntimeFunctions(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    public void register(FclFunctionRegistry registry) {
        Objects.requireNonNull(registry, "registry")
                .register("market", "configure", this::configure)
                .register("market", "origin", this::origin)
                .registerContextual("market", "update", this::update)
                .registerContextual("market", "search", this::search)
                .registerContextual("market", "info", this::info)
                .registerContextual("market", "download", this::download)
                .registerContextual("market", "install", this::install)
                .register("market", "list", this::list)
                .registerContextual("market", "upgrade", this::upgrade)
                .register("market", "uninstall", this::uninstall)
                .register("market", "help", this::help)
                .register("market", "run", this::run);
    }

    private Object configure(List<Object> arguments) {
        arity(arguments, 1, "market.configure");
        String value = normalizeOrigin(text(arguments.getFirst(), "market origin"));
        host.setEnvironment(ORIGIN_VARIABLE, value);
        return Map.of("ok", true, "origin", value);
    }

    private Object origin(List<Object> arguments) {
        arity(arguments, 0, "market.origin");
        return host.environment(ORIGIN_VARIABLE);
    }

    private Object update(List<Object> arguments, FclFunctionRegistry.Invocation invocation) {
        arity(arguments, 0, "market.update");
        String origin = requireOrigin();
        Object delivered = host.httpGet(origin + "/market/v1/index.json", invocation);
        if (!(delivered instanceof Map<?, ?> response)) {
            throw new FclRuntimeException("Market index returned an invalid HTTP response");
        }
        long status = integer(response.get("status"), "market index HTTP status");
        if (status != 200) {
            throw new FclRuntimeException("Market index request failed with HTTP status " + status);
        }
        String body = text(response.get("body"), "market index response body");
        MarketIndex index = parseIndex(body);
        ensureStorage();
        host.writeText(INDEX_PATH, body);
        return Map.of("ok", true, "packages", (long) index.packages().size(),
                "path", INDEX_PATH);
    }

    private Object search(List<Object> arguments, FclFunctionRegistry.Invocation invocation) {
        arity(arguments, 1, "market.search");
        String query = text(arguments.getFirst(), "market search query")
                .trim().toLowerCase(Locale.ROOT);
        MarketIndex index = index(invocation);
        List<Map<String, Object>> matches = index.packages().stream()
                .filter(record -> matches(record, query))
                .map(PackageRecord::asMap).toList();
        return matches;
    }

    private Object info(List<Object> arguments, FclFunctionRegistry.Invocation invocation) {
        arity(arguments, 1, "market.info");
        String packageId = packageId(arguments.getFirst());
        return find(index(invocation), packageId).map(PackageRecord::asMap).orElse(null);
    }

    private Object download(List<Object> arguments, FclFunctionRegistry.Invocation invocation) {
        arity(arguments, 1, "market.download");
        String packageId = packageId(arguments.getFirst());
        PackageRecord record = requireRecord(index(invocation), packageId);
        return downloadRecord(record, invocation);
    }

    private Object install(List<Object> arguments, FclFunctionRegistry.Invocation invocation) {
        arity(arguments, 1, "market.install");
        String packageId = packageId(arguments.getFirst());
        MarketIndex index = index(invocation);
        return installExact(index, packageId, new LinkedHashSet<>(), invocation);
    }

    private Object list(List<Object> arguments) {
        arity(arguments, 0, "market.list");
        return receipts().stream().map(Receipt::asMap).toList();
    }

    private Object uninstall(List<Object> arguments) {
        arity(arguments, 1, "market.uninstall");
        String packageId = packageId(arguments.getFirst());
        List<Receipt> current = receipts();
        List<Receipt> retained = new ArrayList<>();
        boolean removed = false;
        for (Receipt receipt : current) {
            if (!receipt.sha256().equals(packageId)) {
                retained.add(receipt);
                continue;
            }
            removed = host.removeBinding(receipt.environmentId(), receipt.binding()) || removed;
            host.removeFile(packagePath(packageId));
        }
        if (retained.size() != current.size()) saveReceipts(retained);
        return removed;
    }

    private Object upgrade(List<Object> arguments, FclFunctionRegistry.Invocation invocation) {
        arity(arguments, 0, "market.upgrade");
        String stateKey = "cilexec.market.upgrade.updated." + invocation.expressionId();
        if (!invocation.continuation().scope().contains(stateKey)) {
            update(List.of(), invocation);
            invocation.continuation().scope().put(stateKey, true);
        }
        MarketIndex index = loadIndex();
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (Receipt receipt : List.copyOf(receipts())) {
            PackageRecord latest = latest(index, receipt.namespace(), receipt.name());
            if (latest == null || latest.sha256().equals(receipt.sha256())) continue;
            prepareInstall(index, latest.sha256(), new LinkedHashSet<>(), invocation);
            host.removeBinding(receipt.environmentId(), receipt.binding());
            try {
                Map<String, Object> outcome = installExact(index, latest.sha256(),
                        new LinkedHashSet<>(), invocation);
                outcomes.add(outcome);
            } catch (FclSuspension suspension) {
                throw suspension;
            } catch (RuntimeException failure) {
                host.pin(receipt.environmentId(), receipt.binding(), receipt.packageHash());
                throw failure;
            }
        }
        invocation.continuation().scope().remove(stateKey);
        return Map.of("ok", true, "upgraded", List.copyOf(outcomes));
    }

    /** Downloads the new release and installs its dependencies before replacing an old binding. */
    private void prepareInstall(MarketIndex index, String packageId, Set<String> visiting,
                                FclFunctionRegistry.Invocation invocation) {
        if (visiting.size() >= MAX_DEPENDENCY_DEPTH || !visiting.add(packageId)) {
            throw new FclRuntimeException("Cyclic or excessively deep market dependency: "
                    + packageId);
        }
        try {
            PackageRecord record = requireRecord(index, packageId);
            for (Dependency dependency : record.dependencies()) {
                if (!dependency.optional()) {
                    installExact(index, dependency.sha256(), visiting, invocation);
                }
            }
            downloadRecord(record, invocation);
        } finally {
            visiting.remove(packageId);
        }
    }

    private Object help(List<Object> arguments) {
        arity(arguments, 0, "market.help");
        return helpText();
    }

    private Object run(List<Object> arguments) {
        arity(arguments, 0, "market.run");
        return Map.of("name", "CilExec Market", "version", CLIENT_VERSION,
                "help", helpText());
    }

    private Map<String, Object> installExact(MarketIndex index, String packageId,
                                             Set<String> visiting,
                                             FclFunctionRegistry.Invocation invocation) {
        Receipt existing = receipts().stream()
                .filter(receipt -> receipt.sha256().equals(packageId)).findFirst().orElse(null);
        if (existing != null) {
            return Map.of("ok", true, "alreadyInstalled", true,
                    "receipt", existing.asMap());
        }
        if (visiting.size() >= MAX_DEPENDENCY_DEPTH) {
            throw new FclRuntimeException("Market dependency depth exceeds "
                    + MAX_DEPENDENCY_DEPTH);
        }
        if (!visiting.add(packageId)) {
            throw new FclRuntimeException("Cyclic market dependency: " + packageId);
        }
        PackageRecord record = requireRecord(index, packageId);
        try {
            for (Dependency dependency : record.dependencies()) {
                if (!dependency.optional()) {
                    installExact(index, dependency.sha256(), visiting, invocation);
                }
            }
            downloadRecord(record, invocation);
            Map<String, Object> installed = host.install(packagePath(packageId), record.name());
            if (!packageId.equals(installed.get("sha256"))
                    || !record.coordinate().equals(installed.get("coordinate"))) {
                Object environment = installed.get("environmentId");
                Object binding = installed.get("binding");
                if (environment instanceof String environmentId && binding instanceof String name) {
                    host.removeBinding(environmentId, name);
                }
                throw new FclRuntimeException(
                        "Downloaded package identity does not match the market index");
            }
            Receipt receipt = new Receipt(packageId, record.coordinate(), record.namespace(),
                    record.name(), record.version(), text(installed.get("binding"),
                    "installed package binding"), text(installed.get("environmentId"),
                    "installed package environment"), text(installed.get("hash"),
                    "installed package hash"));
            saveReceipt(receipt);
            return Map.of("ok", true, "package", record.asMap(),
                    "installed", Map.copyOf(installed));
        } finally {
            visiting.remove(packageId);
        }
    }

    private Map<String, Object> downloadRecord(PackageRecord record,
                                               FclFunctionRegistry.Invocation invocation) {
        ensureStorage();
        String path = packagePath(record.sha256());
        if (!host.fileMatches(path, record.sha256(), record.bytes())) {
            host.download(requireOrigin() + record.download(), path, invocation);
        }
        if (!host.fileMatches(path, record.sha256(), record.bytes())) {
            host.removeFile(path);
            throw new FclRuntimeException("Downloaded package SHA-256 or size does not match index");
        }
        return Map.of("ok", true, "path", path, "package", record.asMap());
    }

    private MarketIndex index(FclFunctionRegistry.Invocation invocation) {
        requireOrigin();
        ensureStorage();
        if (!host.exists(INDEX_PATH)) update(List.of(), invocation);
        return loadIndex();
    }

    private MarketIndex loadIndex() {
        if (!host.exists(INDEX_PATH)) {
            throw new FclRuntimeException("Market index is missing; call market.update()");
        }
        return parseIndex(host.readText(INDEX_PATH));
    }

    private MarketIndex parseIndex(String source) {
        Object decoded;
        try {
            decoded = JSON.fromJson(source, Object.class);
        } catch (JsonParseException malformed) {
            throw new FclRuntimeException("Market index is not valid JSON", malformed);
        }
        if (!(decoded instanceof Map<?, ?> root)
                || !API_VERSION.equals(root.get("apiVersion"))
                || !(root.get("packages") instanceof List<?> values)
                || values.size() > MAX_PACKAGES) {
            throw new FclRuntimeException("Market index does not satisfy " + API_VERSION);
        }
        List<PackageRecord> packages = new ArrayList<>(values.size());
        Set<String> ids = new LinkedHashSet<>();
        Set<String> coordinates = new LinkedHashSet<>();
        for (Object value : values) {
            PackageRecord record = parseRecord(value);
            if (!ids.add(record.sha256())) {
                throw new FclRuntimeException("Duplicate package SHA-256 in market index");
            }
            if (!coordinates.add(record.coordinate())) {
                throw new FclRuntimeException("Duplicate package coordinate in market index");
            }
            packages.add(record);
        }
        return new MarketIndex(List.copyOf(packages));
    }

    private PackageRecord parseRecord(Object value) {
        if (!(value instanceof Map<?, ?>)) invalidRecord();
        Map<?, ?> record = (Map<?, ?>) value;
        String namespace = safeName(record.get("namespace"), "namespace");
        String name = safeName(record.get("name"), "name");
        String version = safeName(record.get("version"), "version");
        String coordinate = text(record.get("coordinate"), "package coordinate");
        if (!coordinate.equals(namespace + "/" + name + "/" + version)) invalidRecord();
        String sha256 = packageId(record.get("sha256"));
        String download = text(record.get("download"), "package download path");
        if (!download.equals("/market/v1/" + sha256)) invalidRecord();
        long bytes = integer(record.get("bytes"), "package byte size");
        if (bytes < 1 || bytes > MAX_PACKAGE_BYTES) invalidRecord();
        String kind = safeName(record.get("kind"), "package kind");
        if (!(record.get("dependencies") instanceof List<?>)) invalidRecord();
        List<?> dependencyValues = (List<?>) record.get("dependencies");
        if (dependencyValues.size() > MAX_DEPENDENCIES) invalidRecord();
        List<Dependency> dependencies = new ArrayList<>();
        Set<String> dependencyIds = new LinkedHashSet<>();
        for (Object dependencyValue : dependencyValues) {
            if (!(dependencyValue instanceof Map<?, ?>)) invalidRecord();
            Map<?, ?> dependency = (Map<?, ?>) dependencyValue;
            String dependencyId = packageId(dependency.get("sha256"));
            if (!(dependency.get("optional") instanceof Boolean)
                    || !dependencyIds.add(dependencyId)) invalidRecord();
            boolean optional = (Boolean) dependency.get("optional");
            dependencies.add(new Dependency(dependencyId, optional));
        }
        boolean latest = Boolean.TRUE.equals(record.get("latest"));
        return new PackageRecord(namespace, name, version, kind, coordinate, sha256, download,
                bytes, List.copyOf(dependencies), latest, optionalText(record.get("summary")),
                optionalText(record.get("description")), stringList(record.get("tags")));
    }

    private List<Receipt> receipts() {
        ensureStorage();
        if (!host.exists(RECEIPTS_PATH)) return List.of();
        Object decoded;
        try {
            decoded = JSON.fromJson(host.readText(RECEIPTS_PATH), Object.class);
        } catch (JsonParseException malformed) {
            throw new FclRuntimeException("Market receipts are not valid JSON", malformed);
        }
        if (!(decoded instanceof List<?> values) || values.size() > MAX_PACKAGES) {
            throw new FclRuntimeException("Market receipts are invalid");
        }
        List<Receipt> receipts = new ArrayList<>();
        Set<String> bindings = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> item)) {
                throw new FclRuntimeException("Market receipt is invalid");
            }
            Receipt receipt;
            try {
                receipt = new Receipt(packageId(item.get("sha256")),
                        text(item.get("coordinate"), "receipt coordinate"),
                        safeName(item.get("namespace"), "receipt namespace"),
                        safeName(item.get("name"), "receipt name"),
                        safeName(item.get("version"), "receipt version"),
                        text(item.get("binding"), "receipt binding"),
                        UUID.fromString(text(item.get("environmentId"), "receipt environment"))
                                .toString(),
                        packageId(item.get("packageHash")));
            } catch (IllegalArgumentException invalid) {
                throw new FclRuntimeException("Market receipt is invalid", invalid);
            }
            if (!receipt.coordinate().equals(receipt.namespace() + "/" + receipt.name()
                    + "/" + receipt.version()) || !bindings.add(receipt.environmentId()
                    + "\n" + receipt.binding())) {
                throw new FclRuntimeException("Market receipt identity is invalid");
            }
            receipts.add(receipt);
        }
        return List.copyOf(receipts);
    }

    private void saveReceipt(Receipt receipt) {
        List<Receipt> updated = new ArrayList<>();
        for (Receipt current : receipts()) {
            if (!current.environmentId().equals(receipt.environmentId())
                    || !current.binding().equals(receipt.binding())) updated.add(current);
        }
        updated.add(receipt);
        saveReceipts(updated);
    }

    private void saveReceipts(List<Receipt> receipts) {
        ensureStorage();
        host.writeText(RECEIPTS_PATH, JSON.toJson(
                receipts.stream().map(Receipt::asMap).toList()));
    }

    private void ensureStorage() {
        host.ensureDirectory(ROOT);
        host.ensureDirectory(PACKAGE_ROOT);
        if (!host.exists(RECEIPTS_PATH)) host.writeText(RECEIPTS_PATH, "[]");
    }

    private String requireOrigin() {
        String value = host.environment(ORIGIN_VARIABLE);
        if (value == null || value.isBlank()) {
            throw new FclRuntimeException("Market mirror is not configured; call "
                    + "market.configure(\"http://host.docker.internal:8787\")");
        }
        return normalizeOrigin(value);
    }

    private static String normalizeOrigin(String source) {
        String value = source.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new FclRuntimeException("Market origin must be an http:// or https:// origin");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException invalid) {
            throw new FclRuntimeException("Market origin is invalid", invalid);
        }
    }

    private static boolean matches(PackageRecord record, String query) {
        if (query.isEmpty()) return true;
        for (String term : query.split("\\s+")) {
            if (!term.isEmpty() && !matchesTerm(record, term)) return false;
        }
        return true;
    }

    /**
     * Matches complete values and word prefixes, never arbitrary interior substrings. This keeps
     * short queries such as {@code ed} useful without making {@code or} match {@code editor} or
     * making {@code 1} match every 1.x release.
     */
    private static boolean matchesTerm(PackageRecord record, String term) {
        if (prefix(record.name(), term) || prefix(record.namespace(), term)
                || prefix(record.kind(), term)
                || (record.namespace() + "/" + record.name())
                .toLowerCase(Locale.ROOT).startsWith(term)) {
            return true;
        }
        if (term.length() >= 8 && record.sha256().startsWith(term)) return true;
        if (record.tags().stream().anyMatch(value -> prefix(value, term))) return true;
        return words(record.summary()).stream().anyMatch(value -> value.startsWith(term))
                || words(record.description()).stream().anyMatch(value -> value.startsWith(term));
    }

    private static boolean prefix(String value, String term) {
        return value.toLowerCase(Locale.ROOT).startsWith(term);
    }

    private static List<String> words(String value) {
        if (value == null || value.isBlank()) return List.of();
        return WORD_SEPARATOR.splitAsStream(value.toLowerCase(Locale.ROOT))
                .filter(word -> !word.isEmpty()).toList();
    }

    private static java.util.Optional<PackageRecord> find(MarketIndex index, String packageId) {
        return index.packages().stream().filter(record -> record.sha256().equals(packageId))
                .findFirst();
    }

    private static PackageRecord requireRecord(MarketIndex index, String packageId) {
        return find(index, packageId).orElseThrow(() ->
                new FclRuntimeException("Unknown market package ID: " + packageId));
    }

    private static PackageRecord latest(MarketIndex index, String namespace, String name) {
        return index.packages().stream().filter(PackageRecord::latest)
                .filter(record -> record.namespace().equals(namespace)
                        && record.name().equals(name)).findFirst().orElse(null);
    }

    private static String packagePath(String packageId) {
        return PACKAGE_ROOT + "/" + packageId + ".db";
    }

    private static String helpText() {
        return "market.configure(origin)  Configure this user's mirror\n"
                + "market.update()           Refresh the complete index\n"
                + "market.search(text)      Search the cached index\n"
                + "market.info(sha256)      Show one package\n"
                + "market.download(sha256)  Download and verify a package\n"
                + "market.install(sha256)   Install exact package and dependencies\n"
                + "market.list()            List market-managed installations\n"
                + "market.upgrade()         Upgrade market-managed installations\n"
                + "market.uninstall(sha256) Remove a market-managed binding\n"
                + "market.origin()          Show the configured mirror\n"
                + "market.run()             Show client version and help";
    }

    private static String packageId(Object value) {
        String id = text(value, "package SHA-256").toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(id).matches()) {
            throw new FclRuntimeException("Package ID must be a complete SHA-256 hash");
        }
        return id;
    }

    private static String safeName(Object value, String field) {
        String text = text(value, field);
        if (!NAME.matcher(text).matches()) invalidRecord();
        return text;
    }

    private static String optionalText(Object value) {
        if (value == null) return "";
        String text = text(value, "market text");
        if (text.length() > 16_384 || text.codePoints().anyMatch(Character::isISOControl)) {
            invalidRecord();
        }
        return text;
    }

    private static List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?>)) invalidRecord();
        List<?> values = (List<?>) value;
        if (values.size() > 128) invalidRecord();
        return values.stream().map(MarketRuntimeFunctions::optionalText).toList();
    }

    private static long integer(Object value, String field) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.longValue()) {
            throw new FclRuntimeException(field + " must be an integer");
        }
        return number.longValue();
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new FclRuntimeException(field + " must be text");
        }
        return text;
    }

    private static void arity(List<Object> arguments, int expected, String function) {
        if (arguments.size() != expected) {
            throw new FclRuntimeException(function + " expects " + expected + " argument(s)");
        }
    }

    private static void invalidRecord() {
        throw new FclRuntimeException("Market index contains an invalid package record");
    }

    private record MarketIndex(List<PackageRecord> packages) { }

    private record Dependency(String sha256, boolean optional) { }

    private record PackageRecord(String namespace, String name, String version, String kind,
                                 String coordinate, String sha256, String download, long bytes,
                                 List<Dependency> dependencies, boolean latest, String summary,
                                 String description, List<String> tags) {
        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("namespace", namespace);
            result.put("name", name);
            result.put("version", version);
            result.put("kind", kind);
            result.put("coordinate", coordinate);
            result.put("sha256", sha256);
            result.put("download", download);
            result.put("bytes", bytes);
            result.put("dependencies", dependencies.stream().map(dependency -> Map.of(
                    "sha256", dependency.sha256(), "optional", dependency.optional())).toList());
            result.put("latest", latest);
            if (!summary.isEmpty()) result.put("summary", summary);
            if (!description.isEmpty()) result.put("description", description);
            if (!tags.isEmpty()) result.put("tags", tags);
            return Map.copyOf(result);
        }
    }

    private record Receipt(String sha256, String coordinate, String namespace, String name,
                           String version, String binding, String environmentId,
                           String packageHash) {
        private Map<String, Object> asMap() {
            return Map.of("sha256", sha256, "coordinate", coordinate,
                    "namespace", namespace, "name", name, "version", version,
                    "binding", binding, "environmentId", environmentId,
                    "packageHash", packageHash);
        }
    }

    /** Host operations stay in the transaction-aware application adapter. */
    public interface Host {
        String environment(String name);
        void setEnvironment(String name, String value);
        boolean exists(String path);
        void ensureDirectory(String path);
        String readText(String path);
        void writeText(String path, String content);
        boolean removeFile(String path);
        boolean fileMatches(String path, String sha256, long bytes);
        Object httpGet(String url, FclFunctionRegistry.Invocation invocation);
        Object download(String url, String path, FclFunctionRegistry.Invocation invocation);
        Map<String, Object> install(String path, String binding);
        boolean removeBinding(String environmentId, String binding);
        void pin(String environmentId, String binding, String packageHash);
    }
}
