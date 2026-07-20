package com.follarce.extension.pack;

import com.follarce.kernel.Constants;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical VFS and host paths used by the package manager. */
public final class PackagePaths {
    public static final String INDEX_FILE = Constants.SYSTEM_PACKAGE_MANAGER_DATA_PATH + "index.json";

    private PackagePaths() {}

    public static String userPackagePath(String user) {
        return userAppPath(user) + "package/";
    }

    public static String userDataPath(String user) {
        return userAppPath(user) + "data/";
    }

    public static String userPackageDataPath(String user) {
        return userDataPath(user) + "package/";
    }

    public static String userRootFile(String user) {
        return userPackagePath(user) + "installed.json";
    }

    public static String userPinsFile(String user) {
        return userPackageDataPath(user) + "pins.json";
    }

    public static String userTransactionsPath(String user) {
        return userPackageDataPath(user) + "transactions/";
    }

    public static String userPackagesDataPath(String user) {
        return userPackageDataPath(user) + "packages/";
    }

    public static String userPackageInstanceDataPath(String user, PackageCoordinate coordinate) {
        return userPackagesDataPath(user) + coordinate.namespace() + "/" + coordinate.name() + "/";
    }

    public static String userTransactionFile(String user, String transactionId) {
        validateSafeName(transactionId, "transaction ID");
        return userTransactionsPath(user) + transactionId + ".json";
    }

    public static String referenceFile(String hash) {
        validateHash(hash);
        return Constants.SYSTEM_PACKAGE_REFS_PATH + hash + ".json";
    }

    public static String objectDirectory(String hash) {
        validateHash(hash);
        return Constants.SYSTEM_PACKAGE_OBJECTS_PATH + "sha256/" + hash.substring(0, 2) + "/";
    }

    public static String objectVfsPath(String hash) {
        return objectDirectory(hash) + hash + ".pack";
    }

    public static Path objectHostPath(String hash) {
        return Path.of(PathUtil.toRealPath(objectVfsPath(hash)));
    }

    public static Path hostPath(String vfsPath) {
        return Path.of(PathUtil.toRealPath(vfsPath));
    }

    public static String normalizeUserImportPrefix(String user) {
        return userPackagePath(user).substring(0, userPackagePath(user).length() - 1);
    }

    public static void ensureDirectory(String vfsPath, String owner, boolean privateDirectory) {
        String normalized = PathUtil.normalizePath(vfsPath);
        StringBuilder current = new StringBuilder();
        for (String component : normalized.substring(1).split("/")) {
            if (component.isBlank()) continue;
            current.append('/').append(component);
            String currentPath = current.toString();
            try {
                Files.createDirectories(hostPath(currentPath));
            } catch (Exception e) {
                throw new PackageException("Failed to create package directory: " + currentPath, e);
            }
            FileUtil.createDirectoryMetaData(currentPath);
            if (!currentPath.equals(normalized)) continue;
            Map<String, Object> metadata = FileUtil.readDirectoryMetaData(currentPath);
            boolean changed = false;
            if (owner != null && !owner.equals(metadata.get("Owner"))) {
                metadata.put("Owner", owner);
                changed = true;
            }
            if (privateDirectory) {
                Map<String, Object> permission = permissions(metadata);
                if (!"".equals(permission.get(Constants.PERM_OTHERS))) {
                    permission.put(Constants.PERM_OTHERS, "");
                    changed = true;
                }
            }
            if (changed) FileUtil.writeDirectoryMetaData(currentPath, metadata);
        }
    }

    public static void validateHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new PackageException("Invalid SHA-256 package hash: " + hash);
        }
    }

    public static void validateUser(String user) {
        if (user == null || !user.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
            throw new PackageException("Invalid package user: " + user);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> permissions(Map<String, Object> metadata) {
        Object value = metadata.get("Permission");
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Constants.PERM_OWNER, Constants.PERM_READ + ", " + Constants.PERM_WRITE);
        result.put(Constants.PERM_OTHERS, Constants.PERM_READ);
        metadata.put("Permission", result);
        return result;
    }

    private static String userAppPath(String user) {
        validateUser(user);
        return Constants.USER_HOME_PREFIX + user + "/app/";
    }

    private static void validateSafeName(String name, String label) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new PackageException("Invalid " + label + ": " + name);
        }
    }
}
