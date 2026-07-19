package com.follarce.pack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** A fully verified immutable v1 package archive. */
public final class PackageArchive {
    public static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    public static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    public static final long MAX_TOTAL_ENTRY_BYTES = 128L * 1024 * 1024;
    public static final int MAX_ENTRIES = 4096;

    private final byte[] bytes;
    private final String hash;
    private final Map<String, byte[]> entries;
    private final PackageManifest manifest;

    private PackageArchive(byte[] bytes, String hash, Map<String, byte[]> entries,
                           PackageManifest manifest) {
        this.bytes = bytes;
        this.hash = hash;
        this.entries = Collections.unmodifiableMap(entries);
        this.manifest = manifest;
    }

    public static PackageArchive read(Path path) {
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_ARCHIVE_BYTES) {
                throw new PackageException("Package archive size must be between 1 and "
                        + MAX_ARCHIVE_BYTES + " bytes: " + path);
            }
            return read(Files.readAllBytes(path));
        } catch (PackageException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageException("Failed to read package archive: " + path, e);
        }
    }

    public static PackageArchive read(byte[] archiveBytes) {
        if (archiveBytes == null || archiveBytes.length == 0
                || archiveBytes.length > MAX_ARCHIVE_BYTES) {
            throw new PackageException("Invalid package archive byte length");
        }
        Map<String, byte[]> entries = readEntries(archiveBytes);
        byte[] manifestBytes = entries.get("manifest.json");
        if (manifestBytes == null) throw new PackageException("Package archive is missing manifest.json");
        PackageManifest manifest = PackageManifestParser.parse(decodeUtf8(manifestBytes, "manifest.json"));
        verifyManifestContent(manifest, entries);
        return new PackageArchive(Arrays.copyOf(archiveBytes, archiveBytes.length),
                sha256Hex(archiveBytes), entries, manifest);
    }

    public String hash() {
        return hash;
    }

    public String integrity() {
        return "sha256:" + hash;
    }

    public PackageManifest manifest() {
        return manifest;
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public boolean hasEntry(String name) {
        return entries.containsKey(name);
    }

    public byte[] entryBytes(String name) {
        byte[] value = entries.get(name);
        if (value == null) throw new PackageException("Package entry not found: " + name);
        return Arrays.copyOf(value, value.length);
    }

    public String readUtf8(String name) {
        return decodeUtf8(entryBytes(name), name);
    }

    public List<String> payloadModules() {
        List<String> modules = new ArrayList<>();
        for (String name : entries.keySet()) {
            if (name.startsWith("payload/") && name.toLowerCase(Locale.ROOT).endsWith(".fcl")) {
                modules.add(name);
            }
        }
        Collections.sort(modules);
        return modules;
    }

    public List<String> entryNames() {
        return List.copyOf(entries.keySet());
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new PackageException("SHA-256 is unavailable", e);
        }
    }

    public static void validateEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("\\") || name.indexOf('\0') >= 0 || name.endsWith("/")) {
            throw new PackageException("Invalid package entry path: " + name);
        }
        String[] parts = name.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".")) {
                throw new PackageException("Invalid package entry path: " + name);
            }
        }
    }

    private static Map<String, byte[]> readEntries(byte[] archiveBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                validateEntryName(name);
                if (entry.isDirectory()) throw new PackageException("Directory ZIP entries are not allowed: " + name);
                if (entry.getMethod() != ZipEntry.STORED) {
                    throw new PackageException("Package v1 only allows STORED ZIP entries: " + name);
                }
                if (entries.containsKey(name)) throw new PackageException("Duplicate package entry: " + name);
                if (entries.size() >= MAX_ENTRIES) throw new PackageException("Package contains too many entries");

                ByteArrayOutputStream content = new ByteArrayOutputStream();
                int read;
                long entrySize = 0;
                while ((read = zip.read(buffer)) != -1) {
                    entrySize += read;
                    total += read;
                    if (entrySize > MAX_ENTRY_BYTES) {
                        throw new PackageException("Package entry exceeds size limit: " + name);
                    }
                    if (total > MAX_TOTAL_ENTRY_BYTES) {
                        throw new PackageException("Package uncompressed content exceeds size limit");
                    }
                    content.write(buffer, 0, read);
                }
                entries.put(name, content.toByteArray());
                zip.closeEntry();
            }
        } catch (PackageException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageException("Invalid package ZIP: " + e.getMessage(), e);
        }
        if (entries.isEmpty()) throw new PackageException("Package archive is empty");
        return entries;
    }

    private static void verifyManifestContent(PackageManifest manifest, Map<String, byte[]> entries) {
        requireEntry(entries, manifest.entry(), "entry");
        for (Map.Entry<String, PackageManifest.Export> item : manifest.exports().entrySet()) {
            PackageManifest.Export export = item.getValue();
            byte[] moduleBytes = requireEntry(entries, export.module(), "export module");
            String module = decodeUtf8(moduleBytes, export.module());
            Pattern definition = Pattern.compile("(?m)^\\s*func\\s+"
                    + Pattern.quote(export.symbol()) + "\\s*\\(");
            if (!definition.matcher(module).find()) {
                throw new PackageException("Export " + item.getKey() + " references missing function "
                        + export.symbol() + " in " + export.module());
            }
        }
        for (String resource : manifest.resources()) requireEntry(entries, resource, "resource");
        for (PackageManifest.Hook hook : manifest.lifecycle().values()) {
            requireEntry(entries, hook.script(), "lifecycle hook");
        }
        for (String name : entries.keySet()) {
            if (name.toLowerCase(Locale.ROOT).endsWith(".fcl")
                    && !name.startsWith("payload/") && !name.startsWith("hooks/")) {
                throw new PackageException("FCL files are only allowed in payload/ or hooks/: " + name);
            }
            if (name.toLowerCase(Locale.ROOT).endsWith(".fcl")) decodeUtf8(entries.get(name), name);
        }
    }

    private static byte[] requireEntry(Map<String, byte[]> entries, String path, String label) {
        byte[] value = entries.get(path);
        if (value == null) throw new PackageException("Declared " + label + " is missing: " + path);
        return value;
    }

    private static String decodeUtf8(byte[] bytes, String name) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new PackageException("Package text entry is not valid UTF-8: " + name, e);
        }
    }
}
