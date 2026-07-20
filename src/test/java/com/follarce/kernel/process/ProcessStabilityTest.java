package com.follarce.kernel.process;

import com.follarce.bootstrap.init.FileInit;
import com.follarce.extension.builtin.ProcessFunctionProvider;
import com.follarce.kernel.Constants;
import com.follarce.kernel.function.FunctionRegistry;
import com.follarce.kernel.process.ProcessRunner;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class ProcessStabilityTest {
    private static final int PROBE_PID = 300;

    @TempDir Path root;

    @BeforeAll
    static void registerProcessFunctions() {
        FunctionRegistry.registerProvider(new ProcessFunctionProvider());
    }

    @Test
    @Timeout(60)
    void twoHundredFiftySixConcurrentForksRemainUniqueAndConsistent() throws Exception {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        List<ProcessRunner> parents = new ArrayList<>();
        List<Thread> workers = new ArrayList<>();
        Set<Integer> childPids = new HashSet<>();
        CountDownLatch ready = new CountDownLatch(256);
        CountDownLatch start = new CountDownLatch(1);

        try {
            for (int offset = 0; offset < 256; offset++) {
                int pid = 10_000 + offset;
                writeProcess(process(pid, List.of("childPid = fork()"), Map.of()));
                ProcessRunner parent = runner(pid);
                parents.add(parent);
                workers.add(Thread.ofVirtual().start(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        parent.step();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (Thread worker : workers) {
                worker.join(Duration.ofSeconds(30));
                assertFalse(worker.isAlive(), "fork worker exceeded deadline");
            }

            for (ProcessRunner parent : parents) {
                int childPid = numberField(parent.getPid(), "Program.Data.childPid");
                assertTrue(childPids.add(childPid), "duplicate child PID " + childPid);
                Map<String, Object> child = strictProcess(childPid);
                assertEquals(childPid, ((Number) child.get("PID")).intValue());
                assertEquals(parent.getPid(), nestedNumber(child, "Parent", "PID"));
                assertEquals(0, nestedNumber(child, "Program", "Data", "childPid"));
                assertNotNull(field(parent.getPid(), "Child." + childPid));
            }
            assertEquals(256, childPids.size());
            assertEquals(0, countTemporaryProcessFiles());
        } finally {
            for (ProcessRunner parent : parents) ProcessRunner.terminateProcess(parent.getPid());
            for (int childPid : childPids) ProcessRunner.terminateProcess(childPid);
            UserUtil.clearCurrentUser();
        }
    }

    @Test
    @Timeout(15)
    void killedPidCanBeReusedWithoutTemporaryFileLeak() {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        int parentPid = 20_000;
        ProcessRunner parent = null;
        int firstChild = -1;
        int secondChild = -1;
        try {
            writeProcess(process(parentPid,
                    List.of("first = fork()", "waitPID(first)", "second = fork()",
                            "while true", "{", "}"), Map.of()));
            parent = runner(parentPid);
            parent.step();
            firstChild = numberField(parentPid, "Program.Data.first");
            ProcessRunner.terminateProcess(firstChild);

            parent.step();
            parent.step();
            secondChild = numberField(parentPid, "Program.Data.second");
            assertEquals(firstChild, secondChild, "a killed PID remained reserved in JVM memory");
            assertEquals(0, countTemporaryProcessFiles());
        } finally {
            if (parent != null) ProcessRunner.terminateProcess(parentPid);
            if (secondChild > 0) ProcessRunner.terminateProcess(secondChild);
            UserUtil.clearCurrentUser();
        }
    }

    @Test
    @Timeout(90)
    void randomSigkillAndRepeatedJvmRestartsPreserveMonotonicState() throws Exception {
        SplittableRandom random = new SplittableRandom(0xC11E_2026_0717L);
        Process initial = runProbe("steps", 24);
        try {
            assertTrue(initial.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, initial.exitValue());
        } finally {
            if (initial.isAlive()) {
                initial.destroyForcibly();
                initial.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        int previous = readProbeCounter();
        assertTrue(previous > 0);

        for (int round = 0; round < 8; round++) {
            Process process = runProbe("chaos", 0);
            try {
                int baseline = previous;
                awaitCounterAbove(baseline, Duration.ofSeconds(8));
                Thread.sleep(random.nextInt(5, 31));
            } finally {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            }

            recoverAfterCrash();
            int recovered = readProbeCounter();
            assertTrue(recovered >= previous, "counter moved backwards after SIGKILL round " + round);
            previous = recovered;
        }

        int beforeRestarts = previous;
        for (int round = 0; round < 10; round++) {
            Process process = runProbe("steps", 12);
            try {
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
            int recovered = readProbeCounter();
            assertTrue(recovered >= previous, "counter moved backwards after restart round " + round);
            previous = recovered;
        }

        assertTrue(previous > beforeRestarts, "restarted workers made no forward progress");
        assertFalse(Files.exists(realProcessPath(PROBE_PID).resolveSibling(PROBE_PID + ".proc.tmp")));
        try (var files = Files.list(realProcessPath(PROBE_PID).getParent())) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".proc")).count());
        }
    }

    private Process runProbe(String mode, int steps) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
                CrashRecoveryProbe.class.getName(), root.toString(), mode, Integer.toString(steps));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("probe.log").toFile()));
        return builder.start();
    }

    private void recoverAfterCrash() {
        PathUtil.setVfsRoot(root.toFile());
        assertTrue(FileUtil.exists(processPath(PROBE_PID)), "no recoverable process snapshot remained");
        strictProcess(PROBE_PID);
    }

    private void awaitCounterAbove(int baseline, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (readProbeCounterDirect() > baseline) return;
            Thread.sleep(10);
        }
        fail("probe counter did not advance beyond " + baseline);
    }

    private int readProbeCounterDirect() {
        try {
            String stored = Files.readString(realProcessPath(PROBE_PID));
            String body = PathUtil.extractBodyContent(stored);
            Map<String, Object> process = JsonUtil.parseToMapStrict(body);
            return nestedNumber(process, "Program", "Data", "i");
        } catch (Exception e) {
            return -1;
        }
    }

    private int readProbeCounter() {
        PathUtil.setVfsRoot(root.toFile());
        return nestedNumber(strictProcess(PROBE_PID), "Program", "Data", "i");
    }

    private ProcessRunner runner(int pid) {
        ProcessRunner runner = new ProcessRunner(pid, strictProcess(pid));
        runner.init();
        return runner;
    }

    private void writeProcess(Map<String, Object> process) {
        int pid = ((Number) process.get("PID")).intValue();
        JsonUtil.writeFile(processPath(pid), JsonUtil.toJson(process));
    }

    private Map<String, Object> process(int pid, List<String> lines, Map<String, Object> data) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", "stress-" + pid);
        process.put("Owner", "local");
        process.put("PID", pid);
        process.put("Path", "/system/app/stress-" + pid + ".fcl");
        process.put("Status", true);
        process.put("ProcessState", ProcessState.NEW.name());
        process.put("Parent", new LinkedHashMap<>());
        process.put("Child", new LinkedHashMap<>());
        process.put("ExitedChildren", new LinkedHashMap<>());

        Map<String, Object> code = new LinkedHashMap<>();
        code.put("Code", new ArrayList<>(lines));
        code.put("runningCodeLine", 0);
        code.put("BlockStack", new ArrayList<>());
        Map<String, Object> program = new LinkedHashMap<>();
        program.put("Data", new LinkedHashMap<>(data));
        program.put("Code", code);
        process.put("Program", program);
        return process;
    }

    private Map<String, Object> strictProcess(int pid) {
        return JsonUtil.parseToMapStrict(FileUtil.read(processPath(pid)));
    }

    private Object field(int pid, String path) {
        return JsonUtil.getField(processPath(pid), path);
    }

    private int numberField(int pid, String path) {
        Object value = field(pid, path);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    @SuppressWarnings("unchecked")
    private static int nestedNumber(Map<String, Object> root, String... path) {
        Object current = root;
        for (String part : path) {
            if (!(current instanceof Map)) return -1;
            current = ((Map<String, Object>) current).get(part);
        }
        return current instanceof Number ? ((Number) current).intValue() : -1;
    }

    private long countTemporaryProcessFiles() {
        File directory = realProcessPath(2).getParent().toFile();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".proc.tmp"));
        return files == null ? 0 : files.length;
    }

    private String processPath(int pid) {
        return Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
    }

    private Path realProcessPath(int pid) {
        return root.resolve("system/process/" + pid + ".proc");
    }
}
