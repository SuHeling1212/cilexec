package com.follarce.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Reads a Docker/external secret without retaining it as application configuration text. */
public final class DockerSecretLoader {
    private DockerSecretLoader() {
    }

    public static SecretValue read(Path path) {
        if (path == null) {
            throw new ConfigException("Secret path is required");
        }
        try {
            if (!Files.isRegularFile(path)) {
                throw new ConfigException("Secret file does not exist: " + path);
            }
            byte[] bytes = Files.readAllBytes(path);
            int length = bytes.length;
            while (length > 0 && (bytes[length - 1] == '\n' || bytes[length - 1] == '\r')) {
                length--;
            }
            if (length == 0) {
                Arrays.fill(bytes, (byte) 0);
                throw new ConfigException("Secret file is empty: " + path);
            }
            char[] chars = new String(bytes, 0, length, StandardCharsets.UTF_8).toCharArray();
            Arrays.fill(bytes, (byte) 0);
            return new SecretValue(chars);
        } catch (IOException exception) {
            throw new ConfigException("Cannot read secret file: " + path, exception);
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
