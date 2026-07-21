package com.follarce.extension.terminal;

import com.follarce.bootstrap.BuiltinProviderIndex;
import com.follarce.bootstrap.init.FileInit;
import com.follarce.bootstrap.init.PackageInit;
import com.follarce.bootstrap.init.ProcessInit;
import com.follarce.kernel.Constants;
import com.follarce.kernel.process.ProcessIdentity;
import com.follarce.kernel.process.ProcessRunner;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.process.RecoveryManager;
import com.follarce.kernel.process.Scheduler;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FclTerminalIntegrationTest {
    @TempDir java.nio.file.Path root;

    private Scheduler scheduler;
    private ProcessRunner initRunner;

    private String processPath() {
        return Constants.SYSTEM_PROCESS_PATH + Constants.TERMINAL_DEFAULT_PID + ".proc";
    }

    @BeforeEach
    void setUp() {
        File rootDir = root.toFile();
        FileInit.init(rootDir);
        UserUtil.setCurrentUser(Constants.DEFAULT_USER_LOCAL);
        BuiltinProviderIndex.install();
        PackageInit.init();

        String initFclPath = Constants.SYSTEM_CONFIG_PATH + Constants.INIT_FCL;
        FileUtil.write(initFclPath, "x = 0");

        ProcessInit.init();
        RecoveryManager.recoverAll();

        scheduler = new Scheduler();

        String initPath = Constants.SYSTEM_PROCESS_PATH + Constants.PID_INIT + ".proc";
        if (FileUtil.exists(initPath)) {
            String content = FileUtil.read(initPath);
            Map<String, Object> data = JsonUtil.parseToMapStrict(content);
            ProcessIdentity.ensureDefaults(data);
            initRunner = new ProcessRunner(Constants.PID_INIT, data);
            initRunner.init();
            scheduler.addProcess(initRunner);
        }

        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.shutdownScheduler();
        UserUtil.clearCurrentUser();
    }

    private FclTerminal runTerminal(String input, StringWriter output) {
        FclTerminal t = new FclTerminal(
                new StringReader(input), new PrintWriter(output, true),
                scheduler, () -> {});
        Thread termThread = new Thread(t::run);
        termThread.start();
        try { termThread.join(30000); } catch (InterruptedException e) {}
        if (termThread.isAlive()) termThread.interrupt();
        return t;
    }

    @Test
    void createsTerminalProcessAndAcceptsFclInput() {
        StringWriter out = new StringWriter();
        runTerminal("/exit\n", out);
        assertTrue(FileUtil.exists(processPath()), "Terminal process file must exist");
        assertTrue(out.toString().contains("FCL Terminal"));
    }

    @Test
    void basicVariableAssignmentWorks() {
        StringWriter out = new StringWriter();
        runTerminal("a = 1\n/exit\n", out);

        assertTrue(FileUtil.exists(processPath()));
        Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(processPath()));
        @SuppressWarnings("unchecked") Map<String, Object> program = (Map<String, Object>) data.get("Program");
        @SuppressWarnings("unchecked") Map<String, Object> vars = (Map<String, Object>) program.get("Data");
        assertTrue(vars.containsKey("a"), "Variable a should be defined: " + vars);
        assertEquals(1L, ((Number) vars.get("a")).longValue());
    }

    @Test
    void expressionResultIsAutoPrinted() {
        StringWriter out = new StringWriter();
        runTerminal("1 + 1\n/exit\n", out);
        assertTrue(out.toString().contains("2"), "1+1 result should appear: " + out);
    }

    @Test
    void sharedContextAcrossMultipleInputs() {
        StringWriter out = new StringWriter();
        runTerminal("a = 10\nb = 20\n/exit\n", out);
        Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(processPath()));
        @SuppressWarnings("unchecked") Map<String, Object> vars = (Map<String, Object>) ((Map<String, Object>) data.get("Program")).get("Data");
        assertTrue(vars.containsKey("a"), "a should be defined: " + vars);
        assertTrue(vars.containsKey("b"), "b should be defined: " + vars);
    }

    @Test
    void helpAndPsCommands() {
        StringWriter out = new StringWriter();
        runTerminal("/help\n/ps\n/exit\n", out);
        assertTrue(out.toString().contains("/help"));
        assertTrue(out.toString().contains("/ps"));
        assertTrue(out.toString().contains("process1") || out.toString().contains("INIT")
                || out.toString().contains("PID-1") || out.toString().contains("PID- " + Constants.PID_INIT),
                out.toString());
    }

    @Test
    void cdCommand() {
        StringWriter out = new StringWriter();
        runTerminal("/cd\n/exit\n", out);
        assertTrue(out.toString().contains("/") || out.toString().contains("Working"),
                out.toString());
    }

    @Test
    void cdErrorOnNonExistentPath() {
        StringWriter out = new StringWriter();
        runTerminal("/cd /nonexistent\n/exit\n", out);
        assertTrue(out.toString().contains("TerminalError"));
    }

    @Test
    void newCommandResetsContext() {
        StringWriter out = new StringWriter();
        runTerminal("a = 42\n/new\na\n/exit\n", out);
        String s = out.toString();
        assertTrue(s.contains("undefined") || s.contains("error") || s.contains("Error"),
                "a after /new should fail: " + s);
    }

    @Test
    void leftPreservesProcessFile() {
        StringWriter out = new StringWriter();
        runTerminal("/left\n", out);
        assertTrue(FileUtil.exists(processPath()), "File should exist after /left");
    }

    @Test
    void fclCodeWithoutSlashWorks() {
        StringWriter out = new StringWriter();
        runTerminal("a = 10\na\n/exit\n", out);
        assertTrue(out.toString().contains("10"));
    }
}
