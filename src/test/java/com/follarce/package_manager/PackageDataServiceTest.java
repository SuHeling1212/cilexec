package com.follarce.package_manager;

import com.follarce.domain.packageinfo.PackageDataEntry;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.vfs.ObjectHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageDataServiceTest {
    private static final ObjectHash FILE_HASH = new ObjectHash("a".repeat(64));
    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void exportsAndImportsFilesAndDirectoriesDeterministically() throws Exception {
        MemoryPackageDataRepository source = new MemoryPackageDataRepository();
        source.mkdirDataEntry(OWNER, FILE_HASH, "cache");
        source.writeDataEntry(OWNER, FILE_HASH, "config.json",
                "{\"theme\":\"dark\"}".getBytes(StandardCharsets.UTF_8), "application/json", -1);
        source.writeDataEntry(OWNER, FILE_HASH, "cache/index.json",
                "[]".getBytes(StandardCharsets.UTF_8), "application/json", -1);

        byte[] archive = PackageDataService.exportArchive(new FakeTransaction(source), OWNER,
                FILE_HASH);

        // The archive is a versioned SQLite database with the documented schema.
        java.nio.file.Path file = java.nio.file.Files.createTempFile("pkg-data-", ".db");
        try {
            java.nio.file.Files.write(file, archive);
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + file.toAbsolutePath())) {
                int format;
                try (Statement statement = connection.createStatement();
                     ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                    assertTrue(version.next());
                    format = version.getInt(1);
                }
                assertEquals(PackageDataService.ARCHIVE_FORMAT_VERSION, format,
                        "archive bytes=" + archive.length);
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                             "SELECT relative_path, entry_type, content "
                                     + "FROM package_data_entry ORDER BY relative_path")) {
                    assertTrue(rows.next());
                    assertEquals("cache", rows.getString(1));
                    assertEquals("DIRECTORY", rows.getString(2));
                    assertTrue(rows.next());
                    assertEquals("cache/index.json", rows.getString(1));
                    assertArrayEquals("[]".getBytes(StandardCharsets.UTF_8), rows.getBytes(3));
                    assertTrue(rows.next());
                    assertEquals("config.json", rows.getString(1));
                    assertArrayEquals("{\"theme\":\"dark\"}".getBytes(StandardCharsets.UTF_8),
                            rows.getBytes(3));
                    assertFalse(rows.next());
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }

        MemoryPackageDataRepository target = new MemoryPackageDataRepository();
        long imported = PackageDataService.importArchive(new FakeTransaction(target), OWNER,
                FILE_HASH, archive);
        assertEquals(3, imported);
        assertArrayEquals("{\"theme\":\"dark\"}".getBytes(StandardCharsets.UTF_8),
                target.readDataEntry(OWNER, FILE_HASH, "config.json"));
        assertArrayEquals("[]".getBytes(StandardCharsets.UTF_8),
                target.readDataEntry(OWNER, FILE_HASH, "cache/index.json"));
    }

    @Test
    void importReplacesCollidingEntriesAndRejectsMalformedArchives() throws Exception {
        MemoryPackageDataRepository target = new MemoryPackageDataRepository();
        target.writeDataEntry(OWNER, FILE_HASH, "config.json",
                "old".getBytes(StandardCharsets.UTF_8), "application/json", -1);

        MemoryPackageDataRepository source = new MemoryPackageDataRepository();
        source.writeDataEntry(OWNER, FILE_HASH, "config.json",
                "new".getBytes(StandardCharsets.UTF_8), "application/json", -1);
        byte[] archive = PackageDataService.exportArchive(new FakeTransaction(source), OWNER,
                FILE_HASH);

        PackageDataService.importArchive(new FakeTransaction(target), OWNER, FILE_HASH, archive);
        assertArrayEquals("new".getBytes(StandardCharsets.UTF_8),
                target.readDataEntry(OWNER, FILE_HASH, "config.json"));

        assertThrows(IllegalArgumentException.class, () -> PackageDataService.importArchive(
                new FakeTransaction(target), OWNER, FILE_HASH, "not-a-sqlite-file".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsTraversalPathsDuringImport() throws Exception {
        MemoryPackageDataRepository source = new MemoryPackageDataRepository();
        source.writeDataEntry(OWNER, FILE_HASH, "ok.txt",
                "ok".getBytes(StandardCharsets.UTF_8), "text/plain", -1);
        byte[] archive = PackageDataService.exportArchive(new FakeTransaction(source), OWNER,
                FILE_HASH);

        byte[] malicious = rewriteEntry(archive, "ok.txt", "../escape.txt");
        MemoryPackageDataRepository target = new MemoryPackageDataRepository();
        assertThrows(IllegalArgumentException.class, () -> PackageDataService.importArchive(
                new FakeTransaction(target), OWNER, FILE_HASH, malicious));
        assertThrows(IllegalArgumentException.class, () -> PackageDataService.importArchive(
                new FakeTransaction(target), OWNER, FILE_HASH,
                "x".repeat(64).getBytes(StandardCharsets.UTF_8)));
    }

    /** Rewrites one entry path inside an exported SQLite archive. */
    private static byte[] rewriteEntry(byte[] archive, String from, String to) throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("pkg-rewrite-", ".db");
        try {
            java.nio.file.Files.write(file, archive);
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + file.toAbsolutePath());
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=OFF");
                statement.execute("UPDATE package_data_entry SET relative_path='" + to
                        + "' WHERE relative_path='" + from + "'");
            }
            return java.nio.file.Files.readAllBytes(file);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private record FakeTransaction(PackageRepository packages) implements TransactionContext {
        @Override public com.follarce.domain.port.ProgramRepository programs() {
            throw new UnsupportedOperationException();
        }
        @Override public com.follarce.domain.port.ProcessRepository processes() {
            throw new UnsupportedOperationException();
        }
        @Override public com.follarce.domain.port.SchedulerRepository scheduler() {
            throw new UnsupportedOperationException();
        }
        @Override public com.follarce.domain.port.IpcRepository ipc() {
            throw new UnsupportedOperationException();
        }
        @Override public com.follarce.domain.port.TimerRepository timers() {
            throw new UnsupportedOperationException();
        }
        @Override public com.follarce.domain.port.VfsRepository vfs() {
            throw new UnsupportedOperationException();
        }
        @Override public PackageRepository packages() { return packages; }
        @Override public com.follarce.domain.port.EffectRepository effects() {
            throw new UnsupportedOperationException();
        }
        @Override public com.follarce.domain.port.AuthRepository auth() {
            throw new UnsupportedOperationException();
        }
        @Override public com.follarce.domain.port.AuditRepository audit() {
            throw new UnsupportedOperationException();
        }
        @Override public com.follarce.domain.port.TerminalRepository terminal() {
            throw new UnsupportedOperationException();
        }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public void close() { }
    }

    /** In-memory package data implementation behind the repository port. */
    private static final class MemoryPackageDataRepository implements PackageRepository {
        private final Map<String, Map<String, Entry>> spaces = new LinkedHashMap<>();

        @Override public PackageRepository.ReleaseWriteResult registerRelease(
                com.follarce.domain.packageinfo.PackageIndex packageIndex) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<com.follarce.domain.packageinfo.PackageRelease> findRelease(
                com.follarce.domain.packageinfo.PackageRelease.Hash packageHash) {
            return Optional.empty();
        }

        @Override public Optional<com.follarce.domain.packageinfo.PackageRelease> findRelease(
                com.follarce.domain.packageinfo.PackageRelease.Coordinate coordinate) {
            return Optional.empty();
        }

        @Override public void saveProcessBinding(
                com.follarce.domain.packageinfo.ProcessPackageBinding binding) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<com.follarce.domain.packageinfo.ProcessPackageBinding>
                findProcessBinding(UUID processUid, String importName) {
            return Optional.empty();
        }

        private Map<String, Entry> space(UUID ownerId, ObjectHash fileHash) {
            return spaces.computeIfAbsent(ownerId + ":" + fileHash.value(),
                    ignored -> new LinkedHashMap<>());
        }

        @Override public byte[] readDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                              String path) {
            Entry entry = space(ownerId, databaseFileHash).get(path);
            return entry == null || entry.directory ? null : entry.content;
        }

        @Override public PackageDataEntry writeDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                                         String path, byte[] content,
                                                         String mediaType, long expectedVersion) {
            Map<String, Entry> entries = space(ownerId, databaseFileHash);
            Entry existing = entries.get(path);
            if (expectedVersion >= 0 && (existing == null
                    || existing.version != expectedVersion)) {
                throw new IllegalStateException("CAS conflict");
            }
            entries.put(path, new Entry(false, content,
                    existing == null ? 1 : existing.version + 1));
            return new PackageDataEntry(path, "FILE",
                    Optional.of(ObjectHash.sha256(
                            new com.follarce.domain.vfs.BinaryContent(content))),
                    content.length, entries.get(path).version, Optional.of(Instant.now()));
        }

        @Override public PackageDataEntry appendDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                                          String path, byte[] content,
                                                          long expectedVersion) {
            Entry existing = space(ownerId, databaseFileHash).get(path);
            if (existing == null || existing.version != expectedVersion) {
                throw new IllegalStateException("CAS conflict");
            }
            byte[] combined = java.util.Arrays.copyOf(existing.content,
                    existing.content.length + content.length);
            System.arraycopy(content, 0, combined, existing.content.length, content.length);
            space(ownerId, databaseFileHash).put(path,
                    new Entry(false, combined, existing.version + 1));
            return new PackageDataEntry(path, "FILE", Optional.empty(), combined.length,
                    existing.version + 1, Optional.of(Instant.now()));
        }

        @Override public List<PackageDataEntry> listDataEntries(UUID ownerId,
                                                                ObjectHash databaseFileHash,
                                                                String path) {
            String prefix = path == null || path.isBlank() ? "" : path + "/";
            List<PackageDataEntry> result = new ArrayList<>();
            space(ownerId, databaseFileHash).forEach((key, entry) -> {
                if (!key.startsWith(prefix)) return;
                String remainder = key.substring(prefix.length());
                if (remainder.indexOf('/') >= 0) return;
                result.add(new PackageDataEntry(prefix.isEmpty() ? key : remainder,
                        entry.directory ? "DIRECTORY" : "FILE", Optional.empty(),
                        entry.directory ? 0 : entry.content.length, entry.version,
                        Optional.of(Instant.now())));
            });
            return List.copyOf(result);
        }

        @Override public boolean removeDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                                 String path) {
            return space(ownerId, databaseFileHash).remove(path) != null;
        }

        @Override public PackageDataEntry renameDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                                          String from, String to) {
            Entry entry = space(ownerId, databaseFileHash).remove(from);
            if (entry == null) throw new IllegalStateException("missing source");
            space(ownerId, databaseFileHash).put(to, entry);
            return new PackageDataEntry(to, entry.directory ? "DIRECTORY" : "FILE",
                    Optional.empty(), entry.directory ? 0 : entry.content.length, entry.version,
                    Optional.of(Instant.now()));
        }

        @Override public void mkdirDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                             String path) {
            space(ownerId, databaseFileHash).putIfAbsent(path, new Entry(true, null, 0));
        }

        @Override public long clearDataEntries(UUID ownerId, ObjectHash databaseFileHash) {
            int removed = space(ownerId, databaseFileHash).size();
            space(ownerId, databaseFileHash).clear();
            return removed;
        }

        private record Entry(boolean directory, byte[] content, long version) { }
    }
}
