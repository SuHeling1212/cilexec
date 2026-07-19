package com.follarce.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageArchiveTest {
    @TempDir Path root;

    @Test
    void deterministicBuilderProducesIdenticalContentAddressedArchives() throws Exception {
        Path source = PackageTestFixtures.source(root, "source", "tests.pack", "demo", "1.0.0",
                "answer", "func answer() { return 42 }", List.of(), Map.of());
        Path first = root.resolve("first.pack");
        Path second = root.resolve("second.pack");

        PackageBuilder.BuildResult firstResult = PackageBuilder.build(source, first);
        PackageBuilder.BuildResult secondResult = PackageBuilder.build(source, second);
        PackageArchive archive = PackageArchive.read(first);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        assertEquals(firstResult.integrity(), secondResult.integrity());
        assertEquals("tests.pack/demo@1.0.0", archive.manifest().coordinate().key());
        assertEquals(List.of("payload/main.fcl"), archive.payloadModules());
        assertEquals(firstResult.integrity(), archive.integrity());
    }

    @Test
    void readerRejectsCompressedEntriesAndTraversalPaths() throws Exception {
        Path compressed = root.resolve("compressed.pack");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(compressed)))) {
            ZipEntry entry = new ZipEntry("manifest.json");
            zip.putNextEntry(entry);
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertTrue(assertThrows(PackageException.class,
                () -> PackageArchive.read(compressed)).getMessage().contains("STORED"));

        Path traversal = root.resolve("traversal.pack");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(traversal)))) {
            ZipEntry entry = new ZipEntry("../manifest.json");
            zip.putNextEntry(entry);
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertTrue(assertThrows(PackageException.class,
                () -> PackageArchive.read(traversal)).getMessage().contains("entry path"));
    }

    @Test
    void verifierRejectsAnExportThatDoesNotExistInItsModule() throws Exception {
        Path source = PackageTestFixtures.source(root, "bad-source", "tests.pack", "bad", "1.0.0",
                "missing", "func present() { return true }", List.of(), Map.of());
        Path output = root.resolve("bad.pack");

        PackageException error = assertThrows(PackageException.class,
                () -> PackageBuilder.build(source, output));

        assertTrue(error.getMessage().contains("missing function"));
    }
}
