package com.follarce.kernel.process;

import com.follarce.bootstrap.init.FileInit;
import com.follarce.extension.builtin.ProcessFunctionProvider;
import com.follarce.kernel.Constants;
import com.follarce.kernel.function.FunctionRegistry;
import com.follarce.kernel.process.ExitReason;
import com.follarce.kernel.process.ProcessRunner;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.process.Scheduler;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ProcessLifecycleEdgeTest {
    @TempDir Path root;

    @BeforeAll
    static void registerProcessFunctions() {
        FunctionRegistry.registerProvider(new ProcessFunctionProvider());
    }

    @BeforeEach
    void initializeVfs() {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        writeProcess(process(Constants.PID_INIT,
                List.of("while true", "{", "}"), Map.of(), Map.of(), Map.of()));
    }

    @AfterEach
    void stopProcesses() {
        Path processDirectory = Path.of(PathUtil.toRealPath(Constants.SYSTEM_PROCESS_PATH));
        if (Files.isDirectory(processDirectory)) {
            try (var files = Files.list(processDirectory)) {
                files.filter(path -> path.getFileName().toString().matches("\\d+\\.proc"))
                        .map(path -> path.getFileName().toString().replace(".proc", ""))
                        .mapToInt(Integer::parseInt)
                        .filter(pid -> pid != Constants.PID_INIT)
                        .forEach(ProcessRunner::terminateProcess);
            } catch (Exception ignored) {
            }
        }
        UserUtil.clearCurrentUser();
    }

    @Test
    void naturalParentExitReparentsRunningChildToInit() {
        int parentPid = 200;
        int childPid = 201;
        writeProcess(process(childPid, List.of("observedParent = getPPID()"), Map.of(),
                Map.of("PID", parentPid), Map.of()));
        ProcessRunner child = runner(childPid);
        ProcessRunner parent = createRunner(parentPid, List.of(), Map.of(),
                Map.of("PID", Constants.PID_INIT), Map.of(String.valueOf(childPid), childInfo(childPid)));

        assertEquals(ProcessRunner.StepResult.TERMINATED, parent.step());
        assertEquals(Constants.PID_INIT, numberField(childPid, "Parent.PID"));
        assertNotNull(field(Constants.PID_INIT, "Child." + childPid));

        child.step();
        assertEquals(Constants.PID_INIT, numberField(childPid, "Program.Data.observedParent"));
    }

    @Test
    void nestedForkReparentsOnlyDirectOrphans() {
        int parentPid = 210;
        ProcessRunner parent = createRunner(parentPid,
                List.of("child = fork()", "grandchild = fork()", "while true", "{", "}"),
                Map.of(), Map.of("PID", Constants.PID_INIT), Map.of());
        parent.step();
        int childPid = numberField(parentPid, "Program.Data.child");

        ProcessRunner child = runner(childPid);
        child.step();
        int grandchildPid = numberField(childPid, "Program.Data.grandchild");

        ProcessRunner.terminateProcess(parentPid);
        assertEquals(Constants.PID_INIT, numberField(childPid, "Parent.PID"));
        assertEquals(childPid, numberField(grandchildPid, "Parent.PID"));

        ProcessRunner.terminateProcess(childPid);
        assertEquals(Constants.PID_INIT, numberField(grandchildPid, "Parent.PID"));
        assertNotNull(field(Constants.PID_INIT, "Child." + grandchildPid));
    }

    @Test
    void simultaneousChildExitsProduceDistinctDurableEvents() throws Exception {
        int parentPid = 220;
        int firstChild = 221;
        int secondChild = 222;
        writeProcess(process(firstChild, List.of(), Map.of(), Map.of("PID", parentPid), Map.of()));
        writeProcess(process(secondChild, List.of(), Map.of(), Map.of("PID", parentPid), Map.of()));
        ProcessRunner parent = createRunner(parentPid,
                List.of("wait()", "first = true", "wait()", "second = true"), Map.of(), Map.of(),
                Map.of(String.valueOf(firstChild), childInfo(firstChild),
                        String.valueOf(secondChild), childInfo(secondChild)));
        Thread parentThread = Thread.ofVirtual().start(parent::virtualThreadRun);
        await(() -> parent.getState() == ProcessState.BLOCKED, "parent did not enter wait");

        CountDownLatch start = new CountDownLatch(1);
        Thread first = Thread.ofVirtual().start(() -> terminateAfter(start, firstChild));
        Thread second = Thread.ofVirtual().start(() -> terminateAfter(start, secondChild));
        start.countDown();
        first.join();
        second.join();

        await(() -> parent.getState().isTerminal(), "parent lost a child exit event");
        assertEquals(Boolean.TRUE, field(parentPid, "Program.Data.first"));
        assertEquals(Boolean.TRUE, field(parentPid, "Program.Data.second"));
        assertTrue(mapField(parentPid, "ExitedChildren").isEmpty());
        parentThread.join(1_000);
    }

    @Test
    void waitPidWakesWhenTargetIsKilledWhileSiblingRemainsAlive() throws Exception {
        int parentPid = 230;
        int targetPid = 231;
        int siblingPid = 232;
        writeProcess(process(targetPid, List.of(), Map.of(), Map.of("PID", parentPid), Map.of()));
        writeProcess(process(siblingPid, List.of(), Map.of(), Map.of("PID", parentPid), Map.of()));
        ProcessRunner parent = createRunner(parentPid, List.of("waitPID(231)", "done = true"),
                Map.of(), Map.of(), Map.of("231", childInfo(targetPid), "232", childInfo(siblingPid)));
        Thread parentThread = Thread.ofVirtual().start(parent::virtualThreadRun);
        await(() -> parent.getState() == ProcessState.BLOCKED, "parent did not wait for target");

        ProcessRunner controller = createRunner(233, List.of("kill(231)"), Map.of(), Map.of(), Map.of());
        controller.step();

        await(() -> parent.getState().isTerminal(), "waitPID did not observe killed target");
        assertEquals(Boolean.TRUE, field(parentPid, "Program.Data.done"));
        assertTrue(FileUtil.exists(processPath(siblingPid)));
        parentThread.join(1_000);
    }

    @Test
    void orderedPauseAndContinueRequestsHaveDeterministicFinalState() throws Exception {
        int targetPid = 240;
        ProcessRunner target = createRunner(targetPid,
                List.of("while true", "{", "i = i + 1", "}"), Map.of("i", 0), Map.of(), Map.of());
        Thread targetThread = Thread.ofVirtual().start(target::virtualThreadRun);
        await(() -> numberField(targetPid, "Program.Data.i") > 0, "target did not start");

        ProcessRunner.postMessage(targetPid, "ProcessState", ProcessState.PAUSED.name());
        ProcessRunner.postMessage(targetPid, "ProcessState", ProcessState.READY.name());
        int before = numberField(targetPid, "Program.Data.i");
        await(() -> numberField(targetPid, "Program.Data.i") > before,
                "pause followed by continue did not resume");

        ProcessRunner.postMessage(targetPid, "ProcessState", ProcessState.READY.name());
        ProcessRunner.postMessage(targetPid, "ProcessState", ProcessState.PAUSED.name());
        await(() -> target.getState() == ProcessState.PAUSED, "final pause request was lost");
        assertEquals(ProcessState.PAUSED.name(), field(targetPid, "ProcessState"));

        ProcessRunner.terminateProcess(targetPid);
        targetThread.join(1_000);
    }

    @Test
    void schedulerRecoversTemporaryOnlyProcess() throws Exception {
        int pid = 250;
        writeProcess(process(pid, List.of("value = 1"), Map.of(), Map.of(), Map.of()));
        Path real = realProcessPath(pid);
        Path temporary = real.resolveSibling(real.getFileName() + ".tmp");
        Files.move(real, temporary, StandardCopyOption.REPLACE_EXISTING);

        Scheduler scheduler = new Scheduler();
        scheduler.start();
        await(() -> scheduler.getProcess(pid) != null,
                "scheduler did not discover tmp-only process");
        assertTrue(Files.exists(real));
        await(() -> ProcessState.TERMINATED.name().equals(field(pid, "ProcessState")),
                "recovered process did not terminate");
        scheduler.join(2_000);

        assertFalse(scheduler.isAlive());
        assertFalse(Files.exists(temporary));
        assertEquals(1, numberField(pid, "Program.Data.value"));
    }

    @Test
    void schedulerPreservesAndRejectsTwoCorruptSnapshots() throws Exception {
        int pid = 251;
        writeProcess(process(pid, List.of("value = 1"), Map.of(), Map.of(), Map.of()));
        Path real = realProcessPath(pid);
        Path temporary = real.resolveSibling(real.getFileName() + ".tmp");
        Files.writeString(real, "damaged primary");
        Files.writeString(temporary, "damaged temporary");

        Scheduler scheduler = new Scheduler();
        scheduler.start();
        Thread.sleep(150);
        assertNull(scheduler.getProcess(pid));
        scheduler.shutdownScheduler();
        scheduler.join(1_000);

        assertEquals("damaged primary", Files.readString(real));
        assertEquals("damaged temporary", Files.readString(temporary));
    }

    @Test
    void childExitIsDurableBeforeParentRunsAgain() {
        int parentPid = 260;
        int childPid = 261;
        writeProcess(process(childPid, List.of(), Map.of(), Map.of("PID", parentPid), Map.of()));
        ProcessRunner parent = createRunner(parentPid, List.of("wait()", "done = true"),
                Map.of(), Map.of(), Map.of(String.valueOf(childPid), childInfo(childPid)));

        ProcessRunner.terminateProcess(childPid);

        assertNull(field(parentPid, "Child." + childPid));
        assertNotNull(field(parentPid, "ExitedChildren." + childPid));
        assertEquals(ProcessRunner.StepResult.COMPLETED, parent.step());
        assertNull(field(parentPid, "ExitedChildren." + childPid));
        parent.step();
        assertEquals(Boolean.TRUE, field(parentPid, "Program.Data.done"));
    }

    @Test
    void schedulerReplacesStoppedRunnerWhenPidIsReused() throws Exception {
        int reusedPid = 270;
        int sentinelPid = 271;
        writeProcess(process(reusedPid, List.of("while true", "{", "}"), Map.of(), Map.of(), Map.of()));
        writeProcess(process(sentinelPid, List.of("while true", "{", "}"), Map.of(), Map.of(), Map.of()));
        Scheduler scheduler = new Scheduler();
        scheduler.start();
        await(() -> scheduler.getProcess(reusedPid) != null && scheduler.getProcess(sentinelPid) != null,
                "scheduler did not start initial runners");

        ProcessRunner.terminateProcess(reusedPid);
        writeProcess(process(reusedPid, List.of("replacement = 42"), Map.of(), Map.of(), Map.of()));

        await(() -> ProcessState.TERMINATED.name().equals(field(reusedPid, "ProcessState")),
                "scheduler did not replace stale runner for reused PID");
        assertEquals(42, numberField(reusedPid, "Program.Data.replacement"));

        ProcessRunner.terminateProcess(sentinelPid);
        scheduler.shutdownScheduler();
        scheduler.join(1_000);
        assertFalse(scheduler.isAlive());
    }

    @Test
    void temporaryOnlyParentExitEventReservesItsChildPid() throws Exception {
        int savedParentPid = 280;
        Map<String, Object> savedParent = process(
                savedParentPid, List.of("while true", "{", "}"), Map.of(), Map.of(), Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> exited = (Map<String, Object>) savedParent.get("ExitedChildren");
        exited.put("2", Map.of("PID", 2, "ExitReason", ExitReason.KILLED.name()));
        writeProcess(savedParent);
        Path savedReal = realProcessPath(savedParentPid);
        Path savedTemporary = savedReal.resolveSibling(savedReal.getFileName() + ".tmp");
        Files.move(savedReal, savedTemporary, StandardCopyOption.REPLACE_EXISTING);

        ProcessRunner allocator = createRunner(281, List.of("child = fork()"),
                Map.of(), Map.of(), Map.of());
        allocator.step();

        assertEquals(3, numberField(281, "Program.Data.child"));
        assertTrue(Files.exists(savedReal));
        assertFalse(Files.exists(savedTemporary));
    }

    private ProcessRunner createRunner(int pid, List<String> code, Map<String, Object> data,
                                       Map<String, Object> parent, Map<String, Object> children) {
        writeProcess(process(pid, code, data, parent, children));
        return runner(pid);
    }

    private ProcessRunner runner(int pid) {
        Map<String, Object> saved = JsonUtil.parseToMapStrict(FileUtil.read(processPath(pid)));
        ProcessRunner runner = new ProcessRunner(pid, saved);
        runner.init();
        return runner;
    }

    private Map<String, Object> process(int pid, List<String> lines, Map<String, Object> data,
                                        Map<String, Object> parent, Map<String, Object> children) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", pid == Constants.PID_INIT ? "INIT" : "test-" + pid);
        process.put("Owner", "local");
        process.put("PID", pid);
        process.put("ProcessGeneration", "generation-" + pid);
        process.put("Path", "/system/app/test-" + pid + ".fcl");
        process.put("Status", true);
        process.put("ProcessState", ProcessState.NEW.name());
        Map<String, Object> parentInfo = new LinkedHashMap<>(parent);
        Object parentPid = parentInfo.get("PID");
        if (parentPid instanceof Number) {
            parentInfo.putIfAbsent("Generation", "generation-" + ((Number) parentPid).intValue());
        }
        Map<String, Object> childInfo = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : children.entrySet()) {
            if (entry.getValue() instanceof Map) {
                Map<String, Object> child = new LinkedHashMap<>((Map<String, Object>) entry.getValue());
                Object childPid = child.get("PID");
                if (childPid instanceof Number) {
                    child.putIfAbsent("Generation", "generation-" + ((Number) childPid).intValue());
                }
                childInfo.put(entry.getKey(), child);
            }
        }
        process.put("Parent", parentInfo);
        process.put("Child", childInfo);
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

    private Map<String, Object> childInfo(int pid) {
        return Map.of("PID", pid, "Name", "test-" + pid);
    }

    private void writeProcess(Map<String, Object> process) {
        int pid = ((Number) process.get("PID")).intValue();
        JsonUtil.writeFile(processPath(pid), JsonUtil.toJson(process));
    }

    private Object field(int pid, String path) {
        return JsonUtil.getField(processPath(pid), path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapField(int pid, String path) {
        Object value = field(pid, path);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private int numberField(int pid, String path) {
        Object value = field(pid, path);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private String processPath(int pid) {
        return Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
    }

    private Path realProcessPath(int pid) {
        return Path.of(PathUtil.toRealPath(processPath(pid)));
    }

    private static void terminateAfter(CountDownLatch start, int pid) {
        try {
            start.await();
            ProcessRunner.terminateProcess(pid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(BooleanSupplier condition, String failure) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail(failure);
    }
}
