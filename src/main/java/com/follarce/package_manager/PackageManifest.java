package com.follarce.package_manager;

import com.follarce.domain.packageinfo.PackageKind;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Declarative input for the offline and VFS package builders. */
public record PackageManifest(
        String namespace,
        String name,
        String version,
        String languageVersion,
        PackageKind kind,
        List<Module> modules,
        List<String> resources,
        List<Dependency> dependencies,
        List<Entrypoint> entrypoints,
        List<Export> exports,
        List<Capability> capabilities
) {
    public PackageManifest {
        namespace = component(namespace, "namespace");
        name = component(name, "name");
        version = version(version, "version");
        languageVersion = boundedText(languageVersion, "languageVersion", 128);
        kind = Objects.requireNonNull(kind, "package kind is required");
        modules = copy(modules);
        resources = copy(resources).stream()
                .map(path -> canonicalPath(path, "resource"))
                .toList();
        dependencies = copy(dependencies);
        entrypoints = copy(entrypoints);
        exports = copy(exports);
        capabilities = copy(capabilities);
        if (modules.isEmpty()) throw new IllegalArgumentException("At least one module is required");

        Set<String> moduleNames = unique(modules.stream().map(Module::name).toList(),
                "module name");
        Set<String> contentPaths = new HashSet<>();
        modules.forEach(module -> uniquePath(contentPaths, module.path()));
        resources.forEach(path -> uniquePath(contentPaths, path));
        unique(dependencies.stream().map(Dependency::sha256).toList(), "dependency SHA-256");
        unique(entrypoints.stream().map(Entrypoint::name).toList(), "entrypoint name");
        unique(exports.stream().map(Export::name).toList(), "export name");
        unique(capabilities.stream().map(Capability::key).toList(), "capability key");
        entrypoints.forEach(entrypoint -> requireModule(moduleNames, entrypoint.module(),
                "entrypoint"));
        exports.forEach(export -> requireModule(moduleNames, export.module(), "export"));
        if (kind == PackageKind.APPLICATION && entrypoints.stream()
                .noneMatch(entrypoint -> entrypoint.name().equals("run"))) {
            throw new IllegalArgumentException(
                    "Application packages must declare the universal run entrypoint");
        }
    }

    /** Source compatibility for Java callers; JSON manifests must explicitly declare kind. */
    public PackageManifest(String namespace, String name, String version, String languageVersion,
                           List<Module> modules, List<String> resources,
                           List<Dependency> dependencies, List<Entrypoint> entrypoints,
                           List<Export> exports, List<Capability> capabilities) {
        this(namespace, name, version, languageVersion, PackageKind.APPLICATION, modules,
                resources, dependencies, entrypoints, exports, capabilities);
    }

    public List<String> contentPaths() {
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        modules.forEach(module -> paths.add(module.path()));
        resources.forEach(paths::add);
        return List.copyOf(paths);
    }

    public record Module(String name, String path) {
        public Module {
            name = identifier(name, "module name");
            path = canonicalPath(path, "module path");
        }
    }

    public record Dependency(String sha256, boolean optional) {
        public Dependency {
            sha256 = text(sha256, "dependency sha256").toLowerCase(java.util.Locale.ROOT);
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "Dependency sha256 must be a 64-character SHA-256 value");
            }
        }
    }

    public record Entrypoint(String name, String module, String function) {
        public Entrypoint {
            name = identifier(name, "entrypoint name");
            module = identifier(module, "entrypoint module");
            function = identifier(function, "entrypoint function");
        }
    }

    public record Export(String name, String module, String symbol) {
        public Export {
            name = identifier(name, "export name");
            module = identifier(module, "export module");
            symbol = identifier(symbol, "export symbol");
        }
    }

    public record Capability(String key, boolean required, String rationale) {
        public Capability {
            key = text(key, "capability key");
            if (!key.matches("[a-z][a-z0-9_.:-]{0,127}")) {
                throw new IllegalArgumentException("Unsupported capability key: " + key);
            }
            rationale = Objects.requireNonNullElse(rationale, "");
            if (rationale.length() > 4096
                    || rationale.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Capability rationale is invalid");
            }
        }
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String component(String value, String name) {
        value = text(value, name);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("Unsupported " + name + ": " + value);
        }
        return value;
    }

    private static String version(String value, String name) {
        value = text(value, name);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}")) {
            throw new IllegalArgumentException("Unsupported " + name + ": " + value);
        }
        return value;
    }

    private static String boundedText(String value, String name, int maximum) {
        value = text(value, name);
        if (value.length() > maximum) throw new IllegalArgumentException(name + " is too long");
        return value;
    }

    private static String identifier(String value, String name) {
        value = text(value, name);
        if (!value.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Unsupported " + name + ": " + value);
        }
        return value;
    }

    private static String canonicalPath(String value, String name) {
        value = text(value, name).replace('\\', '/');
        if (value.length() > 1024) {
            throw new IllegalArgumentException(name + " is too long");
        }
        if (value.startsWith("/") || value.endsWith("/")) {
            throw new IllegalArgumentException(name + " must be package-relative: " + value);
        }
        for (String part : value.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")
                    || part.length() > 255) {
                throw new IllegalArgumentException(name + " is not canonical: " + value);
            }
        }
        return value;
    }

    private static Set<String> unique(List<String> values, String name) {
        Set<String> result = new HashSet<>(values);
        if (result.size() != values.size()) throw new IllegalArgumentException("Duplicate " + name);
        return Set.copyOf(result);
    }

    private static void uniquePath(Set<String> paths, String path) {
        if (!paths.add(path)) throw new IllegalArgumentException("Duplicate package path: " + path);
    }

    private static void requireModule(Set<String> modules, String module, String kind) {
        if (!modules.contains(module)) {
            throw new IllegalArgumentException(kind + " references unknown module: " + module);
        }
    }
}
