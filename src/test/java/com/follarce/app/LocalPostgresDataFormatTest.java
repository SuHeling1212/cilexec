package com.follarce.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPostgresDataFormatTest {
    @TempDir
    Path directory;

    @Test
    void rejectsLegacyDataWithoutModifyingIt() throws Exception {
        Path postgres = Files.createDirectory(directory.resolve("postgres"));
        Path version = postgres.resolve("PG_VERSION");
        Files.writeString(version, "16");
        Map<String, byte[]> before = snapshot(directory);

        LocalPostgres.DataFormatMismatchException refusal = assertThrows(
                LocalPostgres.DataFormatMismatchException.class,
                () -> LocalPostgres.requireDataFormat(directory));

        assertEquals(LocalPostgres.OLD_DATA_MESSAGE, refusal.getMessage());
        assertTrue(refusal.getMessage().contains("not CilExec 0.0.5"));
        assertTrue(refusal.getMessage().contains("neither migrated nor deleted"));
        assertTrue(refusal.getMessage().contains("new JAR directory"));
        assertEquals("16", Files.readString(version));
        assertFalse(Files.exists(directory.resolve("format.version")));
        Map<String, byte[]> after = snapshot(directory);
        assertEquals(before.keySet(), after.keySet());
        for (String path : before.keySet()) {
            assertArrayEquals(before.get(path), after.get(path), path);
        }
    }

    @Test
    void rejectsMismatchedFormatVersionWithoutModifyingIt() throws Exception {
        Path postgres = Files.createDirectory(directory.resolve("postgres"));
        Files.writeString(postgres.resolve("PG_VERSION"), "17");
        Path format = directory.resolve("format.version");
        Files.writeString(format, "0.0.4\n");
        Map<String, byte[]> before = snapshot(directory);

        assertThrows(LocalPostgres.DataFormatMismatchException.class,
                () -> LocalPostgres.requireDataFormat(directory));

        assertEquals("0.0.4\n", Files.readString(format));
        Map<String, byte[]> after = snapshot(directory);
        assertEquals(before.keySet(), after.keySet());
        for (String path : before.keySet()) {
            assertArrayEquals(before.get(path), after.get(path), path);
        }
    }

    @Test
    void rejectsAStrayOlderFormatMarkerBeforeAnyDatabaseStarts() throws Exception {
        Path format = directory.resolve("format.version");
        Files.writeString(format, "0.0.4\n");
        Map<String, byte[]> before = snapshot(directory);

        assertThrows(LocalPostgres.DataFormatMismatchException.class,
                () -> LocalPostgres.requireDataFormat(directory));

        assertEquals("0.0.4\n", Files.readString(format));
        assertEquals(before.keySet(), snapshot(directory).keySet());
    }

    @Test
    void refusesAStillRunningPostmasterBeforeStartingASecondServer() throws Exception {
        Path postgres = Files.createDirectory(directory.resolve("postgres"));
        Files.writeString(postgres.resolve("PG_VERSION"), "17");
        Files.writeString(directory.resolve("format.version"), "0.0.5\n");
        long livePid = ProcessHandle.current().pid();
        Files.writeString(postgres.resolve("postmaster.pid"),
                livePid + "\n" + postgres.toAbsolutePath() + "\n0\n51905\n");

        LocalPostgres.RunningServerException refusal = assertThrows(
                LocalPostgres.RunningServerException.class,
                () -> LocalPostgres.requireNoRunningServer(directory));

        assertTrue(refusal.getMessage().contains(Long.toString(livePid)),
                refusal.getMessage());
        assertTrue(refusal.getMessage().contains("pg_ctl"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("did not shut down cleanly"),
                refusal.getMessage());
    }

    @Test
    void allowsAStalePostmasterPidToRecover() throws Exception {
        Path postgres = Files.createDirectory(directory.resolve("postgres"));
        Files.writeString(postgres.resolve("PG_VERSION"), "17");
        // No process can own this PID; PostgreSQL removes the stale file when it starts.
        Files.writeString(postgres.resolve("postmaster.pid"),
                "99999999\n" + postgres.toAbsolutePath() + "\n0\n51905\n");

        assertDoesNotThrow(() -> LocalPostgres.requireNoRunningServer(directory));
    }

    @Test
    void allowsFreshDataWithoutCreatingAnything() throws IOException {
        assertFalse(LocalPostgres.requireDataFormat(directory));
        assertTrue(snapshot(directory).isEmpty());
    }

    @Test
    void allowsCurrentDataWithoutModifyingIt() throws Exception {
        Path postgres = Files.createDirectory(directory.resolve("postgres"));
        Files.writeString(postgres.resolve("PG_VERSION"), "17");
        Files.writeString(directory.resolve("format.version"), "0.0.5\n");
        Map<String, byte[]> before = snapshot(directory);

        assertTrue(LocalPostgres.requireDataFormat(directory));

        Map<String, byte[]> after = snapshot(directory);
        assertEquals(before.keySet(), after.keySet());
        for (String path : before.keySet()) {
            assertArrayEquals(before.get(path), after.get(path), path);
        }
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        return files;
    }
}
