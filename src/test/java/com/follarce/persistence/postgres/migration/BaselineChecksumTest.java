package com.follarce.persistence.postgres.migration;

import db.migration.V001__CilexecBaseline;
import db.migration.V004;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaselineChecksumTest {
    @Test
    void checksumTracksTheFrozenBaselineModules() {
        assertEquals(1_814_221_862, new V001__CilexecBaseline().getChecksum());
    }

    @Test
    void publishedChecksumlessJavaMigrationsRemainSourceImmutable() throws Exception {
        assertEquals("84c51d1261502e975fca4010aeef3298c232bf41d8d7d8292b787bdec8c50232",
                sourceSha256("src/main/java/db/migration/V002.java"));
        assertEquals("cab669932cf8ffeb2c27d78c724c9c5cb56dc44ee0e1ed50c63f9bce7d3c281f",
                sourceSha256("src/main/java/db/migration/V003.java"));
    }

    @Test
    void currentJavaMigrationChecksumTracksItsImmutableSqlResource() {
        assertEquals(409_911_152, new V004().getChecksum());
    }

    private static String sourceSha256(String value) throws Exception {
        String normalized = Files.readString(Path.of(value), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8)));
    }
}
