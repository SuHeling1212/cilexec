package com.follarce.pack;

import com.follarce.util.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict parser for package manifest schema version 1. */
public final class PackageManifestParser {
    private static final Pattern SYMBOL = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern INTEGRITY = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "namespace", "name", "version", "entry", "exports",
            "dependencies", "lifecycle", "resources", "author", "license", "description");

    private PackageManifestParser() {}

    public static PackageManifest parse(String json) {
        Object parsed = JsonUtil.parseJson(json);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new PackageException("manifest.json must contain a valid JSON object");
        }
        Map<String, Object> manifest = stringMap(raw, "manifest");
        rejectUnknown(manifest, TOP_LEVEL_FIELDS, "manifest");
        for (Map.Entry<String, Object> item : manifest.entrySet()) {
            if (item.getValue() == null) {
                throw new PackageException("manifest field must not be null: " + item.getKey());
            }
        }

        int schemaVersion = requiredInt(manifest, "schemaVersion");
        if (schemaVersion != PackageManifest.SCHEMA_VERSION) {
            throw new PackageException("Unsupported package schemaVersion: " + schemaVersion);
        }

        PackageCoordinate coordinate = new PackageCoordinate(
                requiredString(manifest, "namespace"),
                requiredString(manifest, "name"),
                requiredString(manifest, "version"));
        String entry = packagePath(requiredString(manifest, "entry"), "entry", "payload/", ".fcl");
        Map<String, PackageManifest.Export> exports = parseExports(manifest.get("exports"));
        List<PackageManifest.Dependency> dependencies = parseDependencies(manifest.get("dependencies"));
        Map<PackageManifest.LifecycleEvent, PackageManifest.Hook> lifecycle =
                parseLifecycle(manifest.get("lifecycle"));
        Set<String> resources = parseResources(manifest.get("resources"));

        String description = optionalString(manifest, "description");
        if (description != null && description.length() > 1000) {
            throw new PackageException("manifest description exceeds 1000 characters");
        }
        String license = optionalString(manifest, "license");
        validateAuthor(manifest.get("author"));

        return new PackageManifest(schemaVersion, coordinate, entry, exports, dependencies,
                lifecycle, resources, description, license, manifest);
    }

    private static Map<String, PackageManifest.Export> parseExports(Object value) {
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
            throw new PackageException("manifest exports must be a non-empty object");
        }
        Map<String, PackageManifest.Export> exports = new LinkedHashMap<>();
        for (Map.Entry<?, ?> item : raw.entrySet()) {
            String publicName = String.valueOf(item.getKey());
            requireSymbol(publicName, "export name");
            if (!(item.getValue() instanceof Map<?, ?> exportRaw)) {
                throw new PackageException("Export " + publicName + " must be an object");
            }
            Map<String, Object> export = stringMap(exportRaw, "export " + publicName);
            rejectUnknown(export, Set.of("module", "symbol"), "export " + publicName);
            String module = packagePath(requiredString(export, "module"),
                    "export module", "payload/", ".fcl");
            String symbol = requiredString(export, "symbol");
            requireSymbol(symbol, "export symbol");
            exports.put(publicName, new PackageManifest.Export(module, symbol));
        }
        return exports;
    }

    private static List<PackageManifest.Dependency> parseDependencies(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new PackageException("manifest dependencies must be an array");
        }
        List<PackageManifest.Dependency> dependencies = new ArrayList<>();
        Set<String> bindings = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> dependencyRaw)) {
                throw new PackageException("Dependency at index " + i + " must be an object");
            }
            Map<String, Object> dependency = stringMap(dependencyRaw, "dependency " + i);
            rejectUnknown(dependency,
                    Set.of("binding", "namespace", "name", "version", "integrity"),
                    "dependency " + i);
            String binding = requiredString(dependency, "binding");
            requireSymbol(binding, "dependency binding");
            if (!bindings.add(binding)) {
                throw new PackageException("Duplicate dependency binding: " + binding);
            }
            PackageCoordinate coordinate = new PackageCoordinate(
                    requiredString(dependency, "namespace"),
                    requiredString(dependency, "name"),
                    requiredString(dependency, "version"));
            String integrity = requiredString(dependency, "integrity");
            if (!INTEGRITY.matcher(integrity).matches()) {
                throw new PackageException("Invalid dependency integrity for " + binding + ": " + integrity);
            }
            dependencies.add(new PackageManifest.Dependency(binding, coordinate, integrity));
        }
        return dependencies;
    }

    private static Map<PackageManifest.LifecycleEvent, PackageManifest.Hook> parseLifecycle(Object value) {
        Map<PackageManifest.LifecycleEvent, PackageManifest.Hook> hooks = new LinkedHashMap<>();
        if (value == null) return hooks;
        if (!(value instanceof Map<?, ?> raw)) {
            throw new PackageException("manifest lifecycle must be an object");
        }
        Map<String, Object> lifecycle = stringMap(raw, "lifecycle");
        Set<String> eventNames = Set.of("preInstall", "postInstall", "preUninstall", "postUninstall");
        rejectUnknown(lifecycle, eventNames, "lifecycle");
        for (Map.Entry<String, Object> item : lifecycle.entrySet()) {
            if (!(item.getValue() instanceof Map<?, ?> hookRaw)) {
                throw new PackageException("Lifecycle hook " + item.getKey() + " must be an object");
            }
            Map<String, Object> hook = stringMap(hookRaw, "lifecycle." + item.getKey());
            rejectUnknown(hook, Set.of("script", "timeoutMs"), "lifecycle." + item.getKey());
            String script = packagePath(requiredString(hook, "script"),
                    "hook script", "hooks/", ".fcl");
            int timeout = requiredInt(hook, "timeoutMs");
            if (timeout < 1 || timeout > 60_000) {
                throw new PackageException("Hook timeoutMs must be between 1 and 60000");
            }
            hooks.put(PackageManifest.LifecycleEvent.fromManifestKey(item.getKey()),
                    new PackageManifest.Hook(script, timeout));
        }
        return hooks;
    }

    private static Set<String> parseResources(Object value) {
        Set<String> resources = new LinkedHashSet<>();
        if (value == null) return resources;
        if (!(value instanceof List<?> list)) {
            throw new PackageException("manifest resources must be an array");
        }
        for (Object item : list) {
            if (!(item instanceof String path)) {
                throw new PackageException("Every resource path must be a string");
            }
            path = packagePath(path, "resource", "resources/", null);
            if (!resources.add(path)) throw new PackageException("Duplicate resource path: " + path);
        }
        return resources;
    }

    private static void validateAuthor(Object value) {
        if (value == null) return;
        if (!(value instanceof Map<?, ?> raw)) throw new PackageException("manifest author must be an object");
        Map<String, Object> author = stringMap(raw, "author");
        rejectUnknown(author, Set.of("name", "email", "url"), "author");
        if (requiredString(author, "name").isBlank()) {
            throw new PackageException("author.name must not be blank");
        }
        optionalString(author, "email");
        optionalString(author, "url");
    }

    static String packagePath(String value, String field, String requiredPrefix, String requiredSuffix) {
        PackageArchive.validateEntryName(value);
        if (requiredPrefix != null && !value.startsWith(requiredPrefix)) {
            throw new PackageException(field + " must be inside " + requiredPrefix + ": " + value);
        }
        if (requiredSuffix != null && !value.toLowerCase().endsWith(requiredSuffix)) {
            throw new PackageException(field + " must end with " + requiredSuffix + ": " + value);
        }
        return value;
    }

    private static void requireSymbol(String value, String field) {
        if (!SYMBOL.matcher(value).matches()) throw new PackageException("Invalid " + field + ": " + value);
    }

    private static String requiredString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new PackageException(key + " must be a non-empty string");
        }
        return text;
    }

    private static String optionalString(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) return null;
        Object value = map.get(key);
        if (!(value instanceof String text)) throw new PackageException(key + " must be a string");
        return text;
    }

    private static int requiredInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new PackageException(key + " must be an integer");
        }
        return number.intValue();
    }

    private static Map<String, Object> stringMap(Map<?, ?> source, String label) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new PackageException(label + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void rejectUnknown(Map<String, Object> map, Set<String> allowed, String label) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) throw new PackageException("Unknown " + label + " field: " + key);
        }
    }
}
