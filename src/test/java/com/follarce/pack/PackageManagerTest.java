package com.follarce.pack;

import com.follarce.init.FileInit;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.UserUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageManagerTest {
    @TempDir Path root;

    private PackageManager manager;
    private Path repository;

    @BeforeEach
    void setUp() throws Exception {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        UserUtil.createUser("alice", "pw", false);
        UserUtil.createUser("bob", "pw", false);
        manager = new PackageManager(new PackageStore(), new PackageHookRunner());
        manager.initialize();
        repository = root.resolve("user/alice/app/repository");
        Files.createDirectories(repository);
    }

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void installsPrivateDependencyGraphForOneUserAndImportsItFromImmutableObjects() throws Exception {
        BuiltGraph graph = buildDependencyGraph("Hello");

        Map<String, Object> installed = manager.install("alice",
                "/user/alice/app/repository/root.pack", null, null,
                "install-alice-root", 100, "generation-100");

        assertEquals("installed", installed.get("status"));
        assertEquals("completed", installed.get("postHookStatus"));
        assertEquals(1, manager.list("alice").size());
        assertTrue(manager.list("bob").isEmpty());
        assertEquals(2, new PackageStore().listObjectHashes().size());
        assertEquals(true, manager.verify("alice", "root").get("ok"));
        assertTrue(FileUtil.exists("/user/alice/app/data/package/packages/tests.pack/root"));
        assertTrue(FileUtil.exists("/user/alice/app/data/package/packages/tests.pack/dependency"));

        PackageManager.PackageImport packageImport = manager.loadForImport("alice", "root");
        assertEquals(graph.root().hash(), packageImport.rootHash());
        assertEquals(2, packageImport.modules().size());
        assertEquals(graph.dependency().hash(), packageImport.modules().get(0).packageHash());
        assertEquals(graph.root().hash(), packageImport.modules().get(1).packageHash());
        assertFalse(packageImport.modules().get(1).source().contains("import dep.*"));
        assertNull(manager.loadForImport("bob", "root"));

        Map<String, Object> removed = manager.remove("alice", "root",
                "remove-alice-root", 100, "generation-100");
        assertEquals("removed", removed.get("status"));
        assertTrue(manager.list("alice").isEmpty());

        Map<String, Object> gc = manager.garbageCollect();
        assertEquals(2, ((List<?>) gc.get("removed")).size());
        assertTrue(new PackageStore().listObjectHashes().isEmpty());
    }

    @Test
    void preInstallDenialLeavesNoVisibleOrCommittedPackage() throws Exception {
        Path source = PackageTestFixtures.source(root, "denied-source", "tests.pack", "denied", "1.0.0",
                "denied", "func denied() { return true }", List.of(),
                Map.of("preInstall", "hookResult = {\"status\": \"ok\", \"allow\": false, \"message\": \"no\"}"));
        PackageBuilder.build(source, repository.resolve("denied.pack"));

        PackageException error = assertThrows(PackageException.class,
                () -> manager.install("alice", "/user/alice/app/repository/denied.pack",
                        null, null, "denied-effect", 101, "generation-101"));

        assertTrue(error.getMessage().contains("denied"));
        assertTrue(manager.list("alice").isEmpty());
        assertTrue(new PackageStore().listObjectHashes().isEmpty());
    }

    @Test
    void postInstallFailureKeepsRootVisibleAndRecordsRetryableState() throws Exception {
        Path source = PackageTestFixtures.source(root, "post-source", "tests.pack", "post-fail", "1.0.0",
                "ready", "func ready() { return true }", List.of(),
                Map.of("postInstall", "hookResult = {\"status\": \"error\", \"message\": \"later\"}"));
        PackageBuilder.build(source, repository.resolve("post.pack"));

        Map<String, Object> result = manager.install("alice", "/user/alice/app/repository/post.pack",
                null, null, "post-fail-effect", 102, "generation-102");

        assertEquals("pending-retry", result.get("postHookStatus"));
        assertEquals(1, manager.list("alice").size());
        assertTrue(FileUtil.exists(PackagePaths.userRootFile("alice")));
        assertTrue(new PackageStore().readTransactions("alice").stream()
                .anyMatch(transaction -> "POST_HOOKS_FAILED".equals(transaction.get("state"))));
    }

    @Test
    void rejectsSameCoordinateWithDifferentContentAcrossUsers() throws Exception {
        Path firstSource = PackageTestFixtures.source(root, "first-source", "tests.pack", "polluted", "1.0.0",
                "value", "func value() { return 1 }", List.of(), Map.of());
        Path firstPack = repository.resolve("first.pack");
        PackageBuilder.build(firstSource, firstPack);
        manager.install("alice", "/user/alice/app/repository/first.pack",
                null, null, "pollution-first", 103, "generation-103");

        Path bobRepository = root.resolve("user/bob/app/repository");
        Files.createDirectories(bobRepository);
        Path secondSource = PackageTestFixtures.source(root, "second-source", "tests.pack", "polluted", "1.0.0",
                "value", "func value() { return 2 }", List.of(), Map.of());
        PackageBuilder.build(secondSource, bobRepository.resolve("second.pack"));

        PackageException error = assertThrows(PackageException.class,
                () -> manager.install("bob", "/user/bob/app/repository/second.pack",
                        null, null, "pollution-second", 104, "generation-104"));
        assertTrue(error.getMessage().contains("version pollution"));
        assertTrue(manager.list("bob").isEmpty());
    }

    @Test
    void regularUserCannotInstallFromAnotherUsersAppPath() throws Exception {
        buildDependencyGraph("private");

        PackageException error = assertThrows(PackageException.class,
                () -> manager.install("bob", "/user/alice/app/repository/root.pack",
                        null, null, "cross-user", 105, "generation-105"));

        assertTrue(error.getMessage().contains("Permission denied"));
    }

    @Test
    void packagePathsCannotEscapeThroughAHostSymbolicLink() throws Exception {
        Path source = PackageTestFixtures.source(root, "symlink-source", "tests.pack", "symlink", "1.0.0",
                "value", "func value() { return 1 }", List.of(), Map.of());
        Path external = root.resolve("external-repository");
        Files.createDirectories(external);
        PackageBuilder.build(source, external.resolve("symlink.pack"));
        Path link = root.resolve("user/alice/app/linked-repository");
        try {
            Files.createSymbolicLink(link, external);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            return;
        }

        PackageException error = assertThrows(PackageException.class,
                () -> manager.install("alice", "/user/alice/app/linked-repository/symlink.pack",
                        null, null, "symlink-effect", 108, "generation-108"));

        assertTrue(error.getMessage().contains("symbolic links"));
    }

    @Test
    void buildFromVfsStripsMutableMetadataBeforeHashing() {
        UserUtil.setCurrentUser("alice");
        FileUtil.createDirectory("/user/alice/app", "source");
        PackagePaths.ensureDirectory("/user/alice/app/source", "alice", true);
        FileUtil.createDirectory("/user/alice/app/source", "payload");
        PackagePaths.ensureDirectory("/user/alice/app/source/payload", "alice", true);
        FileUtil.createFile("/user/alice/app/source", "manifest.json");
        FileUtil.createFile("/user/alice/app/source/payload", "main.fcl");
        Map<String, Object> manifest = Map.of(
                "schemaVersion", 1,
                "namespace", "tests.pack",
                "name", "vfs-source",
                "version", "1.0.0",
                "entry", "payload/main.fcl",
                "exports", Map.of("value", Map.of(
                        "module", "payload/main.fcl", "symbol", "value")),
                "dependencies", List.of());
        FileUtil.write("/user/alice/app/source/manifest.json", JsonUtil.toJson(manifest));
        FileUtil.write("/user/alice/app/source/payload/main.fcl", "func value() { return 7 }");
        UserUtil.setCurrentUser("local");

        Map<String, Object> built = manager.build("alice", "/user/alice/app/source",
                "/user/alice/app/vfs-source.pack");
        PackageArchive archive = PackageArchive.read(root.resolve("user/alice/app/vfs-source.pack"));

        assertEquals("built", built.get("status"));
        assertEquals(List.of("manifest.json", "payload/main.fcl"), archive.entryNames());
        assertFalse(archive.readUtf8("payload/main.fcl").contains("#<META>"));
    }

    @Test
    void startupRecoveryUsesTheRealRootAsTheCrashVisibilityAuthority() throws Exception {
        Path source = PackageTestFixtures.source(root, "recovery-source", "tests.pack", "recovery", "1.0.0",
                "ready", "func ready() { return true }", List.of(), Map.of());
        PackageBuilder.build(source, repository.resolve("recovery.pack"));
        Map<String, Object> installed = manager.install("alice",
                "/user/alice/app/repository/recovery.pack", null, null,
                "recovery-install", 106, "generation-106");
        String hash = installed.get("integrity").toString().substring("sha256:".length());
        PackageStore store = new PackageStore();

        String crossedId = "a".repeat(40);
        Map<String, Object> crossed = interruptedTransaction(crossedId, "recovery", hash,
                "COMMITTING_ROOT");
        store.writeTransaction("alice", crossedId, crossed);

        String beforeId = "b".repeat(40);
        Map<String, Object> before = interruptedTransaction(beforeId, "not-visible", "c".repeat(64),
                "OBJECTS_COMMITTED");
        store.writeTransaction("alice", beforeId, before);

        manager.recoverTransactions();

        assertEquals("COMMITTED", store.readTransaction("alice", crossedId).get("state"));
        assertEquals("ABORTED", store.readTransaction("alice", beforeId).get("state"));
    }

    @Test
    void transitiveDependenciesRemainInEachOwnersPrivateDirectReferenceTable() throws Exception {
        Path leafSource = PackageTestFixtures.source(root, "leaf-source", "tests.pack", "leaf", "1.0.0",
                "leafValue", "func leafValue() { return 1 }", List.of(), Map.of());
        Path leafPack = repository.resolve("leaf.pack");
        PackageBuilder.build(leafSource, leafPack);
        PackageArchive leaf = PackageArchive.read(leafPack);

        Path middleSource = PackageTestFixtures.source(root, "middle-source", "tests.pack", "middle", "1.0.0",
                "middleValue", "import leaf.*\nfunc middleValue() { return leafValue() }",
                List.of(PackageTestFixtures.dependency("leaf", leaf)), Map.of());
        Path middlePack = repository.resolve("middle.pack");
        PackageBuilder.build(middleSource, middlePack);
        PackageArchive middle = PackageArchive.read(middlePack);

        Path topSource = PackageTestFixtures.source(root, "top-source", "tests.pack", "top", "1.0.0",
                "topValue", "import middle.*\nfunc topValue() { return middleValue() }",
                List.of(PackageTestFixtures.dependency("middle", middle)), Map.of());
        Path topPack = repository.resolve("top.pack");
        PackageBuilder.build(topSource, topPack);
        PackageArchive top = PackageArchive.read(topPack);

        manager.install("alice", "/user/alice/app/repository/top.pack", null, null,
                "transitive-install", 107, "generation-107");

        PackageStore store = new PackageStore();
        Map<String, Object> topRefs = PackageStore.objectMap(store.readReferences(top.hash()), "references");
        Map<String, Object> middleRefs = PackageStore.objectMap(
                store.readReferences(middle.hash()), "references");
        assertEquals(List.of("middle"), new ArrayList<>(topRefs.keySet()));
        assertEquals(List.of("leaf"), new ArrayList<>(middleRefs.keySet()));
        assertFalse(topRefs.containsKey("leaf"));
        assertEquals(List.of(leaf.hash(), middle.hash(), top.hash()),
                manager.loadForImport("alice", "top").modules().stream()
                        .map(PackageManager.ImportModule::packageHash).toList());
    }

    private BuiltGraph buildDependencyGraph(String greeting) throws Exception {
        Path dependencySource = PackageTestFixtures.source(root, "dependency-source", "tests.pack", "dependency", "1.0.0",
                "depValue", "func depValue() { return \"" + greeting + "\" }", List.of(), Map.of(
                        "preInstall", "hookResult = {\"status\": \"ok\", \"allow\": true}",
                        "postInstall", "hookResult = {\"status\": \"ok\"}",
                        "preUninstall", "hookResult = {\"status\": \"ok\", \"allow\": true}",
                        "postUninstall", "hookResult = {\"status\": \"ok\"}"));
        Path dependencyPack = repository.resolve("dependency.pack");
        PackageBuilder.build(dependencySource, dependencyPack);
        PackageArchive dependency = PackageArchive.read(dependencyPack);

        Path rootSource = PackageTestFixtures.source(root, "root-source", "tests.pack", "root", "1.0.0",
                "rootValue", "import dep.*\nfunc rootValue() { return depValue() }",
                List.of(PackageTestFixtures.dependency("dep", dependency)), Map.of(
                        "preInstall", "hookResult = {\"status\": \"ok\", \"allow\": true}",
                        "postInstall", "hookResult = {\"status\": \"ok\"}",
                        "preUninstall", "hookResult = {\"status\": \"ok\", \"allow\": true}",
                        "postUninstall", "hookResult = {\"status\": \"ok\"}"));
        Path rootPack = repository.resolve("root.pack");
        PackageBuilder.build(rootSource, rootPack);
        return new BuiltGraph(dependency, PackageArchive.read(rootPack));
    }

    private record BuiltGraph(PackageArchive dependency, PackageArchive root) {}

    private static Map<String, Object> interruptedTransaction(String id, String binding,
                                                               String hash, String state) {
        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("schemaVersion", 1);
        transaction.put("transactionId", id);
        transaction.put("effectId", "effect-" + id);
        transaction.put("user", "alice");
        transaction.put("operation", "INSTALL");
        transaction.put("state", state);
        transaction.put("binding", binding);
        transaction.put("rootHash", hash);
        transaction.put("postHooks", new ArrayList<Map<String, Object>>());
        transaction.put("completedPostHooks", new ArrayList<String>());
        transaction.put("result", new LinkedHashMap<>(Map.of("status", "installed")));
        return transaction;
    }
}
