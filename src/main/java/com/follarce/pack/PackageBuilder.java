package com.follarce.pack;

import java.io.BufferedOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates canonical package v1 archives whose exact bytes are content-addressed. */
public final class PackageBuilder {
    private static final LocalDateTime ZIP_EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0);

    private PackageBuilder() {}

    public record BuildResult(Path path, PackageCoordinate coordinate, String integrity, long size) {}

    public static BuildResult build(Path sourceDirectory, Path outputFile) {
        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path output = outputFile.toAbsolutePath().normalize();
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new PackageException("Package source is not a directory: " + source);
        }
        if (Files.isSymbolicLink(source)) throw new PackageException("Package source cannot be a symbolic link");
        if (output.startsWith(source)) {
            throw new PackageException("Package output must be outside the source directory: " + output);
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new PackageException("Package output already exists: " + output);
        }

        List<SourceEntry> entries = collectEntries(source);
        if (entries.stream().noneMatch(entry -> entry.name().equals("manifest.json"))) {
            throw new PackageException("Package source is missing manifest.json");
        }

        Path parent = output.getParent();
        if (parent == null) throw new PackageException("Package output has no parent directory");
        Path temp = parent.resolve(output.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            Files.deleteIfExists(temp);
            try (ZipOutputStream zip = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temp,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))) {
                zip.setLevel(0);
                for (SourceEntry sourceEntry : entries) {
                    byte[] content = Files.readAllBytes(sourceEntry.path());
                    CRC32 crc = new CRC32();
                    crc.update(content);
                    ZipEntry entry = new ZipEntry(sourceEntry.name());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(content.length);
                    entry.setCompressedSize(content.length);
                    entry.setCrc(crc.getValue());
                    entry.setTimeLocal(ZIP_EPOCH);
                    entry.setExtra(new byte[0]);
                    zip.putNextEntry(entry);
                    zip.write(content);
                    zip.closeEntry();
                }
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            PackageArchive archive = PackageArchive.read(temp);
            try {
                Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, output);
            }
            forceDirectory(parent);
            return new BuildResult(output, archive.manifest().coordinate(), archive.integrity(),
                    Files.size(output));
        } catch (PackageException e) {
            deleteQuietly(temp);
            throw e;
        } catch (Exception e) {
            deleteQuietly(temp);
            throw new PackageException("Failed to build package archive: " + output, e);
        }
    }

    private static List<SourceEntry> collectEntries(Path source) {
        List<SourceEntry> entries = new ArrayList<>();
        long total = 0;
        try (var stream = Files.walk(source)) {
            List<Path> paths = stream.filter(path -> !path.equals(source)).toList();
            for (Path path : paths) {
                if (Files.isSymbolicLink(path)) {
                    throw new PackageException("Package source contains a symbolic link: " + path);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PackageException("Unsupported package source entry: " + path);
                }
                String name = source.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                PackageArchive.validateEntryName(name);
                long size = Files.size(path);
                if (size > PackageArchive.MAX_ENTRY_BYTES) {
                    throw new PackageException("Package source entry exceeds size limit: " + name);
                }
                total += size;
                if (total > PackageArchive.MAX_TOTAL_ENTRY_BYTES) {
                    throw new PackageException("Package source exceeds total size limit");
                }
                entries.add(new SourceEntry(name, path));
            }
        } catch (PackageException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageException("Failed to scan package source: " + source, e);
        }
        if (entries.size() > PackageArchive.MAX_ENTRIES) {
            throw new PackageException("Package source contains too many files");
        }
        entries.sort(Comparator.comparing(SourceEntry::name));
        return entries;
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {
            // Some filesystems do not support opening directories; the file itself is already durable.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private record SourceEntry(String name, Path path) {}
}
