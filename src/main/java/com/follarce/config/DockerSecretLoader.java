package com.follarce.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/** Reads a Docker/external secret without retaining it as application configuration text. */
public final class DockerSecretLoader {
    private static final int MAX_SECRET_BYTES = 64 * 1024;
    private DockerSecretLoader() {
    }

    public static SecretValue read(Path path) {
        if (path == null) {
            throw new ConfigException("Secret path is required");
        }
        byte[] bytes = null;
        try {
            if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)) {
                throw new ConfigException("Secret file does not exist: " + path);
            }
            requirePrivatePosixPermissions(path);
            if (Files.size(path) > MAX_SECRET_BYTES) {
                throw new ConfigException("Secret file exceeds 64 KiB: " + path);
            }
            bytes = readAllBytesWithoutFollowingLinks(path);
            if (bytes.length > MAX_SECRET_BYTES) {
                throw new ConfigException("Secret file exceeds 64 KiB: " + path);
            }
            int length = bytes.length;
            while (length > 0 && (bytes[length - 1] == '\n' || bytes[length - 1] == '\r')) {
                length--;
            }
            if (length == 0) {
                throw new ConfigException("Secret file is empty: " + path);
            }
            java.nio.CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes, 0, length));
            char[] chars = new char[decoded.remaining()];
            decoded.get(chars);
            return new SecretValue(chars);
        } catch (CharacterCodingException exception) {
            throw new ConfigException("Secret file is not valid UTF-8: " + path, exception);
        } catch (IOException exception) {
            throw new ConfigException("Cannot read secret file: " + path, exception);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void requirePrivatePosixPermissions(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.readAttributes(path,
                    PosixFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    .permissions();
            Set<PosixFilePermission> forbidden = EnumSet.of(
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE);
            if (!permissions.contains(PosixFilePermission.OWNER_READ)
                    || permissions.stream().anyMatch(forbidden::contains)) {
                throw new ConfigException("Secret file must be owner-readable and inaccessible "
                        + "to group/other users (mode 0400 or 0600): " + path);
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems still retain the no-symlink and regular-file checks.
        }
    }

    /** Reads via a NOFOLLOW_LINKS channel so a symlink swap cannot redirect the read. */
    private static byte[] readAllBytesWithoutFollowingLinks(Path path) throws IOException {
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(path,
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > MAX_SECRET_BYTES) {
                throw new ConfigException("Secret file exceeds 64 KiB: " + path);
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(
                    Math.toIntExact(Math.min(channel.size(), MAX_SECRET_BYTES)));
            java.nio.ByteBuffer chunk = java.nio.ByteBuffer.allocate(8192);
            while (channel.read(chunk) > 0) {
                chunk.flip();
                bytes.write(chunk.array(), 0, chunk.limit());
                chunk.clear();
                if (bytes.size() > MAX_SECRET_BYTES) {
                    throw new ConfigException("Secret file exceeds 64 KiB: " + path);
                }
            }
            return bytes.toByteArray();
        }
    }

    public static final class SecretValue implements AutoCloseable {
        private final char[] value;
        private boolean closed;

        private SecretValue(char[] value) {
            this.value = value;
        }

        public synchronized char[] copy() {
            if (closed) {
                throw new IllegalStateException("Secret has already been cleared");
            }
            return Arrays.copyOf(value, value.length);
        }

        public synchronized String exposeForDriver() {
            if (closed) {
                throw new IllegalStateException("Secret has already been cleared");
            }
            return new String(value);
        }

        @Override
        public synchronized void close() {
            Arrays.fill(value, '\0');
            closed = true;
        }
    }
}
