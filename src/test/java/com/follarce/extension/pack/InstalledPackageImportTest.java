package com.follarce.extension.pack;

import com.follarce.bootstrap.init.FileInit;
import com.follarce.extension.pack.PackageBuilder;
import com.follarce.extension.pack.PackageManager;
import com.follarce.kernel.process.ImportManager;
import com.follarce.kernel.security.UserUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstalledPackageImportTest {
    @TempDir Path root;

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void bareAndOwnAbsoluteImportsLoadTheCurrentUsersInstalledPackOnlyOnce() throws Exception {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        UserUtil.createUser("alice", "pw", false);
        UserUtil.createUser("bob", "pw", false);

        Path source = PackageTestFixtures.source(root, "import-source", "tests.pack", "demo", "1.0.0",
                "hello", "func hello() { return \"hello\" }", List.of(), Map.of());
        Path repository = root.resolve("user/alice/app/repository");
        Files.createDirectories(repository);
        PackageBuilder.build(source, repository.resolve("demo.pack"));

        PackageManager manager = PackageManager.getInstance();
        manager.initialize();
        manager.install("alice", "/user/alice/app/repository/demo.pack", null, null,
                "installed-import", 201, "generation-201");

        ImportManager imports = new ImportManager(
                () -> "alice", Map::of, () -> "/user/alice/app/main.fcl");
        List<String> code = new ArrayList<>();
        List<String> first = imports.handleImport("import demo.*", code);
        first.forEach(imports::addImportedFile);
        List<String> second = imports.handleImport("import demo.*", code);

        assertEquals(1, first.size());
        assertTrue(first.get(0).matches("pack:[0-9a-f]{64}!/payload/main\\.fcl"));
        assertTrue(second.isEmpty());
        assertEquals(List.of("func hello() { return \"hello\" }"), code);

        ImportManager absolute = new ImportManager(
                () -> "alice", Map::of, () -> "/system/app/main.fcl");
        assertEquals(1, absolute.handleImport(
                "import /user/alice/app/package/demo.*", new ArrayList<>()).size());

        ImportManager otherUser = new ImportManager(
                () -> "bob", Map::of, () -> "/user/bob/app/main.fcl");
        assertTrue(otherUser.handleImport("import demo.*", new ArrayList<>()).isEmpty());
    }
}
