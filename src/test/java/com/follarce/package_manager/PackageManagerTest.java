package com.follarce.package_manager;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.audit.AuditRetentionPolicy;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.packageinfo.PackageBinding;
import com.follarce.domain.packageinfo.PackageEnvironment;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.port.AuditRepository;
import com.follarce.domain.port.AuthRepository;
import com.follarce.domain.port.EffectRepository;
import com.follarce.domain.port.IpcRepository;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.ProgramRepository;
import com.follarce.domain.port.SchedulerRepository;
import com.follarce.domain.port.TerminalRepository;
import com.follarce.domain.port.TimerRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.port.TransactionWork;
import com.follarce.domain.port.VfsRepository;
import com.follarce.domain.vfs.FileRevision;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsMount;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageManagerTest {
    private static final Instant NOW = Instant.parse("2026-07-22T05:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void callerCannotForgeAnySignatureVerificationStatus() {
        MemoryPersistence persistence = new MemoryPersistence();
        PackageManager manager = manager(persistence);

        for (PackageRelease.SignatureStatus status : PackageRelease.SignatureStatus.values()) {
            if (status == PackageRelease.SignatureStatus.UNSIGNED) continue;
            assertThrows(SecurityException.class, () -> manager.importDatabase(
                    UUID.randomUUID(), new byte[]{1, 2, 3}, status), status.name());
        }
        assertEquals(0, persistence.transactions);
    }

    @Test
    void importsReleaseAndAllDerivedIndexesInOneRepositoryBundle() throws Exception {
        MemoryPersistence persistence = new MemoryPersistence();
        PackageManager manager = manager(persistence);
        byte[] database = Files.readAllBytes(packageDatabase("00".repeat(32)));

        PackageRelease imported = manager.importDatabase(UUID.randomUUID(), database);

        assertEquals(PackageRelease.SignatureStatus.UNSIGNED, imported.signatureStatus());
        assertNotNull(persistence.packages.lastIndex);
        PackageIndex index = persistence.packages.lastIndex;
        assertEquals(List.of("main"), index.modules().stream()
                .map(PackageIndex.Module::name).toList());
        assertEquals(List.of("std/base/1.0.0"), index.dependencies().stream()
                .map(PackageIndex.Dependency::coordinate).toList());
        assertEquals(List.of("run"), index.entrypoints().stream()
                .map(PackageIndex.Entrypoint::name).toList());
        assertEquals(List.of("api"), index.exports().stream()
                .map(PackageIndex.Export::name).toList());
        assertEquals(List.of("vfs_read"), index.capabilities().stream()
                .map(PackageIndex.CapabilityRequirement::key).toList());
        assertTrue(persistence.vfs.objects.containsKey(imported.databaseObjectHash()));
        assertEquals(1, persistence.transactions);
    }

    @Test
    void sameCoordinateWithDifferentLogicalContentIsRejected() throws Exception {
        MemoryPersistence persistence = new MemoryPersistence();
        PackageManager manager = manager(persistence);
        UUID ownerId = UUID.randomUUID();

        manager.importDatabase(ownerId,
                Files.readAllBytes(packageDatabase("00".repeat(32))));

        assertThrows(PackageCoordinateConflictException.class, () -> manager.importDatabase(
                ownerId, Files.readAllBytes(packageDatabase("11".repeat(32)))));
    }

    private PackageManager manager(MemoryPersistence persistence) {
        return new PackageManager(persistence, new SqlitePackageReader(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Path packageDatabase(String moduleHash) throws SQLException {
        Path database = temporaryDirectory.resolve(UUID.randomUUID() + ".db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE package_metadata(metadata_key TEXT, metadata_value TEXT)");
            statement.execute("CREATE TABLE package_file(file_path TEXT, content BLOB)");
            statement.execute("CREATE TABLE package_module(module_name TEXT, "
                    + "module_object_path TEXT, module_hash TEXT)");
            statement.execute("CREATE TABLE package_dependency(dependency_namespace TEXT, "
                    + "dependency_name TEXT, version_constraint TEXT, optional INTEGER)");
            statement.execute("CREATE TABLE package_entrypoint(entrypoint_name TEXT, "
                    + "module_name TEXT, function_name TEXT)");
            statement.execute("CREATE TABLE package_export(export_name TEXT, "
                    + "module_name TEXT, symbol_name TEXT)");
            statement.execute("CREATE TABLE package_capability(capability_key TEXT, "
                    + "required INTEGER, rationale TEXT)");
            statement.execute("CREATE TABLE package_signature(signature BLOB)");
            statement.execute("INSERT INTO package_metadata VALUES "
                    + "('namespace','std'),('name','example'),('version','1.2.3'),"
                    + "('language_version','1')");
            statement.execute("INSERT INTO package_module VALUES "
                    + "('main','modules/main.fcl','" + sha256(java.util.HexFormat.of()
                    .parseHex(moduleHash)) + "')");
            statement.execute("INSERT INTO package_file VALUES "
                    + "('modules/main.fcl',X'" + moduleHash + "')");
            statement.execute("INSERT INTO package_dependency VALUES "
                    + "('std','base','1.0.0',0)");
            statement.execute("INSERT INTO package_entrypoint VALUES ('run','main','main')");
            statement.execute("INSERT INTO package_export VALUES ('api','main','api')");
            statement.execute("INSERT INTO package_capability VALUES "
                    + "('vfs_read',1,'read package data')");
        }
        return database;
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class MemoryPersistence implements UserTransactionExecutor,
            TransactionContext {
        private final MemoryPackageRepository packages = new MemoryPackageRepository();
        private final MemoryVfsRepository vfs = new MemoryVfsRepository();
        private final List<AuditEvent> events = new ArrayList<>();
        private int transactions;

        @Override
        public <T> T inUserTransaction(UUID userId, Isolation isolation,
                                       TransactionWork<T> work) {
            transactions++;
            return work.execute(this);
        }

        @Override public PackageRepository packages() { return packages; }
        @Override public VfsRepository vfs() { return vfs; }
        @Override public AuthRepository auth() { return new AuthRepository() {
            @Override public Optional<UserAccount> findUser(UUID id) { return Optional.empty(); }
            @Override public Optional<UserAccount> findUser(String name) { return Optional.empty(); }
            @Override public void saveUser(UserAccount user) { }
            @Override public String provisionPrincipal(UUID id, char[] password) {
                throw new UnsupportedOperationException();
            }
            @Override public void disablePrincipal(UUID id) { }
            @Override public Set<Capability> capabilities(UUID id) {
                return Set.of(Capability.PACKAGE_IMPORT);
            }
            @Override public void replaceCapabilities(UUID id, Set<Capability> capabilities) { }
        }; }
        @Override public AuditRepository audit() { return new AuditRepository() {
            @Override public void append(AuditEvent event) { events.add(event); }
            @Override public List<AuditEvent> findByResource(String type, String id, int limit) {
                return events.stream().filter(event -> event.resourceType().equals(type)
                        && event.resourceId().equals(id)).limit(limit).toList();
            }
            @Override public void saveRetentionPolicy(AuditRetentionPolicy policy) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<AuditRetentionPolicy> findRetentionPolicy(String type) {
                return Optional.empty();
            }
            @Override public int purgeExpired(int limit) {
                throw new UnsupportedOperationException();
            }
        }; }
        @Override public ProgramRepository programs() { return null; }
        @Override public ProcessRepository processes() { return null; }
        @Override public SchedulerRepository scheduler() { return null; }
        @Override public IpcRepository ipc() { return null; }
        @Override public TimerRepository timers() { return null; }
        @Override public EffectRepository effects() { return null; }
        @Override public TerminalRepository terminal() { return null; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public void close() { }
    }

    private static final class MemoryPackageRepository implements PackageRepository {
        private final Map<PackageRelease.Hash, PackageRelease> byHash = new LinkedHashMap<>();
        private final Map<PackageRelease.Coordinate, PackageRelease> byCoordinate =
                new LinkedHashMap<>();
        private PackageIndex lastIndex;

        @Override public ReleaseWriteResult registerRelease(PackageIndex index) {
            PackageRelease release = index.release();
            PackageRelease coordinate = byCoordinate.get(release.coordinate());
            if (coordinate != null) {
                return coordinate.packageHash().equals(release.packageHash())
                        ? ReleaseWriteResult.ALREADY_PRESENT
                        : ReleaseWriteResult.COORDINATE_CONFLICT;
            }
            lastIndex = index;
            byHash.put(release.packageHash(), release);
            byCoordinate.put(release.coordinate(), release);
            return ReleaseWriteResult.REGISTERED;
        }
        @Override public Optional<PackageRelease> findRelease(PackageRelease.Hash hash) {
            return Optional.ofNullable(byHash.get(hash));
        }
        @Override public Optional<PackageRelease> findRelease(PackageRelease.Coordinate coordinate) {
            return Optional.ofNullable(byCoordinate.get(coordinate));
        }
        @Override public void saveEnvironment(PackageEnvironment environment) {
            throw new UnsupportedOperationException();
        }
        @Override public void saveBinding(PackageBinding binding) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<PackageBinding> findBinding(UUID id, String binding) {
            return Optional.empty();
        }
        @Override public void saveProcessBinding(ProcessPackageBinding binding) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<ProcessPackageBinding> findProcessBinding(UUID process,
                                                                            String name) {
            return Optional.empty();
        }
    }

    private static final class MemoryVfsRepository implements VfsRepository {
        private final Map<ObjectHash, StoredObject> objects = new LinkedHashMap<>();

        @Override public void saveObject(StoredObject object) {
            objects.putIfAbsent(object.objectHash(), object);
        }
        @Override public Optional<StoredObject> findObject(ObjectHash hash) {
            return Optional.ofNullable(objects.get(hash));
        }
        @Override public Optional<VfsNode> findNode(UUID id) { return Optional.empty(); }
        @Override public Optional<VfsNode> findChild(UUID owner, Optional<UUID> parent,
                                                     String name) { return Optional.empty(); }
        @Override public void insertNode(VfsNode node) { throw new UnsupportedOperationException(); }
        @Override public boolean replaceContent(UUID id, Optional<ObjectHash> expected,
                                                ObjectHash replacement, Instant at) { return false; }
        @Override public FileRevision appendRevision(UUID revisionId, UUID nodeId, UUID ownerId,
                ObjectHash hash, UUID createdBy, Instant at) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<FileRevision> findRevision(UUID id, long number) {
            return Optional.empty();
        }
        @Override public List<FileRevision> findRevisions(UUID id) { return List.of(); }
        @Override public void insertMount(VfsMount mount) { throw new UnsupportedOperationException(); }
        @Override public Optional<VfsMount> findMount(UUID id) { return Optional.empty(); }
        @Override public List<VfsMount> findMounts(UUID owner) { return List.of(); }
        @Override public boolean disableMount(UUID id, UUID owner) { return false; }
    }
}
