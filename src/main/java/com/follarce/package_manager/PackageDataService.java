package com.follarce.package_manager;

import com.follarce.domain.packageinfo.PackageDataEntry;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.vfs.ObjectHash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic SQLite archive import/export for one user's private package data
 * space. Exported archives are ordinary user VFS files; they are never deleted
 * by package uninstallation.
 */
public final class PackageDataService {
    public static final int ARCHIVE_FORMAT_VERSION = 1;
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;

    private PackageDataService() {
    }

    /** Serializes every file and directory entry of one package data space. */
    public static byte[] exportArchive(TransactionContext transaction, UUID ownerId,
                                       ObjectHash databaseFileHash) {
        PackageRepository packages = transaction.packages();
        try {
            Path cache = Files.createTempFile("cilexec-package-data-export-", ".db");
            try {
                try (Connection connection = DriverManager.getConnection(
                        "jdbc:sqlite:file:" + cache.toAbsolutePath())) {
                    connection.createStatement().execute(
                            "PRAGMA user_version = " + ARCHIVE_FORMAT_VERSION);
                    connection.createStatement().execute(
                            "CREATE TABLE package_data_entry("
                                    + "relative_path TEXT PRIMARY KEY,"
                                    + "entry_type TEXT NOT NULL,"
                                    + "content BLOB)");
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO package_data_entry VALUES (?,?,?)")) {
                        writeEntries(insert, packages, ownerId, databaseFileHash,
                                "", new int[]{0});
                    }
                }
                byte[] bytes = Files.readAllBytes(cache);
                if (bytes.length > MAX_ARCHIVE_BYTES) {
                    throw new IllegalStateException(
                            "Package data export exceeds the 64 MiB archive limit");
                }
                return bytes;
            } finally {
                Files.deleteIfExists(cache);
            }
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("Cannot export package data", exception);
        }
    }

    /** Merges an archive into an installed package data space, replacing collisions. */
    public static long importArchive(TransactionContext transaction, UUID ownerId,
                                     ObjectHash databaseFileHash, byte[] archiveBytes) {
        PackageRepository packages = transaction.packages();
        if (archiveBytes == null || archiveBytes.length < 100) {
            throw new IllegalArgumentException("Package data archive is empty or truncated");
        }
        Path cache = null;
        try {
            cache = Files.createTempFile("cilexec-package-data-import-", ".db");
            Files.write(cache, archiveBytes);
            long entries = 0;
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + cache.toAbsolutePath() + "?mode=ro&immutable=1")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA query_only=ON");
                    try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                        if (!version.next()
                                || version.getInt(1) != ARCHIVE_FORMAT_VERSION) {
                            throw new IllegalArgumentException(
                                    "Unsupported package data archive format");
                        }
                    }
                }
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                             "SELECT relative_path, entry_type, content "
                                     + "FROM package_data_entry ORDER BY relative_path")) {
                    while (rows.next()) {
                        String path = rows.getString(1);
                        validatePath(path);
                        String type = rows.getString(2);
                        byte[] content = rows.getBytes(3);
                        if ("FILE".equals(type)) {
                            packages.removeDataEntry(ownerId, databaseFileHash, path);
                            packages.writeDataEntry(ownerId, databaseFileHash, path,
                                    content == null ? new byte[0] : content,
                                    "application/octet-stream", -1);
                        } else if ("DIRECTORY".equals(type)) {
                            packages.mkdirDataEntry(ownerId, databaseFileHash, path);
                        } else {
                            throw new IllegalArgumentException(
                                    "Unknown package data entry type in archive: " + type);
                        }
                        if (++entries > MAX_ARCHIVE_ENTRIES) {
                            throw new IllegalArgumentException(
                                    "Package data archive exceeds 10000 entries");
                        }
                    }
                }
            }
            return entries;
        } catch (SQLException | IOException exception) {
            throw new IllegalArgumentException("Cannot import package data archive", exception);
        } finally {
            if (cache != null) {
                try {
                    Files.deleteIfExists(cache);
                } catch (IOException ignored) {
                    cache.toFile().deleteOnExit();
                }
            }
        }
    }

    private static void writeEntries(PreparedStatement insert,
                                     PackageRepository packages, UUID ownerId,
                                     ObjectHash databaseFileHash, String prefix,
                                     int[] counter) throws SQLException {
        List<PackageDataEntry> entries = packages.listDataEntries(
                ownerId, databaseFileHash, prefix);
        for (PackageDataEntry entry : entries) {
            String path = prefix.isEmpty() ? entry.relativePath()
                    : prefix + "/" + entry.relativePath();
            if (entry.isDirectory()) {
                insert.setString(1, path);
                insert.setString(2, "DIRECTORY");
                insert.setNull(3, java.sql.Types.BLOB);
                insert.executeUpdate();
                writeEntries(insert, packages, ownerId, databaseFileHash,
                        path, counter);
            } else {
                byte[] content = packages.readDataEntry(ownerId, databaseFileHash, path);
                if (content == null) continue;
                insert.setString(1, path);
                insert.setString(2, "FILE");
                insert.setBytes(3, content);
                insert.executeUpdate();
            }
            if (++counter[0] > MAX_ARCHIVE_ENTRIES) {
                throw new IllegalStateException(
                        "Package data export exceeds 10000 entries");
            }
        }
    }

    private static void validatePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.endsWith("/")
                || path.indexOf('\\') >= 0 || path.length() > 1024
                || path.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Package data path is invalid");
        }
        for (String part : path.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")
                    || part.length() > 255) {
                throw new IllegalArgumentException("Package data path is not canonical");
            }
        }
    }
}
