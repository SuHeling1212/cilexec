package com.follarce.extension.pack;

import com.follarce.extension.pack.PackageArchive;
import com.follarce.extension.pack.PackageCoordinate;
import com.follarce.kernel.util.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PackageTestFixtures {
    private PackageTestFixtures() {}

    static Path source(Path parent,
                       String directoryName,
                       String namespace,
                       String name,
                       String version,
                       String exportedFunction,
                       String code,
                       List<Map<String, Object>> dependencies,
                       Map<String, String> hooks) throws Exception {
        Path source = parent.resolve(directoryName);
        Files.createDirectories(source.resolve("payload"));
        Files.writeString(source.resolve("payload/main.fcl"), code, StandardCharsets.UTF_8);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("namespace", namespace);
        manifest.put("name", name);
        manifest.put("version", version);
        manifest.put("entry", "payload/main.fcl");
        manifest.put("exports", Map.of(exportedFunction,
                Map.of("module", "payload/main.fcl", "symbol", exportedFunction)));
        manifest.put("dependencies", dependencies);
        if (hooks != null && !hooks.isEmpty()) {
            Map<String, Object> lifecycle = new LinkedHashMap<>();
            Files.createDirectories(source.resolve("hooks"));
            for (Map.Entry<String, String> hook : hooks.entrySet()) {
                String fileName = camelToKebab(hook.getKey()) + ".fcl";
                Files.writeString(source.resolve("hooks").resolve(fileName), hook.getValue(),
                        StandardCharsets.UTF_8);
                lifecycle.put(hook.getKey(), Map.of("script", "hooks/" + fileName, "timeoutMs", 5000));
            }
            manifest.put("lifecycle", lifecycle);
        }
        Files.writeString(source.resolve("manifest.json"), JsonUtil.toJson(manifest), StandardCharsets.UTF_8);
        return source;
    }

    static Map<String, Object> dependency(String binding, PackageArchive archive) {
        PackageCoordinate coordinate = archive.manifest().coordinate();
        Map<String, Object> dependency = new LinkedHashMap<>();
        dependency.put("binding", binding);
        dependency.put("namespace", coordinate.namespace());
        dependency.put("name", coordinate.name());
        dependency.put("version", coordinate.version());
        dependency.put("integrity", archive.integrity());
        return dependency;
    }

    private static String camelToKebab(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }
}
