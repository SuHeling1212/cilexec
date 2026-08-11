package com.follarce.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSecretLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsOwnerOnlyPosixSecret() throws Exception {
        Path secret = secretWithPermissions(EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));

        try (DockerSecretLoader.SecretValue loaded = DockerSecretLoader.read(secret)) {
            assertArrayEquals("database-password".toCharArray(), loaded.copy());
        }
    }

    @Test
    void rejectsGroupReadablePosixSecret() throws Exception {
        Path secret = secretWithPermissions(EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));

        assertThrows(ConfigException.class, () -> DockerSecretLoader.read(secret));
    }

    private Path secretWithPermissions(EnumSet<PosixFilePermission> permissions) throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(temporaryDirectory,
                PosixFileAttributeView.class) != null);
        Path secret = temporaryDirectory.resolve("database-password");
        Files.writeString(secret, "database-password\n");
        Files.setPosixFilePermissions(secret, permissions);
        return secret;
    }
}
