package com.follarce.market.server;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Publish tokens for external developers. Only a SHA-256 digest of each token is
 * stored in a private JSON file; the plaintext is printed once when a token is
 * created and never persisted, so a leaked file does not reveal usable tokens.
 */
final class TokenStore {
    private static final Gson JSON = new Gson();
    private static final int TOKEN_BYTES = 32;

    private final Path file;
    private final Map<String, String> tokens;

    TokenStore(Path file) throws IOException {
        this.file = file;
        this.tokens = new LinkedHashMap<>(load());
    }

    private Map<String, String> load() throws IOException {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        if (Files.isSymbolicLink(file) || Files.size(file) > 1024 * 1024) {
            throw new IllegalArgumentException("Token file must be a regular JSON file up to 1 MiB");
        }
        Object decoded = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                Object.class);
        if (!(decoded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Token file must contain a JSON object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : root.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String hash)
                    || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid token entry: " + entry.getKey());
            }
            result.put(name, hash);
        }
        return result;
    }

    synchronized boolean isValid(String token) throws IOException {
        // The token file is the authority and is re-read on every check, so tokens
        // created or removed while the server runs take effect immediately. The file
        // is small and publish requests are rare, so this is cheap.
        if (token == null || token.isBlank()) return false;
        byte[] digest = hex(token).getBytes(StandardCharsets.UTF_8);
        for (String hash : load().values()) {
            if (MessageDigest.isEqual(digest, hash.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    /** Creates a token, stores only its digest, and returns the plaintext once. */
    synchronized String add(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Token name must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}");
        }
        if (tokens.containsKey(name)) {
            throw new IllegalArgumentException("Token already exists: " + name);
        }
        byte[] random = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(random);
        String plaintext = hex(random);
        tokens.put(name, hex(plaintext));
        save();
        return plaintext;
    }

    synchronized boolean remove(String name) throws IOException {
        if (tokens.remove(name) == null) return false;
        save();
        return true;
    }

    synchronized Set<String> names() {
        return new LinkedHashSet<>(tokens.keySet());
    }

    private void save() throws IOException {
        Map<String, String> sorted = new LinkedHashMap<>();
        tokens.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        Path parent = file.getParent() == null ? Path.of(".") : file.getParent();
        Files.createDirectories(parent);
        Path temporary = parent.resolve(file.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(sorted) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        restrict(file);
    }

    /** Owner-only permissions so the digest file stays private on shared hosts. */
    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems keep the default permissions.
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            value.append(Character.forDigit((current >> 4) & 0xF, 16));
            value.append(Character.forDigit(current & 0xF, 16));
        }
        return value.toString();
    }

    private static String hex(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
