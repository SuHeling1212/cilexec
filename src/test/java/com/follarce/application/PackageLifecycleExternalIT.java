package com.follarce.application;

import com.follarce.auth.AuthService;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.packageinfo.PackageInstallation;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramLinker;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.persistence.postgres.error.PersistenceFailure;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.vfs.VfsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end package lifecycle against a real PostgreSQL instance: per-user
 * installation ledger, private data spaces with quotas, effective-install
 * enforcement, and atomic uninstallation with global payload GC.
 */
@Testcontainers
class PackageLifecycleExternalIT {
    private static final long DEFAULT_QUOTA = 268435456L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getProperty("cilexec.test.postgres.image", "postgres:17.10-alpine3.23"));

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            com.follarce.persistence.postgres.PostgresTestBootstrap.createServiceRoles(connection);
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(),
                        com.follarce.persistence.postgres.PostgresTestBootstrap.MIGRATOR_ROLE,
                        com.follarce.persistence.postgres.PostgresTestBootstrap.DEFAULT_PASSWORD)
                .locations("classpath:db/migration")
                .defaultSchema("flyway")
                .schemas("flyway")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @Test
    void installsStoresPrivateDataAndUninstallsCompletely() {
        JdbcTransactionExecutor transactions = transactions();
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthService auth = new AuthService(transactions, clock);
        UserAccount owner = auth.create("pkg-owner-" + suffix, "owner-password-123".toCharArray(),
                Set.of(Capability.VFS_READ, Capability.VFS_WRITE, Capability.PROCESS_CREATE,
                        Capability.PROCESS_CONTROL_OWN, Capability.PACKAGE_IMPORT,
                        Capability.PACKAGE_BIND));
        UserAccount administrator = auth.create("pkg-admin-" + suffix,
                "admin-password-123".toCharArray(), Set.of(Capability.SYSTEM_ADMIN,
                        Capability.VFS_READ));
        VfsService vfs = new VfsService(transactions, clock);
        VfsNode root = transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.vfs()
                        .findChild(owner.userId(), Optional.empty(), "/").orElseThrow());
        vfs.createFile(owner.userId(), root.nodeId(), "editor.db", packageBytes("cilexec",
                "editor", "0.0.1"), "application/vnd.sqlite3", Set.of(), false);

        String installSource = "installed = package.install(\"/editor.db\")\n"
                + "listed = package.list()\n"
                + "usage = package.dataInfo(installed[\"sha256\"])\n";
        FclContinuation installRuntime = run(transactions, owner.userId(), installSource);
        @SuppressWarnings("unchecked")
        Map<String, Object> installed = (Map<String, Object>) installRuntime.scope().get("installed");
        String fileSha256 = (String) installed.get("sha256");
        assertEquals("cilexec/editor/0.0.1", installed.get("coordinate"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed = (List<Map<String, Object>>) installRuntime.scope()
                .get("listed");
        assertTrue(listed.stream().anyMatch(item ->
                "cilexec/editor/0.0.1".equals(item.get("coordinate"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) installRuntime.scope().get("usage");
        assertEquals(DEFAULT_QUOTA, ((Number) usage.get("quota")).longValue());
        assertEquals(0L, ((Number) usage.get("logicalBytes")).longValue());

        // Private data space exists and is user-isolated.
        ObjectHash fileHash = new ObjectHash(fileSha256);
        assertEquals(DEFAULT_QUOTA, (long) transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .findDataUsage(owner.userId(), fileHash).quota()));
        assertThrows(PersistenceFailure.class, () -> transactions.inUserTransaction(
                administrator.userId(), Isolation.READ_COMMITTED, transaction -> transaction
                        .packages().findDataUsage(administrator.userId(), fileHash)));

        // Private data writes, CAS versioning, listing, rename and removal.
        transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages().writeDataEntry(owner.userId(), fileHash,
                        "config.json", "{\"theme\":\"dark\"}".getBytes(StandardCharsets.UTF_8),
                        "application/json", -1));
        transactions.<Void>inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    transaction.packages().mkdirDataEntry(owner.userId(), fileHash, "cache");
                    return null;
                });
        long version = transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages().listDataEntries(owner.userId(), fileHash, "")
                        .stream().filter(entry -> entry.relativePath().equals("config.json"))
                        .mapToLong(entry -> entry.stateVersion()).findFirst().orElseThrow());
        assertEquals(1L, version);
        assertThrows(PersistenceFailure.class, () -> transactions.inUserTransaction(
                owner.userId(), Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .writeDataEntry(owner.userId(), fileHash, "config.json",
                                "stale".getBytes(StandardCharsets.UTF_8), "text/plain", 99L)));
        assertEquals("{\"theme\":\"dark\"}", new String(transactions.inUserTransaction(
                owner.userId(), Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .readDataEntry(owner.userId(), fileHash, "config.json")),
                StandardCharsets.UTF_8));

        // Administrator quota override below current usage is rejected.
        assertThrows(PersistenceFailure.class, () -> transactions.<Void>inUserTransaction(
                administrator.userId(), Isolation.READ_COMMITTED, transaction -> {
                    transaction.packages().setDataQuota(administrator.userId(), owner.userId(),
                            fileHash, 1L);
                    return null;
                }));

        // Uninstall from FCL delegates to the same core service.
        String uninstallSource = "removed = package.uninstall(\"" + fileSha256 + "\")\n"
                + "listedAfter = package.list()\n";
        FclContinuation uninstallRuntime = run(transactions, owner.userId(), uninstallSource);
        @SuppressWarnings("unchecked")
        Map<String, Object> removed = (Map<String, Object>) uninstallRuntime.scope().get("removed");
        assertEquals(true, removed.get("removed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listedAfter = (List<Map<String, Object>>) uninstallRuntime.scope()
                .get("listedAfter");
        assertTrue(listedAfter.isEmpty());

        // After uninstall: no installation, no release payload, no data space.
        assertTrue(transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages().findInstallations(owner.userId())).isEmpty());
        assertTrue(transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages()
                        .findReleaseByDatabaseFileHash(fileHash)).isEmpty());
        assertThrows(PersistenceFailure.class, () -> transactions.inUserTransaction(
                owner.userId(), Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .findDataUsage(owner.userId(), fileHash)));
    }

    @Test
    void activeProcessesBlockUninstallUnlessForced() {
        JdbcTransactionExecutor transactions = transactions();
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthService auth = new AuthService(transactions, clock);
        UserAccount owner = auth.create("pkg-block-" + suffix, "owner-password-123".toCharArray(),
                Set.of(Capability.VFS_READ, Capability.VFS_WRITE, Capability.PROCESS_CREATE,
                        Capability.PROCESS_CONTROL_OWN, Capability.PACKAGE_IMPORT,
                        Capability.PACKAGE_BIND));
        VfsService vfs = new VfsService(transactions, clock);
        VfsNode root = transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.vfs()
                        .findChild(owner.userId(), Optional.empty(), "/").orElseThrow());
        vfs.createFile(owner.userId(), root.nodeId(), "editor.db",
                packageBytes("cilexec", "editor", "0.0.2"), "application/vnd.sqlite3",
                Set.of(), false);

        FclContinuation installRuntime = run(transactions, owner.userId(),
                "installed = package.install(\"/editor.db\")\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> installed = (Map<String, Object>) installRuntime.scope().get("installed");
        String fileSha256 = (String) installed.get("sha256");

        CilProcess bound = new ProcessService(transactions).create(owner.userId(),
                new ProgramService(transactions).create(owner.userId(), "value = 1\n"),
                Optional.empty());
        transactions.<Void>inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    transaction.packages().saveProcessBinding(
                            new com.follarce.domain.packageinfo.ProcessPackageBinding(
                                    bound.identity().processUid(), fileSha256,
                                    new PackageRelease.Hash(new ObjectHash(
                                            (String) installed.get("hash"))),
                                    clock.instant()));
                    return null;
                });

        assertThrows(PersistenceFailure.class, () -> transactions.inUserTransaction(
                owner.userId(), Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .uninstall(owner.userId(), new ObjectHash(fileSha256), false,
                                UUID.randomUUID())));

        // Forced uninstall from another process purges the bound process atomically.
        var result = transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages().uninstall(owner.userId(),
                        new ObjectHash(fileSha256), true, UUID.randomUUID()));
        assertTrue(result.removed());
        assertEquals(1, result.processesRemoved());
        assertTrue(transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.processes()
                        .findByUid(bound.identity().processUid())).isEmpty());
    }

    @Test
    void twoUsersShareAReleaseButOwnIndependentInstallationsAndData() {
        JdbcTransactionExecutor transactions = transactions();
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthService auth = new AuthService(transactions, clock);
        Set<Capability> capabilities = Set.of(Capability.VFS_READ, Capability.VFS_WRITE,
                Capability.PACKAGE_IMPORT, Capability.PACKAGE_BIND, Capability.PROCESS_CREATE);
        UserAccount first = auth.create("pkg-first-" + suffix, "first-password-123".toCharArray(),
                capabilities);
        UserAccount second = auth.create("pkg-second-" + suffix, "second-password-123".toCharArray(),
                capabilities);
        VfsService vfs = new VfsService(transactions, clock);
        for (UserAccount user : List.of(first, second)) {
            VfsNode root = transactions.inUserTransaction(user.userId(),
                    Isolation.READ_COMMITTED, transaction -> transaction.vfs()
                            .findChild(user.userId(), Optional.empty(), "/").orElseThrow());
            vfs.createFile(user.userId(), root.nodeId(), "editor.db",
                    packageBytes("cilexec", "editor", "0.0.3"), "application/vnd.sqlite3",
                    Set.of(), false);
            run(transactions, user.userId(), "installed = package.install(\"/editor.db\")\n");
        }
        ObjectHash fileHash = transactions.inUserTransaction(first.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .findInstallations(first.userId()).getFirst().rootFileHash());

        transactions.inUserTransaction(first.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages().writeDataEntry(first.userId(), fileHash,
                        "first.json", "first".getBytes(StandardCharsets.UTF_8),
                        "application/json", -1));
        assertEquals("first", new String(transactions.inUserTransaction(first.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .readDataEntry(first.userId(), fileHash, "first.json")),
                StandardCharsets.UTF_8));
        assertTrue(transactions.inUserTransaction(second.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages().listDataEntries(second.userId(), fileHash,
                        "")).isEmpty());

        // First user uninstalls; the release survives for the second user.
        run(transactions, first.userId(),
                "removed = package.uninstall(\"" + fileHash.value() + "\")\n");
        assertFalse(transactions.inUserTransaction(first.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages()
                        .findInstalledReleaseByDatabaseFileHash(first.userId(), fileHash))
                .isPresent());
        assertTrue(transactions.inUserTransaction(second.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages()
                        .findInstalledReleaseByDatabaseFileHash(second.userId(), fileHash))
                .isPresent());

        // Second user uninstalls; the global release payload is garbage-collected.
        run(transactions, second.userId(),
                "removed = package.uninstall(\"" + fileHash.value() + "\")\n");
        assertTrue(transactions.inUserTransaction(second.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages()
                        .findReleaseByDatabaseFileHash(fileHash)).isEmpty());
        assertTrue(transactions.inUserTransaction(second.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages().findInstallations(second.userId())).isEmpty());
    }

    @Test
    void forcedUninstallOfDependencyGarbageCollectsTheWholeRemovedClosure() {
        JdbcTransactionExecutor transactions = transactions();
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount owner = createOwner(transactions, clock, "pkg-gc-" + suffix);
        VfsService vfs = new VfsService(transactions, clock);
        VfsNode root = root(transactions, owner);

        // Dependency library B first.
        String libSource = "func value() { return 1 }\n"
                + "func run() { return value() }\n";
        com.follarce.package_manager.PackageManifest libManifest =
                new com.follarce.package_manager.PackageManifest("cilexec", "lib", "1.0.0",
                        "fcl-0.0.2", com.follarce.domain.packageinfo.PackageKind.LIBRARY,
                        List.of(new com.follarce.package_manager.PackageManifest.Module(
                                "main", "main.fcl")), List.of(), List.of(), List.of(),
                        List.of(new com.follarce.package_manager.PackageManifest.Export(
                                "value", "main", "value")), List.of());
        byte[] libDatabase = new com.follarce.package_manager.PackageBuilder().build(
                libManifest, path -> libSource.getBytes(StandardCharsets.UTF_8));
        var libDescriptor = new com.follarce.persistence.sqlite.SqlitePackageReader()
                .inspect(libDatabase);
        String libFileSha256 = libDescriptor.databaseFileHash();
        vfs.createFile(owner.userId(), root.nodeId(), "lib.db",
                libDatabase, "application/vnd.sqlite3", Set.of(), false);
        FclContinuation installLib = run(transactions, owner.userId(),
                "installed = package.install(\"/lib.db\")\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> installedLib = (Map<String, Object>) installLib.scope()
                .get("installed");
        assertEquals(libFileSha256, installedLib.get("sha256"));

        // Application A declares a required dependency on B.
        String appSource = "func answer() { return " + libFileSha256 + ".value() }\n"
                + "func run() { return answer() }\n";
        com.follarce.package_manager.PackageManifest appManifest =
                new com.follarce.package_manager.PackageManifest("cilexec", "app", "1.0.0",
                        "fcl-0.0.2", com.follarce.domain.packageinfo.PackageKind.APPLICATION,
                        List.of(new com.follarce.package_manager.PackageManifest.Module(
                                "main", "main.fcl")), List.of(),
                        List.of(new com.follarce.package_manager.PackageManifest.Dependency(
                                libFileSha256, false)),
                        List.of(new com.follarce.package_manager.PackageManifest.Entrypoint(
                                "run", "main", "run")),
                        List.of(new com.follarce.package_manager.PackageManifest.Export(
                                "answer", "main", "answer")), List.of());
        byte[] appDatabase = new com.follarce.package_manager.PackageBuilder().build(
                appManifest, path -> appSource.getBytes(StandardCharsets.UTF_8));
        var appDescriptor = new com.follarce.persistence.sqlite.SqlitePackageReader()
                .inspect(appDatabase);
        String appFileSha256 = appDescriptor.databaseFileHash();
        vfs.createFile(owner.userId(), root.nodeId(), "app.db",
                appDatabase, "application/vnd.sqlite3", Set.of(), false);
        FclContinuation installApp = run(transactions, owner.userId(),
                "installed = package.install(\"/app.db\")\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> installedApp = (Map<String, Object>) installApp.scope()
                .get("installed");
        assertEquals(appFileSha256, installedApp.get("sha256"));

        // Uninstalling the dependency with force removes every installation whose
        // closure contains it; both releases must then be garbage-collected.
        var result = transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages().uninstall(owner.userId(),
                        new ObjectHash(libFileSha256), true, UUID.randomUUID()));
        assertTrue(result.removed());
        assertTrue(transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages()
                        .findReleaseByDatabaseFileHash(new ObjectHash(libFileSha256)))
                .isEmpty(), "dependency release must be purged");
        assertTrue(transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages()
                        .findReleaseByDatabaseFileHash(new ObjectHash(appFileSha256)))
                .isEmpty(), "application release removed with the dependency closure must be purged");
    }

    @Test
    void injectedFailureRollsBackTheWholeUninstallTransaction() {
        JdbcTransactionExecutor transactions = transactions();
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount owner = createOwner(transactions, clock, "pkg-fail-" + suffix);
        VfsService vfs = new VfsService(transactions, clock);
        VfsNode root = root(transactions, owner);
        vfs.createFile(owner.userId(), root.nodeId(), "editor.db",
                packageBytes("cilexec", "editor", "0.0.4"), "application/vnd.sqlite3",
                Set.of(), false);
        FclContinuation installRuntime = run(transactions, owner.userId(),
                "installed = package.install(\"/editor.db\")\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> installed = (Map<String, Object>) installRuntime.scope().get("installed");
        ObjectHash fileHash = new ObjectHash((String) installed.get("sha256"));
        transactions.<Void>inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    transaction.packages().writeDataEntry(owner.userId(), fileHash,
                            "config.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                            "application/json", -1);
                    return null;
                });

        assertThrows(PersistenceFailure.class, () -> transactions.<Void>inUserTransaction(
                owner.userId(), Isolation.READ_COMMITTED, transaction -> {
                    transaction.setLocalSetting("app.cilexec_test_fail",
                            "uninstall_after_data");
                    transaction.packages().uninstall(owner.userId(), fileHash, false,
                            UUID.randomUUID());
                    return null;
                }));

        // The injected failure must have rolled back every phase: installation,
        // private data, and data space all remain.
        assertTrue(transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages()
                        .findInstalledReleaseByDatabaseFileHash(owner.userId(), fileHash))
                .isPresent());
        assertEquals("{\"a\":1}", new String(transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .readDataEntry(owner.userId(), fileHash, "config.json")),
                StandardCharsets.UTF_8));
        assertEquals(DEFAULT_QUOTA, (long) transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .findDataUsage(owner.userId(), fileHash).quota()));
    }

    @Test
    void concurrentUninstallsAreIdempotentAndLeaveCleanState() throws Exception {
        JdbcTransactionExecutor transactions = transactions();
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount owner = createOwner(transactions, clock, "pkg-conc-" + suffix);
        VfsService vfs = new VfsService(transactions, clock);
        VfsNode root = root(transactions, owner);
        vfs.createFile(owner.userId(), root.nodeId(), "editor.db",
                packageBytes("cilexec", "editor", "0.0.5"), "application/vnd.sqlite3",
                Set.of(), false);
        FclContinuation installRuntime = run(transactions, owner.userId(),
                "installed = package.install(\"/editor.db\")\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> installed = (Map<String, Object>) installRuntime.scope().get("installed");
        ObjectHash fileHash = new ObjectHash((String) installed.get("sha256"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> uninstall = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
            return transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                    transaction -> transaction.packages().uninstall(owner.userId(), fileHash,
                            false, UUID.randomUUID()).removed());
        };
        boolean firstRemoved;
        boolean secondRemoved;
        try {
            Future<Boolean> first = pool.submit(uninstall);
            Future<Boolean> second = pool.submit(uninstall);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            firstRemoved = first.get(30, TimeUnit.SECONDS);
            secondRemoved = second.get(30, TimeUnit.SECONDS);
            assertTrue(firstRemoved || secondRemoved,
                    "at least one concurrent uninstall must remove the package; "
                            + "firstRemoved=" + firstRemoved + ", secondRemoved=" + secondRemoved);
        } finally {
            pool.shutdownNow();
        }

        List<PackageInstallation> remaining = transactions.inUserTransaction(
                owner.userId(), Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .findInstallations(owner.userId()));
        boolean releasePresent = transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .findReleaseByDatabaseFileHash(fileHash)).isPresent();
        assertTrue(remaining.isEmpty(),
                "installation roots must be fully removed; remaining=" + remaining.size()
                        + " firstRemoved=" + firstRemoved + " secondRemoved=" + secondRemoved);
        assertTrue(!releasePresent,
                "global release must be purged after the last reference disappears; "
                        + "firstRemoved=" + firstRemoved + " secondRemoved=" + secondRemoved);
        assertThrows(PersistenceFailure.class, () -> transactions.inUserTransaction(
                owner.userId(), Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .findDataUsage(owner.userId(), fileHash)));
    }

    @Test
    void managedCacheNodesAreDeletedAndCountedOnUninstall() {
        JdbcTransactionExecutor transactions = transactions();
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount owner = createOwner(transactions, clock, "pkg-cache-" + suffix);
        VfsService vfs = new VfsService(transactions, clock);
        VfsNode root = root(transactions, owner);
        vfs.createFile(owner.userId(), root.nodeId(), "editor.db",
                packageBytes("cilexec", "editor", "0.0.6"), "application/vnd.sqlite3",
                Set.of(), false);
        FclContinuation installRuntime = run(transactions, owner.userId(),
                "installed = package.install(\"/editor.db\")\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> installed = (Map<String, Object>) installRuntime.scope().get("installed");
        ObjectHash fileHash = new ObjectHash((String) installed.get("sha256"));
        VfsNode cache = vfs.createFile(owner.userId(), root.nodeId(), "editor.db.cache",
                "cached".getBytes(StandardCharsets.UTF_8), "text/plain", Set.of(), false);
        transactions.<Void>inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    transaction.packages().registerManagedNode(owner.userId(), cache.nodeId(),
                            fileHash, "MARKET_CACHE");
                    return null;
                });

        FclContinuation uninstallRuntime = run(transactions, owner.userId(),
                "removed = package.uninstall(\"" + fileHash.value() + "\")\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> removed = (Map<String, Object>) uninstallRuntime.scope().get("removed");
        assertEquals(1L, ((Number) removed.get("cacheFilesRemoved")).longValue());
        assertTrue(transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.vfs()
                        .findChild(owner.userId(), Optional.of(root.nodeId()),
                                "editor.db.cache")).isEmpty());
    }

    @Test
    void recoverReportFlagsUsageMismatchAndReportsHealthyState() throws Exception {
        JdbcTransactionExecutor transactions = transactions();
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount owner = createOwner(transactions, clock, "pkg-heal-" + suffix);
        com.follarce.auth.AuthService auth = new com.follarce.auth.AuthService(transactions, clock);
        UserAccount admin = auth.create("pkg-heal-admin-" + suffix,
                "admin-password-123".toCharArray(), Set.of(Capability.SYSTEM_ADMIN));
        VfsService vfs = new VfsService(transactions, clock);
        VfsNode root = root(transactions, owner);
        vfs.createFile(owner.userId(), root.nodeId(), "editor.db",
                packageBytes("cilexec", "editor", "0.0.7"), "application/vnd.sqlite3",
                Set.of(), false);
        FclContinuation installRuntime = run(transactions, owner.userId(),
                "installed = package.install(\"/editor.db\")\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> installed = (Map<String, Object>) installRuntime.scope().get("installed");
        ObjectHash fileHash = new ObjectHash((String) installed.get("sha256"));
        transactions.<Void>inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    transaction.packages().writeDataEntry(owner.userId(), fileHash,
                            "note.txt", "hello".getBytes(StandardCharsets.UTF_8),
                            "text/plain", -1);
                    return null;
                });

        Map<String, Object> healthy = transactions.inUserTransaction(admin.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .recoverReport(admin.userId()));
        assertEquals(true, healthy.get("ok"));
        assertEquals(0, ((List<?>) healthy.get("issues")).size());

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE package.data_space SET logical_bytes = 0 "
                    + "WHERE owner_id = '" + owner.userId() + "'::uuid");
        }

        Map<String, Object> broken = transactions.inUserTransaction(admin.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .recoverReport(admin.userId()));
        assertEquals(false, broken.get("ok"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) broken.get("issues");
        assertTrue(issues.stream().anyMatch(issue ->
                "data_usage_mismatch".equals(issue.get("kind"))));
    }

    @Test
    void packageDataRunsFromLinkedPackageCodeAndRejectsTopLevelCalls() {
        JdbcTransactionExecutor transactions = transactions();
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount owner = createOwner(transactions, clock, "pkg-data-" + suffix);
        VfsService vfs = new VfsService(transactions, clock);
        VfsNode root = root(transactions, owner);

        String moduleSource = "func save() { packageData.mkdir(\"cache_%\")\n"
                + "packageData.write(\"cache_%/note.txt\", \"hello\")\n"
                + "packageData.write(\"cache_%/next.txt\", \"world\")\n"
                + "packageData.mkdir(\"cacheX\")\n"
                + "packageData.write(\"cacheX/keep.txt\", \"keep\")\nreturn true }\n"
                + "func readNote() { return packageData.read(\"cache_%/note.txt\") }\n"
                + "func clearCache() { return packageData.clear(\"cache_%\") }\n"
                + "func cacheExists() { return packageData.exists(\"cache_%\") }\n"
                + "func readOtherCache() { return packageData.read(\"cacheX/keep.txt\") }\n"
                + "func run() { return save() }\n";
        com.follarce.package_manager.PackageManifest manifest =
                new com.follarce.package_manager.PackageManifest("cilexec", "datanote", "0.0.1",
                        "fcl-0.0.2", com.follarce.domain.packageinfo.PackageKind.APPLICATION,
                        List.of(new com.follarce.package_manager.PackageManifest.Module(
                                "main", "main.fcl")), List.of(), List.of(),
                        List.of(new com.follarce.package_manager.PackageManifest.Entrypoint(
                                "run", "main", "run")),
                        List.of(new com.follarce.package_manager.PackageManifest.Export(
                                "save", "main", "save"),
                                new com.follarce.package_manager.PackageManifest.Export(
                                "readNote", "main", "readNote"),
                                new com.follarce.package_manager.PackageManifest.Export(
                                "clearCache", "main", "clearCache"),
                                new com.follarce.package_manager.PackageManifest.Export(
                                "cacheExists", "main", "cacheExists")),
                        List.of(new com.follarce.package_manager.PackageManifest.Capability(
                                "package.data", true, "persist private test note")));
        byte[] database = new com.follarce.package_manager.PackageBuilder().build(manifest,
                path -> moduleSource.getBytes(StandardCharsets.UTF_8));
        vfs.createFile(owner.userId(), root.nodeId(), "datanote.db", database,
                "application/vnd.sqlite3", Set.of(), false);
        FclContinuation installRuntime = run(transactions, owner.userId(),
                "installed = package.install(\"/datanote.db\")\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> installed = (Map<String, Object>) installRuntime.scope().get("installed");
        String fileSha256 = (String) installed.get("sha256");
        String packageHash = (String) installed.get("hash");
        ObjectHash fileHash = new ObjectHash(fileSha256);

        FclContinuation saved = runLinked(transactions, owner.userId(),
                "import \"" + fileSha256 + "\" as \"note\"\nvalue = note.save()\n",
                packageHash, moduleSource, List.of(
                        new FclProgramLinker.Export("save", List.of("note.save"))));
        assertEquals(true, saved.scope().get("value"));
        assertEquals("hello", new String(transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .readDataEntry(owner.userId(), fileHash, "cache_%/note.txt")),
                StandardCharsets.UTF_8));

        // A different process reads the same private data through the linked package.
        FclContinuation readBack = runLinked(transactions, owner.userId(),
                "import \"" + fileSha256 + "\" as \"note\"\n"
                        + "cleared = note.clearCache()\nexists = note.cacheExists()\n",
                packageHash, moduleSource, List.of(
                        new FclProgramLinker.Export("clearCache", List.of("note.clearCache")),
                        new FclProgramLinker.Export("cacheExists", List.of("note.cacheExists")),
                        new FclProgramLinker.Export("readOtherCache", List.of("note.readOtherCache"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> cleared = (Map<String, Object>) readBack.scope().get("cleared");
        assertEquals(2L, cleared.get("entriesRemoved"));
        assertEquals(true, readBack.scope().get("exists"),
                "clear keeps the requested directory itself");
        boolean cacheIsEmpty = transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.packages().listDataEntries(owner.userId(), fileHash,
                        "cache_%").isEmpty());
        assertTrue(cacheIsEmpty);
        assertEquals("keep", new String(transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.packages()
                        .readDataEntry(owner.userId(), fileHash, "cacheX/keep.txt")),
                StandardCharsets.UTF_8),
                "A percent or underscore in a cleared directory name is literal, never a wildcard");

        // Top-level user code has no package identity and must be rejected.
        CilProcess rejectProcess = new ProcessService(transactions).create(owner.userId(),
                new ProgramService(transactions).create(owner.userId(), "value = 1\n"),
                Optional.empty());
        var rejectProgram = transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.programs()
                        .findById(rejectProcess.continuation().programId()).orElseThrow());
        FclContinuation rejected = new FclContinuation();
        FclProgram topLevel = new FclCompiler().compile(
                "value = packageData.write(\"x.txt\", \"y\")\n");
        FclStepResult step = transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> new FclRuntime(
                        FclRuntimeFunctions.create(transaction, rejectProcess, rejectProgram,
                                rejected, clock.instant())).executeOne(topLevel, rejected));
        assertEquals(FclStepResult.Status.FAILED, step.status());
        assertTrue(String.valueOf(step.value()).contains("installed package code"));
    }

    private static UserAccount createOwner(JdbcTransactionExecutor transactions, Clock clock,
                                           String name) {
        return new AuthService(transactions, clock).create(name,
                "owner-password-123".toCharArray(), Set.of(Capability.VFS_READ,
                        Capability.VFS_WRITE, Capability.PROCESS_CREATE,
                        Capability.PROCESS_CONTROL_OWN, Capability.PACKAGE_IMPORT,
                        Capability.PACKAGE_BIND));
    }

    private static VfsNode root(JdbcTransactionExecutor transactions, UserAccount owner) {
        return transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.vfs()
                        .findChild(owner.userId(), Optional.empty(), "/").orElseThrow());
    }

    /** Executes a program whose package imports are linked from one known module. */
    private static FclContinuation runLinked(JdbcTransactionExecutor transactions, UUID ownerId,
                                             String source, String packageHash,
                                             String moduleSource,
                                             List<FclProgramLinker.Export> exports) {
        CilProcess process = new ProcessService(transactions).create(ownerId,
                new ProgramService(transactions).create(ownerId, source), Optional.empty());
        var program = transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> transaction.programs().findById(process.continuation().programId())
                        .orElseThrow());
        FclProgram base = new FclCompiler().compile(source);
        FclProgram linked = new FclProgramLinker().link(base, List.of(
                new FclProgramLinker.Module(packageHash, "main", moduleSource, exports)));
        FclContinuation continuation = new FclContinuation();
        int steps = 0;
        while (!continuation.halted() && steps++ < 50) {
            FclContinuation current = continuation;
            FclStepResult result = transactions.inUserTransaction(ownerId,
                    Isolation.READ_COMMITTED, transaction -> new FclRuntime(
                            FclRuntimeFunctions.create(transaction, process, program, current,
                                    Clock.systemUTC().instant()))
                            .executeOne(linked, current));
            if (result.status() == FclStepResult.Status.DIRECTIVE) {
                current.clearWait();
                continue;
            }
            assertFalse(result.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(result.value()));
        }
        assertTrue(continuation.halted());
        return continuation;
    }

    private static byte[] packageBytes(String namespace, String name, String version) {
        String source = "func greet(value) { return \"Hello, \" + value }\n"
                + "func run() { return greet(\"package\") }\n";
        com.follarce.package_manager.PackageManifest manifest =
                new com.follarce.package_manager.PackageManifest(namespace, name, version,
                        "fcl-0.0.2", List.of(new com.follarce.package_manager.PackageManifest.Module(
                                "main", "main.fcl")), List.of(), List.of(),
                        List.of(new com.follarce.package_manager.PackageManifest.Entrypoint(
                                "run", "main", "run")),
                        List.of(new com.follarce.package_manager.PackageManifest.Export(
                                "greet", "main", "greet")), List.of());
        return new com.follarce.package_manager.PackageBuilder().build(manifest,
                path -> source.getBytes(StandardCharsets.UTF_8));
    }

    private static FclContinuation run(JdbcTransactionExecutor transactions, UUID ownerId,
                                       String source) {
        CilProcess process = new ProcessService(transactions).create(ownerId,
                new ProgramService(transactions).create(ownerId, source), Optional.empty());
        var program = transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> transaction.programs().findById(process.continuation().programId())
                        .orElseThrow());
        FclContinuation continuation = new FclContinuation();
        var compiled = new com.follarce.fcl.FclCompiler().compile(source);
        int steps = 0;
        while (!continuation.halted() && steps++ < 100) {
            FclContinuation current = continuation;
            com.follarce.fcl.FclStepResult result = transactions.inUserTransaction(ownerId,
                    Isolation.READ_COMMITTED, transaction -> new com.follarce.fcl.FclRuntime(
                            FclRuntimeFunctions.create(transaction, process, program, current,
                                    Clock.systemUTC().instant()))
                            .executeOne(compiled, current));
            assertFalse(result.status() == com.follarce.fcl.FclStepResult.Status.FAILED,
                    () -> String.valueOf(result.value()));
        }
        assertTrue(continuation.halted());
        return continuation;
    }

    private static JdbcTransactionExecutor transactions() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return new JdbcTransactionExecutor(source);
    }
}
