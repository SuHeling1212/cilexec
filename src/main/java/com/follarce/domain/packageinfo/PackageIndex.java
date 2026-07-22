package com.follarce.domain.packageinfo;

import com.follarce.domain.Invariant;
import com.follarce.domain.vfs.ObjectHash;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rebuildable PostgreSQL lookup rows derived from authoritative package bytes. */
public record PackageIndex(
        PackageRelease release,
        List<Module> modules,
        List<Dependency> dependencies,
        List<Entrypoint> entrypoints,
        List<Export> exports,
        List<CapabilityRequirement> capabilities
) {
    public PackageIndex {
        Invariant.required(release, "release");
        modules = Invariant.list(modules, "modules");
        dependencies = Invariant.list(dependencies, "dependencies");
        entrypoints = Invariant.list(entrypoints, "entrypoints");
        exports = Invariant.list(exports, "exports");
        capabilities = Invariant.list(capabilities, "capabilities");
        Set<String> moduleNames = unique(modules.stream().map(Module::name).toList(),
                "module name");
        unique(dependencies.stream().map(dependency ->
                dependency.namespace() + "/" + dependency.name()).toList(), "dependency");
        unique(entrypoints.stream().map(Entrypoint::name).toList(), "entrypoint name");
        unique(exports.stream().map(Export::name).toList(), "export name");
        unique(capabilities.stream().map(CapabilityRequirement::key).toList(),
                "capability key");
        entrypoints.forEach(entrypoint -> Invariant.check(
                moduleNames.contains(entrypoint.moduleName()),
                "entrypoint references an unknown module"));
        exports.forEach(export -> Invariant.check(moduleNames.contains(export.moduleName()),
                "export references an unknown module"));
    }

    private static Set<String> unique(List<String> values, String description) {
        Set<String> unique = new HashSet<>(values);
        Invariant.check(unique.size() == values.size(), "duplicate " + description);
        return Set.copyOf(unique);
    }

    private static String key(String value, String name) {
        value = Invariant.text(value, name);
        Invariant.check(value.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}"),
                name + " contains unsupported characters");
        return value;
    }

    private static String component(String value, String name) {
        value = Invariant.text(value, name);
        Invariant.check(value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"),
                name + " contains unsupported characters");
        return value;
    }

    public record Module(String name, String objectPath, ObjectHash hash) {
        public Module {
            name = key(name, "moduleName");
            objectPath = Invariant.text(objectPath, "moduleObjectPath");
            Invariant.check(!objectPath.startsWith("/") && !objectPath.endsWith("/")
                            && objectPath.indexOf('\\') < 0,
                    "moduleObjectPath must be a canonical package-relative path");
            Invariant.check(objectPath.chars().noneMatch(Character::isISOControl),
                    "moduleObjectPath contains control characters");
            for (String pathPart : objectPath.split("/", -1)) {
                Invariant.check(!pathPart.isBlank() && !pathPart.equals(".")
                                && !pathPart.equals(".."),
                        "moduleObjectPath must be traversal-free");
            }
            Invariant.required(hash, "moduleHash");
        }
    }

    public record Dependency(
            String namespace,
            String name,
            String versionConstraint,
            boolean optional
    ) {
        public Dependency {
            namespace = component(namespace, "dependencyNamespace");
            name = component(name, "dependencyName");
            versionConstraint = Invariant.text(versionConstraint, "versionConstraint");
            Invariant.check(versionConstraint.chars().noneMatch(Character::isISOControl),
                    "versionConstraint contains control characters");
        }

        public String coordinate() {
            return namespace + "/" + name + "/" + versionConstraint;
        }
    }

    public record Entrypoint(String name, String moduleName, String functionName) {
        public Entrypoint {
            name = key(name, "entrypointName");
            moduleName = key(moduleName, "entrypointModuleName");
            functionName = key(functionName, "entrypointFunctionName");
        }
    }

    public record Export(String name, String moduleName, String symbolName) {
        public Export {
            name = key(name, "exportName");
            moduleName = key(moduleName, "exportModuleName");
            symbolName = key(symbolName, "exportSymbolName");
        }
    }

    public record CapabilityRequirement(String key, boolean required, String rationale) {
        public CapabilityRequirement {
            key = Invariant.text(key, "capabilityKey");
            Invariant.check(key.matches("[a-z][a-z0-9_.:-]*"),
                    "capabilityKey contains unsupported characters");
            rationale = Invariant.required(rationale, "rationale");
        }
    }
}
