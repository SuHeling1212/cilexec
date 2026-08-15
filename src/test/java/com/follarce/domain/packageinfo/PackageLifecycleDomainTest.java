package com.follarce.domain.packageinfo;

import com.follarce.domain.vfs.ObjectHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageLifecycleDomainTest {
    private static final ObjectHash HASH = new ObjectHash("a".repeat(64));
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void installationRequiresACanonicalSourceAndNonNegativeDepths() {
        PackageInstallation.Member member = new PackageInstallation.Member(
                new PackageRelease.Coordinate("demo", "hello", "1.0.0"), HASH, HASH, 0, false);
        assertEquals(0, member.dependencyDepth());

        assertThrows(IllegalArgumentException.class, () -> new PackageInstallation(
                UUID.randomUUID().toString(), OWNER,
                new PackageRelease.Coordinate("demo", "hello", "1.0.0"), HASH, "SOURCE", NOW,
                List.of(member)));
        assertThrows(IllegalArgumentException.class, () -> new PackageInstallation.Member(
                new PackageRelease.Coordinate("demo", "hello", "1.0.0"), HASH, HASH, -1, false));
    }

    @Test
    void dataUsageValidatesQuotasAndCounts() {
        PackageDataUsage usage = new PackageDataUsage(UUID.randomUUID().toString(), OWNER, HASH,
                HASH, 12, 268435456, 2, NOW);
        assertEquals(268435456L, usage.quota());

        assertThrows(IllegalArgumentException.class, () -> new PackageDataUsage(
                UUID.randomUUID().toString(), OWNER, HASH, HASH, -1, 0, 0, NOW));
        assertThrows(IllegalArgumentException.class, () -> new PackageDataUsage(
                UUID.randomUUID().toString(), OWNER, HASH, HASH, 0, -1, 0, NOW));
    }

    @Test
    void dataEntriesDistinguishFilesFromDirectories() {
        PackageDataEntry file = new PackageDataEntry("config.json", "FILE",
                Optional.of(HASH), 3, 2, Optional.of(NOW));
        PackageDataEntry directory = new PackageDataEntry("cache", "DIRECTORY",
                Optional.empty(), 0, 0, Optional.empty());
        assertTrue(!file.isDirectory());
        assertTrue(directory.isDirectory());
        assertThrows(IllegalArgumentException.class, () -> new PackageDataEntry(
                "x", "SOCKET", Optional.empty(), 0, 0, Optional.empty()));
    }

    @Test
    void uninstallSummaryRejectsNegativeCounts() {
        PackageUninstallResult result = new PackageUninstallResult(true, 1, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(result.removed());
        assertEquals(1, result.packagesRemoved());
        assertThrows(IllegalArgumentException.class, () -> new PackageUninstallResult(
                true, -1, 0, 0, 0, 0, 0, 0, 0));
    }
}
