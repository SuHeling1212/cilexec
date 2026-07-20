package com.follarce.shell;

import com.follarce.bootstrap.init.FileInit;
import com.follarce.extension.pack.PackageHookRunner;
import com.follarce.extension.pack.PackageManager;
import com.follarce.extension.pack.PackageStore;
import com.follarce.kernel.Constants;
import com.follarce.kernel.process.ProcessLauncher;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.vfs.FileUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemControlServiceTest {
    @TempDir Path root;

    private SystemControlService control;

    @BeforeEach
    void setUp() {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser(Constants.DEFAULT_USER_LOCAL);
        control = new SystemControlService(new ProcessLauncher(),
                new PackageManager(new PackageStore(), new PackageHookRunner()));
        FileUtil.createFile(Constants.SYSTEM_APP_PATH, "worker.fcl");
        FileUtil.write(Constants.SYSTEM_APP_PATH + "worker.fcl", "value = 1");
    }

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void startsAnIndependentProcessFromDiskAndControlsItThroughDurableMessages() {
        int pid = control.startProcess("/system/app/worker.fcl", "local", "worker one",
                Constants.PRIORITY_HIGH);

        Map<String, Object> process = control.inspectProcess(pid);
        assertEquals("worker one", process.get("Name"));
        assertEquals("local", process.get("EffectiveUser"));
        assertEquals(ProcessState.READY.name(), process.get("ProcessState"));
        assertEquals(Constants.PRIORITY_HIGH, ((Number) process.get("Priority")).intValue());
        assertTrue(((Map<?, ?>) process.get("Parent")).isEmpty());
        assertEquals("/system/app/worker.fcl",
                ((Map<?, ?>) ((Map<?, ?>) process.get("Program")).get("Data")).get("__current_script"));

        control.pauseProcess(pid);
        assertEquals(ProcessState.PAUSED.name(), control.inspectProcess(pid).get("ProcessState"));
        control.continueProcess(pid);
        assertEquals(ProcessState.READY.name(), control.inspectProcess(pid).get("ProcessState"));
        control.killProcess(pid);
        assertFalse(FileUtil.exists(Constants.SYSTEM_PROCESS_PATH + pid + ".proc"));
    }

    @Test
    void concurrentStartsReserveDifferentPids() throws Exception {
        List<Callable<Integer>> starts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int ordinal = i;
            starts.add(() -> control.startProcess("/system/app/worker.fcl", "local",
                    "worker-" + ordinal, Constants.PRIORITY_NORMAL));
        }

        Set<Integer> pids = new HashSet<>();
        try (var executor = Executors.newFixedThreadPool(6)) {
            for (var result : executor.invokeAll(starts)) pids.add(result.get());
        }

        assertEquals(12, pids.size());
        assertEquals(12, control.listProcesses().size());
    }

    @Test
    void protectsInitAndRejectsUnknownUsers() {
        assertThrows(IllegalArgumentException.class, () -> control.pauseProcess(Constants.PID_INIT));
        assertThrows(IllegalArgumentException.class,
                () -> control.startProcess("/system/app/worker.fcl", "missing", null,
                        Constants.PRIORITY_NORMAL));
    }

    @Test
    void rejectsHostPathsAndSymbolicLinksThatEscapeTheVfsRoot() throws Exception {
        Path external = Files.createTempFile(root.getParent(), "outside-cilexec-", ".fcl");
        Files.writeString(external, "escaped = true");
        Path link = root.resolve("system/app/outside.fcl");
        try {
            Files.createSymbolicLink(link, external);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            Files.deleteIfExists(external);
            return;
        }

        try {
            assertThrows(SecurityException.class,
                    () -> control.startProcess("/system/app/outside.fcl", "local", null,
                            Constants.PRIORITY_NORMAL));
            assertThrows(IllegalArgumentException.class,
                    () -> control.startProcess(external.toString(), "local", null,
                            Constants.PRIORITY_NORMAL));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(external);
        }
    }
}
