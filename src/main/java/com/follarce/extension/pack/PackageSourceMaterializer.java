package com.follarce.extension.pack;

import com.follarce.kernel.Constants;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

/** Copies a VFS package source tree without mutable CilExec metadata wrappers. */
final class PackageSourceMaterializer {
    private PackageSourceMaterializer() {}

    static void materialize(String sourceVfsPath, Path target, String user) {
        Path source = PackagePaths.hostPath(sourceVfsPath).toAbsolutePath().normalize();
        Path cleanTarget = target.toAbsolutePath().normalize();
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new PackageException("Package source is not a regular directory: " + sourceVfsPath);
        }
        if (cleanTarget.startsWith(source) || source.startsWith(cleanTarget)) {
            throw new PackageException("Package staging directory overlaps its source");
        }
        deleteTree(cleanTarget);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().isEmpty()) {
                    Files.createDirectories(cleanTarget);
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new PackageException("Package source contains a symbolic link: " + relative);
                }
                if (path.getFileName().toString().equals(Constants.META_DIR_FILE)) continue;
                Path destination = cleanTarget.resolve(relative.toString()).normalize();
                if (!destination.startsWith(cleanTarget)) {
                    throw new PackageException("Package source path escapes staging: " + relative);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PackageException("Unsupported VFS package source entry: " + relative);
                }

                Files.createDirectories(destination.getParent());
                byte[] raw = Files.readAllBytes(path);
                if (hasVfsMetadata(raw)) {
                    String childVfsPath = PathUtil.normalizePath(sourceVfsPath + "/"
                            + relative.toString().replace(path.getFileSystem().getSeparator(), "/"));
                    if (!FileUtil.checkFilePermission(childVfsPath, Constants.PERM_READ, user)) {
                        throw new PackageException("Permission denied reading package source: " + childVfsPath);
                    }
                    Files.writeString(destination, FileUtil.read(childVfsPath), StandardCharsets.UTF_8);
                } else {
                    Files.write(destination, raw);
                }
            }
        } catch (PackageException e) {
            deleteTree(cleanTarget);
            throw e;
        } catch (Exception e) {
            deleteTree(cleanTarget);
            throw new PackageException("Failed to materialize VFS package source: " + sourceVfsPath, e);
        }
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            throw new PackageException("Failed to clean package staging directory: " + root, e);
        }
    }

    private static boolean hasVfsMetadata(byte[] bytes) {
        byte[] marker = (Constants.META_START + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length < marker.length) return false;
        for (int i = 0; i < marker.length; i++) {
            if (bytes[i] != marker[i]) return false;
        }
        return true;
    }
}
