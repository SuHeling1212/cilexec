package com.follarce.package_manager;

import com.follarce.persistence.sqlite.SqlitePackageReader;
import com.follarce.domain.packageinfo.PackageKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackageBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsDeterministicValidatedPackageDatabase() {
        PackageManifest manifest = manifest();
        PackageBuilder builder = new PackageBuilder();
        byte[] first = builder.build(manifest, this::content);
        byte[] second = builder.build(manifest, this::content);

        assertArrayEquals(first, second);
        var descriptor = new SqlitePackageReader().inspect(first);
        assertEquals("demo/hello/1.0.0", descriptor.coordinate());
        assertEquals(List.of("main"), descriptor.modules());
        assertEquals("asset", new String(new SqlitePackageReader().readResource(first,
                "assets/message.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void buildsFromDirectoryAndRefusesToReplaceOutput() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("hello"));
        Files.writeString(source.resolve("package.json"), """
                {"namespace":"demo","name":"hello","version":"1.0.0",
                 "languageVersion":"fcl-1",
                 "kind":"application",
                 "modules":[{"name":"main","path":"main.fcl"}],
                 "entrypoints":[{"name":"run","module":"main","function":"run"}],
                 "exports":[{"name":"greet","module":"main","symbol":"greet"}]}
                """);
        Files.writeString(source.resolve("main.fcl"), module());
        Path output = temporaryDirectory.resolve("hello.db");

        assertEquals("demo/hello/1.0.0", new PackageBuilder().build(source, output).coordinate());
        assertThrows(IllegalArgumentException.class,
                () -> new PackageBuilder().build(source, output));
    }

    @Test
    void rejectsMissingEntrypointFunctions() {
        PackageManifest invalid = new PackageManifest("demo", "hello", "1.0.0", "fcl-1",
                List.of(new PackageManifest.Module("main", "main.fcl")), List.of(), List.of(),
                List.of(new PackageManifest.Entrypoint("run", "main", "missing")),
                List.of(), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> new PackageBuilder().build(invalid, this::content));
    }

    @Test
    void requiresKindAndUniversalRunForApplicationsButNotLibraries() {
        PackageBuilder builder = new PackageBuilder();
        assertThrows(IllegalArgumentException.class, () -> builder.parseManifest("""
                {"namespace":"demo","name":"missing-kind","version":"1.0.0",
                 "languageVersion":"fcl-1",
                 "modules":[{"name":"main","path":"main.fcl"}]}
                """.getBytes(StandardCharsets.UTF_8)));

        assertThrows(IllegalArgumentException.class, () -> new PackageManifest(
                "demo", "application", "1.0.0", "fcl-1", PackageKind.APPLICATION,
                List.of(new PackageManifest.Module("main", "main.fcl")), List.of(),
                List.of(), List.of(), List.of(), List.of()));

        PackageManifest library = new PackageManifest(
                "demo", "library", "1.0.0", "fcl-1", PackageKind.LIBRARY,
                List.of(new PackageManifest.Module("main", "main.fcl")), List.of(),
                List.of(), List.of(), List.of(), List.of());
        byte[] database = builder.build(library, path ->
                "func helper() { return 1 }".getBytes(StandardCharsets.UTF_8));
        assertEquals(PackageKind.LIBRARY,
                new SqlitePackageReader().inspect(database).kind());
    }

    private PackageManifest manifest() {
        return new PackageManifest("demo", "hello", "1.0.0", "fcl-1",
                List.of(new PackageManifest.Module("main", "main.fcl")),
                List.of("assets/message.txt"), List.of(),
                List.of(new PackageManifest.Entrypoint("run", "main", "run")),
                List.of(new PackageManifest.Export("greet", "main", "greet")), List.of());
    }

    private byte[] content(String path) {
        return switch (path) {
            case "main.fcl" -> module().getBytes(StandardCharsets.UTF_8);
            case "assets/message.txt" -> "asset".getBytes(StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException(path);
        };
    }

    private static String module() {
        return """
                func greet(value) { return "Hello, " + value }
                func run() { return greet("package") }
                """;
    }
}
