package com.follarce.extension.terminal;

import com.follarce.bootstrap.init.FileInit;
import com.follarce.extension.pack.PackageHookRunner;
import com.follarce.extension.pack.PackageManager;
import com.follarce.extension.pack.PackageStore;
import com.follarce.kernel.Constants;
import com.follarce.kernel.process.ProcessLauncher;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleShellIntegrationTest {
    @TempDir Path root;

    private SystemControlService control;

    @BeforeEach
    void setUp() {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser(Constants.DEFAULT_USER_LOCAL);
        control = new SystemControlService(new ProcessLauncher(),
                new PackageManager(new PackageStore(), new PackageHookRunner()));
    }

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void shellItselfNeverCreatesAProcessSnapshot() {
        StringWriter output = new StringWriter();
        AtomicInteger shutdowns = new AtomicInteger();
        ConsoleShell shell = new ConsoleShell(
                new StringReader("help\npackage list\nexit\n"), output, control,
                shutdowns::incrementAndGet);

        shell.run();

        assertTrue(PathUtil.scanProcessFileNames().isEmpty());
        assertEquals(1, shutdowns.get());
        assertTrue(output.toString().contains("Cilexec host shell"));
        assertTrue(output.toString().contains("[]"));
    }

    @Test
    void executesRealProcessCommandsEndToEnd() {
        FileUtil.createFile(Constants.SYSTEM_APP_PATH, "worker.fcl");
        FileUtil.write(Constants.SYSTEM_APP_PATH + "worker.fcl", "value = 1");
        String commands = String.join("\n",
                "run /system/app/worker.fcl --name \"worker one\" --priority high",
                "ps",
                "inspect 2",
                "pause 2",
                "continue 2",
                "kill 2",
                "exit",
                "");
        StringWriter output = new StringWriter();
        ConsoleShell shell = new ConsoleShell(
                new StringReader(commands), output, control, () -> {});

        shell.run();

        String transcript = output.toString();
        assertTrue(transcript.contains("Started FCL process PID 2."), transcript);
        assertTrue(transcript.contains("worker one"), transcript);
        assertTrue(transcript.contains("\"ProcessGeneration\""), transcript);
        assertTrue(transcript.contains("Pause requested for PID 2."), transcript);
        assertTrue(transcript.contains("Continue requested for PID 2."), transcript);
        assertTrue(transcript.contains("Kill requested for PID 2."), transcript);
        assertFalse(FileUtil.exists(Constants.SYSTEM_PROCESS_PATH + "2.proc"));
        assertTrue(PathUtil.scanProcessFileNames().isEmpty(),
                "Only the launched FCL process may have a snapshot");
    }
}
