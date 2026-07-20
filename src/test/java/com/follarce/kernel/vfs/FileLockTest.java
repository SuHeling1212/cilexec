package com.follarce.kernel.vfs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.follarce.kernel.Constants;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class FileLockTest {
    private static final String DIRECTORY = "/locks";
    private static final String FILE = DIRECTORY + "/data.txt";

    @TempDir Path root;

    @BeforeEach
    void createFileSystem() throws Exception {
        PathUtil.setVfsRoot(root.toFile());
        Files.createDirectories(root.resolve("locks"));
        FileUtil.createDirectoryMetaData(DIRECTORY);
        FileUtil.createFile(DIRECTORY, "data.txt");
        FileUtil.write(FILE, "initial");
    }

    @Test
    void concurrentAcquisitionHasExactlyOneWinner() throws Exception {
        int workerCount = 24;
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger winningPid = new AtomicInteger();
        AtomicReference<FileUtil.LockHandle> winningHandle = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();

        for (int index = 0; index < workerCount; index++) {
            int pid = 1_000 + index;
            workers.add(Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    start.await();
                    FileUtil.LockHandle handle = FileUtil.acquireLock(FILE, pid, 1L, 30_000L);
                    winners.incrementAndGet();
                    winningPid.set(pid);
                    winningHandle.set(handle);
                } catch (RuntimeException expectedForLosers) {
                    // An active owner must reject all losing contenders.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Thread worker : workers) worker.join();

        assertEquals(1, winners.get());
        FileUtil.LockHandle handle = winningHandle.get();
        assertNotNull(handle);
        Map<String, Object> locked = lockRecord(FileUtil.readFileMetaData(FILE));
        assertEquals(winningPid.get(), number(locked, "lockedBy"));
        assertEquals(handle.fencingToken(), longNumber(locked, "fencingToken"));
        assertEquals(1L, longNumber(locked, "lockedByGeneration"));
    }

    @Test
    void expiredLeaseCanBeTakenOverAndAdvancesToken() throws Exception {
        FileUtil.LockHandle first = FileUtil.acquireLock(FILE, 10, 100L, 25L);
        awaitExpiration(first);

        assertDoesNotThrow(() -> FileUtil.write(FILE, "expired leases do not block"));
        FileUtil.LockHandle second = FileUtil.acquireLock(FILE, 11, 101L, 5_000L);

        assertTrue(second.fencingToken() > first.fencingToken());
        Map<String, Object> locked = lockRecord(FileUtil.readFileMetaData(FILE));
        assertEquals(second.fencingToken(), longNumber(locked, "fencingToken"));
        assertEquals(second.leaseUntilEpochMs(), longNumber(locked, "leaseUntilEpochMs"));
    }

    @Test
    void reusedPidCannotActAsAFormerGeneration() throws Exception {
        int reusedPid = 20;
        FileUtil.LockHandle former = FileUtil.acquireLock(FILE, reusedPid, 200L, 25L);
        awaitExpiration(former);
        FileUtil.LockHandle current = FileUtil.acquireLock(FILE, reusedPid, 201L, 5_000L);

        assertThrows(RuntimeException.class,
                () -> FileUtil.renewLock(FILE, reusedPid, 200L,
                        current.fencingToken(), 5_000L));
        assertThrows(RuntimeException.class,
                () -> FileUtil.unlock(FILE, reusedPid, 200L,
                        current.fencingToken(), "user"));
        assertThrows(RuntimeException.class,
                () -> FileUtil.unlock(FILE, reusedPid, "user"));

        FileUtil.LockHandle renewed = FileUtil.renewLock(
                FILE, reusedPid, 201L, current.fencingToken(), 5_000L);
        assertEquals(current.fencingToken(), renewed.fencingToken());
    }

    @Test
    void staleTokenCannotWriteOrAppendAfterReacquisition() {
        FileUtil.LockHandle stale = FileUtil.acquireLock(FILE, 30, 300L, 5_000L);
        FileUtil.unlock(FILE, 30, 300L, stale.fencingToken(), "user");
        FileUtil.LockHandle current = FileUtil.acquireLock(FILE, 31, 301L, 5_000L);

        assertTrue(current.fencingToken() > stale.fencingToken());
        assertThrows(RuntimeException.class,
                () -> FileUtil.write(FILE, "stale", 30, 300L, stale.fencingToken()));
        assertThrows(RuntimeException.class,
                () -> FileUtil.append(FILE, "stale", 30, 300L, stale.fencingToken()));
        assertThrows(RuntimeException.class, () -> FileUtil.write(FILE, "unfenced"));
        assertThrows(RuntimeException.class, () -> FileUtil.append(FILE, "unfenced"));

        FileUtil.write(FILE, "owner", 31, 301L, current.fencingToken());
        FileUtil.append(FILE, "-append", 31, 301L, current.fencingToken());
        assertEquals("owner-append", FileUtil.read(FILE));
    }

    @Test
    void directoryLockIsStoredInMetaFile() {
        FileUtil.createDirectory(DIRECTORY, "nested");
        String directory = DIRECTORY + "/nested";
        FileUtil.LockHandle handle = FileUtil.acquireLock(directory, 40, 400L, 5_000L);

        assertTrue(Files.isRegularFile(root.resolve("locks/nested/" + Constants.META_DIR_FILE)));
        Map<String, Object> locked = lockRecord(FileUtil.readDirectoryMetaData(directory));
        assertEquals(2, number(locked, "version"));
        assertEquals(40, number(locked, "lockedBy"));
        assertEquals(400L, longNumber(locked, "lockedByGeneration"));
        assertEquals(handle.fencingToken(), longNumber(locked, "fencingToken"));
        assertThrows(RuntimeException.class, () -> FileUtil.checkLock(directory));

        FileUtil.unlock(directory, 40, 400L, handle.fencingToken(), "user");
        assertDoesNotThrow(() -> FileUtil.checkLock(directory));
        Map<String, Object> released = lockRecord(FileUtil.readDirectoryMetaData(directory));
        assertFalse((Boolean) released.get("isLocked"));
        assertEquals(handle.fencingToken(), longNumber(released, "fencingToken"));
    }

    @Test
    void legacyLockAndUnlockSignaturesRemainUsable() {
        FileUtil.lock(FILE, 50);
        assertThrows(RuntimeException.class, () -> FileUtil.checkLock(FILE));
        assertThrows(RuntimeException.class, () -> FileUtil.write(FILE, "blocked"));

        FileUtil.unlock(FILE, 50, "user");
        assertDoesNotThrow(() -> FileUtil.write(FILE, "legacy released"));
    }

    @Test
    void processAtomicWriteRemainsAvailableUnderPersistentLock() {
        String processPath = DIRECTORY + "/42.proc";
        FileUtil.createFile(DIRECTORY, "42.proc");
        FileUtil.writeAtomic(processPath, "{\"PID\":42,\"Program\":{\"value\":1}}");
        FileUtil.LockHandle handle = FileUtil.acquireLock(processPath, 42, 420L, 5_000L);

        FileUtil.writeAtomic(processPath, "{\"PID\":42,\"Program\":{\"value\":2}}");

        Map<String, Object> process = JsonUtil.parseToMapStrict(FileUtil.read(processPath));
        @SuppressWarnings("unchecked")
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        assertEquals(2, ((Number) program.get("value")).intValue());
        assertEquals(handle.fencingToken(),
                longNumber(lockRecord(FileUtil.readFileMetaData(processPath)), "fencingToken"));
    }

    private static void awaitExpiration(FileUtil.LockHandle handle) throws InterruptedException {
        long delay = handle.leaseUntilEpochMs() - System.currentTimeMillis() + 2L;
        if (delay > 0) Thread.sleep(delay);
        while (System.currentTimeMillis() <= handle.leaseUntilEpochMs()) Thread.sleep(1L);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lockRecord(Map<String, Object> metadata) {
        return (Map<String, Object>) assertInstanceOf(Map.class, metadata.get("locked"));
    }

    private static int number(Map<String, Object> map, String field) {
        return ((Number) map.get(field)).intValue();
    }

    private static long longNumber(Map<String, Object> map, String field) {
        return ((Number) map.get(field)).longValue();
    }
}
