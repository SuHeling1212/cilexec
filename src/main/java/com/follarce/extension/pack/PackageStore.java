package com.follarce.extension.pack;

import com.follarce.kernel.Constants;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Durable package object, reference, index, user-root, pin, and transaction storage. */
public final class PackageStore {

    public void initializeGlobal() {
        PackagePaths.ensureDirectory(Constants.SYSTEM_PACKAGE_OBJECTS_PATH, Constants.DEFAULT_USER_LOCAL, false);
        PackagePaths.ensureDirectory(Constants.SYSTEM_PACKAGE_OBJECTS_PATH + "sha256/",
                Constants.DEFAULT_USER_LOCAL, false);
        PackagePaths.ensureDirectory(Constants.SYSTEM_PACKAGE_MANAGER_DATA_PATH,
                Constants.DEFAULT_USER_LOCAL, false);
        PackagePaths.ensureDirectory(Constants.SYSTEM_PACKAGE_REFS_PATH,
                Constants.DEFAULT_USER_LOCAL, false);
        PackagePaths.ensureDirectory(Constants.SYSTEM_PACKAGE_STAGING_PATH,
                Constants.DEFAULT_USER_LOCAL, false);
        PackagePaths.ensureDirectory(Constants.SYSTEM_PACKAGE_REPOSITORY_PATH,
                Constants.DEFAULT_USER_LOCAL, false);
        ensureJsonFile(PackagePaths.INDEX_FILE, Constants.DEFAULT_USER_LOCAL, defaultIndex(), false);
    }

    public void initializeUser(String user) {
        PackagePaths.validateUser(user);
        PackagePaths.ensureDirectory(PackagePaths.userPackagePath(user), user, true);
        PackagePaths.ensureDirectory(PackagePaths.userDataPath(user), user, true);
        PackagePaths.ensureDirectory(PackagePaths.userPackageDataPath(user), user, true);
        PackagePaths.ensureDirectory(PackagePaths.userTransactionsPath(user), user, true);
        PackagePaths.ensureDirectory(PackagePaths.userPackagesDataPath(user), user, true);
        ensureJsonFile(PackagePaths.userRootFile(user), user, defaultRoot(user), true);
        ensureJsonFile(PackagePaths.userPinsFile(user), user, defaultPins(user), true);
    }

    public boolean containsObject(String hash) {
        return Files.isRegularFile(PackagePaths.objectHostPath(hash), LinkOption.NOFOLLOW_LINKS);
    }

    public PackageArchive readObject(String hash) {
        PackagePaths.validateHash(hash);
        Path path = PackagePaths.objectHostPath(hash);
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new PackageException("Package object not found or unsafe: sha256:" + hash);
        }
        PackageArchive archive = PackageArchive.read(path);
        if (!archive.hash().equals(hash)) {
            throw new PackageException("Package object hash mismatch: expected " + hash
                    + ", found " + archive.hash());
        }
        return archive;
    }

    public void putObject(PackageArchive archive) {
        String hash = archive.hash();
        String directory = PackagePaths.objectDirectory(hash);
        PackagePaths.ensureDirectory(directory, Constants.DEFAULT_USER_LOCAL, false);
        Path target = PackagePaths.objectHostPath(hash);
        if (Files.isSymbolicLink(target)) {
            throw new PackageException("Package object path is a symbolic link: sha256:" + hash);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            readObject(hash);
            return;
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temp, archive.bytes(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target);
            }
            forceDirectory(target.getParent());
        } catch (java.nio.file.FileAlreadyExistsException e) {
            deleteQuietly(temp);
            readObject(hash);
        } catch (Exception e) {
            deleteQuietly(temp);
            throw new PackageException("Failed to commit package object: sha256:" + hash, e);
        }
    }

    public Map<String, Object> readIndex() {
        return readJson(PackagePaths.INDEX_FILE);
    }

    public void updateIndex(Consumer<Map<String, Object>> updater) {
        updateJson(PackagePaths.INDEX_FILE, updater);
    }

    public Map<String, Object> readRoot(String user) {
        initializeUser(user);
        return readJson(PackagePaths.userRootFile(user));
    }

    public void replaceRoot(String user, Map<String, Object> expected,
                            Map<String, Object> replacement) {
        replaceJson(PackagePaths.userRootFile(user), expected, replacement,
                "Installed package root changed during transaction for user " + user);
    }

    public Map<String, Object> readPins(String user) {
        initializeUser(user);
        return readJson(PackagePaths.userPinsFile(user));
    }

    public void replacePins(String user, Map<String, Object> expected,
                            Map<String, Object> replacement) {
        replaceJson(PackagePaths.userPinsFile(user), expected, replacement,
                "Package pins changed during transaction for user " + user);
    }

    public Map<String, Object> readReferences(String hash) {
        String path = PackagePaths.referenceFile(hash);
        if (!FileUtil.exists(path)) throw new PackageException("Package references not found: sha256:" + hash);
        return readJson(path);
    }

    public void writeReferences(String hash, Map<String, Object> references) {
        String path = PackagePaths.referenceFile(hash);
        ensureJsonFile(path, Constants.DEFAULT_USER_LOCAL, references, false);
        writeJson(path, references);
    }

    public boolean referencesExist(String hash) {
        return FileUtil.exists(PackagePaths.referenceFile(hash));
    }

    public void writeTransaction(String user, String transactionId, Map<String, Object> transaction) {
        String path = PackagePaths.userTransactionFile(user, transactionId);
        ensureJsonFile(path, user, transaction, true);
        writeJson(path, transaction);
    }

    public Map<String, Object> readTransaction(String user, String transactionId) {
        String path = PackagePaths.userTransactionFile(user, transactionId);
        return FileUtil.exists(path) ? readJson(path) : null;
    }

    public List<Map<String, Object>> readTransactions(String user) {
        initializeUser(user);
        Path directory = PackagePaths.hostPath(PackagePaths.userTransactionsPath(user));
        List<Map<String, Object>> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            List<Path> paths = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            for (Path path : paths) {
                result.add(readJson(PackagePaths.userTransactionsPath(user) + path.getFileName()));
            }
            return result;
        } catch (Exception e) {
            throw new PackageException("Failed to read package transactions for " + user, e);
        }
    }

    public Set<String> listObjectHashes() {
        Path shaRoot = PackagePaths.hostPath(Constants.SYSTEM_PACKAGE_OBJECTS_PATH + "sha256/");
        Set<String> hashes = new LinkedHashSet<>();
        if (!Files.isDirectory(shaRoot)) return hashes;
        try (var paths = Files.walk(shaRoot, 2)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if (name.matches("[0-9a-f]{64}\\.pack")) hashes.add(name.substring(0, 64));
            });
            return hashes;
        } catch (Exception e) {
            throw new PackageException("Failed to list package objects", e);
        }
    }

    public void deleteObject(String hash) {
        PackagePaths.validateHash(hash);
        try {
            Files.deleteIfExists(PackagePaths.objectHostPath(hash));
            String refs = PackagePaths.referenceFile(hash);
            if (FileUtil.exists(refs)) FileUtil.removeFile(refs);
        } catch (Exception e) {
            throw new PackageException("Failed to delete package object: sha256:" + hash, e);
        }
    }

    public Path stagingFile(String transactionId) {
        if (transactionId == null || !transactionId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new PackageException("Invalid transaction ID: " + transactionId);
        }
        return PackagePaths.hostPath(Constants.SYSTEM_PACKAGE_STAGING_PATH)
                .resolve(transactionId + ".pack");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> objectMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        Map<String, Object> result = new LinkedHashMap<>();
        parent.put(key, result);
        return result;
    }

    private static Map<String, Object> defaultIndex() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("packages", new LinkedHashMap<String, Object>());
        return result;
    }

    private static Map<String, Object> defaultRoot(String user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("owner", user);
        result.put("generation", 0L);
        result.put("packages", new LinkedHashMap<String, Object>());
        return result;
    }

    private static Map<String, Object> defaultPins(String user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("owner", user);
        result.put("packages", new LinkedHashMap<String, Object>());
        return result;
    }

    private static Map<String, Object> readJson(String path) {
        try {
            return JsonUtil.parseToMapStrict(FileUtil.read(path));
        } catch (Exception e) {
            throw new PackageException("Invalid package state file: " + path, e);
        }
    }

    private static void ensureJsonFile(String path, String owner, Map<String, Object> initial,
                                       boolean privateFile) {
        if (!FileUtil.exists(path)) {
            String parent = PathUtil.getParentPath(path);
            String name = PathUtil.getFileName(path);
            FileUtil.createFile(parent, name);
            FileUtil.write(path, JsonUtil.toJson(initial));
        }
        FileUtil.updateFileMetaData(path, metadata -> {
            metadata.put("Owner", owner);
            if (privateFile) {
                objectMap(metadata, "Permission").put(Constants.PERM_OTHERS, "");
            }
        });
    }

    private static void writeJson(String path, Map<String, Object> value) {
        Map<String, Object> replacement = JsonUtil.deepCopy(value);
        updateJson(path, current -> {
            current.clear();
            current.putAll(replacement);
        });
    }

    private static void updateJson(String path, Consumer<Map<String, Object>> updater) {
        try {
            JsonUtil.updateFile(path, updater);
        } catch (PackageException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageException("Failed to update package state file: " + path, e);
        }
    }

    private static void replaceJson(String path, Map<String, Object> expected,
                                    Map<String, Object> replacement, String conflictMessage) {
        Map<String, Object> expectedCopy = JsonUtil.deepCopy(expected);
        Map<String, Object> replacementCopy = JsonUtil.deepCopy(replacement);
        updateJson(path, current -> {
            if (!current.equals(expectedCopy)) throw new PackageException(conflictMessage);
            current.clear();
            current.putAll(replacementCopy);
        });
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }
}
