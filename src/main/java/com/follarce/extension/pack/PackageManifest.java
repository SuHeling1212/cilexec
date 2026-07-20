package com.follarce.extension.pack;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parsed and validated manifest v1. */
public record PackageManifest(
        int schemaVersion,
        PackageCoordinate coordinate,
        String entry,
        Map<String, Export> exports,
        List<Dependency> dependencies,
        Map<LifecycleEvent, Hook> lifecycle,
        Set<String> resources,
        String description,
        String license,
        Map<String, Object> source
) {
    public static final int SCHEMA_VERSION = 1;

    public PackageManifest {
        exports = Map.copyOf(new LinkedHashMap<>(exports));
        dependencies = List.copyOf(dependencies);
        lifecycle = Map.copyOf(new LinkedHashMap<>(lifecycle));
        resources = Set.copyOf(new LinkedHashSet<>(resources));
        source = Map.copyOf(new LinkedHashMap<>(source));
    }

    public record Export(String module, String symbol) {}

    public record Dependency(
            String binding,
            PackageCoordinate coordinate,
            String integrity
    ) {
        public String hash() {
            return integrity.substring("sha256:".length());
        }
    }

    public record Hook(String script, int timeoutMs) {}

    public enum LifecycleEvent {
        PRE_INSTALL("preInstall"),
        POST_INSTALL("postInstall"),
        PRE_UNINSTALL("preUninstall"),
        POST_UNINSTALL("postUninstall");

        private final String manifestKey;

        LifecycleEvent(String manifestKey) {
            this.manifestKey = manifestKey;
        }

        public String manifestKey() {
            return manifestKey;
        }

        public static LifecycleEvent fromManifestKey(String key) {
            for (LifecycleEvent event : values()) {
                if (event.manifestKey.equals(key)) return event;
            }
            throw new PackageException("Unknown lifecycle event: " + key);
        }
    }
}
