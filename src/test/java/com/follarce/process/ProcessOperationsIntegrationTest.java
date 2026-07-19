package com.follarce.process;

import com.follarce.Constants;
import com.follarce.function.FunctionContext;
import com.follarce.function.FunctionRegistry;
import com.follarce.function.ProcessFunctionProvider;
import com.follarce.init.FileInit;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;
import com.follarce.util.UserUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessOperationsIntegrationTest {
    @TempDir Path root;

    @BeforeAll
    static void registerProcessFunctions() {
        FunctionRegistry.registerProvider(new ProcessFunctionProvider());
    }

    @BeforeEach
    void initializeVfs() {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
    }

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    @Order(1)
    void processProviderReturnsControlMarkersAndIdentity() {
        ProcessFunctionProvider provider = new ProcessFunctionProvider();
        FunctionContext context = new FunctionContext(12, 4, "local");

        assertEquals("FORK", provider.call("fork", List.of(), context));
        assertEquals("EXEC:/system/app/a.fcl:arg", provider.call(
                "exec", List.of("/system/app/a.fcl", "arg"), context));
        assertEquals("KILL:9", provider.call("kill", List.of(9), context));
        assertEquals("WAIT", provider.call("wait", List.of(), context));
        assertEquals("WAITPID:8", provider.call("waitPID", List.of(8), context));
        assertEquals("PAUSE:7", provider.call("pause", List.of(7), context));
        assertEquals("CONTINUE:7", provider.call("continue", List.of(7), context));
        assertEquals(12, provider.call("getPID", List.of(), context));
        assertEquals(4, provider.call("getPPID", List.of(), context));
    }

    @Test
    @Order(2)
    void forkCreatesChildSnapshotAndParentRelationship() {
        ProcessRunner parent = createRunner(10, List.of("childPid = fork()", "after = 1"));
        assertEquals(ProcessRunner.StepResult.COMPLETED, parent.step());

        Number childPid = (Number) field(10, "Program.Data.childPid");
        assertNotNull(childPid);
        int child = childPid.intValue();
        assertTrue(FileUtil.exists(processPath(child)));
        assertEquals(10, ((Number) field(child, "Parent.PID")).intValue());
        assertEquals(ProcessState.READY.name(), field(child, "ProcessState"));
        assertEquals(1, ((Number) field(child, "Program.Code.runningCodeLine")).intValue());
        assertEquals(0, ((Number) field(child, "Program.Data.childPid")).intValue());
        assertNotNull(field(10, "Child." + child), () -> FileUtil.read(processPath(10)));

        Map<String, Object> childData = JsonUtil.parseToMap(FileUtil.read(processPath(child)));
        ProcessRunner childRunner = new ProcessRunner(child, childData);
        childRunner.init();
        childRunner.step();
        childRunner.step();
        assertEquals(ProcessState.TERMINATED, childRunner.getState());

        parent.step();
        assertNull(field(10, "Child." + child));

        ProcessRunner.terminateProcess(10);
        ProcessRunner.terminateProcess(child);
    }

    @Test
    @Order(3)
    void execReplacesProgramAndRunsReplacementScript() {
        FileUtil.createFile(Constants.SYSTEM_APP_PATH, "replacement.fcl");
        FileUtil.write(Constants.SYSTEM_APP_PATH + "replacement.fcl", "result = 42");
        ProcessRunner runner = createRunner(20,
                List.of("exec(\"/system/app/replacement.fcl\")", "oldCode = true"));

        assertEquals(ProcessRunner.StepResult.COMPLETED, runner.step());
        assertEquals("/system/app/replacement.fcl", field(20, "Path"));
        assertEquals("result = 42", ((List<?>) field(20, "Program.Code.Code")).getFirst());
        assertEquals(0, ((Number) field(20, "Program.Code.runningCodeLine")).intValue());

        assertEquals(ProcessRunner.StepResult.COMPLETED, runner.step());
        assertEquals(42, ((Number) field(20, "Program.Data.result")).intValue());
        assertEquals(ProcessRunner.StepResult.TERMINATED, runner.step());
        assertEquals(ProcessState.TERMINATED.name(), field(20, "ProcessState"));
        ProcessRunner.terminateProcess(20);
    }

    @Test
    @Order(4)
    void pidAndPpidAreVisibleInsideFcl() {
        Map<String, Object> parent = new LinkedHashMap<>();
        parent.put("PID", 33);
        ProcessRunner runner = createRunner(30,
                List.of("self = getPID()", "parent = getPPID()"), Map.of(), parent, Map.of());

        runner.step();
        runner.step();
        assertEquals(30, ((Number) field(30, "Program.Data.self")).intValue());
        assertEquals(33, ((Number) field(30, "Program.Data.parent")).intValue());
        ProcessRunner.terminateProcess(30);
    }

    @Test
    @Order(5)
    void pauseAndContinueOperateOnRunningProcess() throws Exception {
        ProcessRunner target = createRunner(40,
                List.of("while true", "{", "i = i + 1", "}"), Map.of("i", 0), Map.of(), Map.of());
        Thread targetThread = Thread.ofVirtual().start(target::virtualThreadRun);
        ProcessRunner controller = createRunner(41, List.of("pause(40)", "continue(40)"));

        controller.step();
        await(() -> target.getState() == ProcessState.PAUSED, "target did not pause");
        assertEquals(ProcessState.PAUSED.name(), field(40, "ProcessState"));
        assertEquals(Boolean.TRUE, field(40, "Status"));

        controller.step();
        await(() -> target.getState() != ProcessState.PAUSED, "target did not continue");
        assertTrue(target.isRunning());

        ProcessRunner.terminateProcess(40);
        ProcessRunner.terminateProcess(41);
        targetThread.join(1_000);
    }

    @Test
    @Order(6)
    void waitAnyWakesWhenAChildTerminates() throws Exception {
        writeProcess(process(51, List.of("while true", "{", "}"), Map.of(), Map.of(), Map.of()));
        ProcessRunner parent = createRunner(50, List.of("wait()", "done = true"), Map.of(), Map.of(),
                Map.of("51", Map.of("PID", 51)));
        Thread parentThread = Thread.ofVirtual().start(parent::virtualThreadRun);

        await(() -> parent.getState() == ProcessState.BLOCKED, "parent did not block");
        assertEquals(BlockReason.WAIT_ANY.name(), field(50, "BlockReason"));
        ProcessRunner.terminateProcess(51);
        await(() -> parent.getState().isTerminal(), "parent did not wake");
        assertEquals(ProcessState.TERMINATED, parent.getState());
        parentThread.join(1_000);
        ProcessRunner.terminateProcess(50);
    }

    @Test
    @Order(7)
    void waitPidIgnoresOtherChildrenAndWakesForTarget() throws Exception {
        writeProcess(process(61, List.of("while true", "{", "}"), Map.of(), Map.of(), Map.of()));
        writeProcess(process(62, List.of("while true", "{", "}"), Map.of(), Map.of(), Map.of()));
        ProcessRunner parent = createRunner(60, List.of("waitPID(61)", "done = true"), Map.of(), Map.of(),
                Map.of("61", Map.of("PID", 61), "62", Map.of("PID", 62)));
        Thread parentThread = Thread.ofVirtual().start(parent::virtualThreadRun);

        await(() -> parent.getState() == ProcessState.BLOCKED, "parent did not block for PID");
        assertEquals(BlockReason.WAIT_PID.name(), field(60, "BlockReason"));
        assertEquals(61, ((Number) field(60, "BlockTargetPid")).intValue());
        ProcessRunner.terminateProcess(62);
        Thread.sleep(150);
        assertEquals(ProcessState.BLOCKED, parent.getState(),
                () -> FileUtil.read(processPath(60)));

        ProcessRunner.terminateProcess(61);
        await(() -> parent.getState().isTerminal(), "parent did not wake for target PID");
        assertEquals(ProcessState.TERMINATED, parent.getState());
        parentThread.join(1_000);
        ProcessRunner.terminateProcess(60);
    }

    @Test
    @Order(8)
    void killDeletesTargetAndStopsItsRunner() throws Exception {
        ProcessRunner target = createRunner(70,
                List.of("while true", "{", "i = i + 1", "}"), Map.of("i", 0), Map.of(), Map.of());
        Thread targetThread = Thread.ofVirtual().start(target::virtualThreadRun);
        ProcessRunner controller = createRunner(71, List.of("kill(70)"));

        controller.step();
        await(() -> !target.isRunning() && !FileUtil.exists(processPath(70)),
                "target runner or process file did not stop");
        assertFalse(FileUtil.exists(processPath(70)),
                () -> FileUtil.read(processPath(70)));
        assertEquals(ProcessState.TERMINATED, target.getState());
        targetThread.join(1_000);
        ProcessRunner.terminateProcess(71);
    }

    @Test
    @Order(9)
    void childListingFindsProcessesByParentPid() {
        writeProcess(process(81, List.of(), Map.of(), Map.of("PID", 80), Map.of()));
        ProcessFunctionProvider provider = new ProcessFunctionProvider();
        String listing = (String) provider.call("getListOfChildProcess", List.of(),
                new FunctionContext(80, 1, "local"));
        assertTrue(listing.contains("PID=81"));
        ProcessRunner.terminateProcess(81);
    }

    @Test
    @Order(10)
    void schedulerDiscoversAndCompletesProcessFile() throws Exception {
        writeProcess(process(90, List.of("value = 1"), Map.of(), Map.of(), Map.of()));
        Scheduler scheduler = new Scheduler();
        scheduler.start();

        await(() -> ProcessState.TERMINATED.name().equals(field(90, "ProcessState")),
                "scheduler process did not terminate");
        scheduler.join(2_000);
        assertFalse(scheduler.isAlive());
        assertEquals(1, ((Number) field(90, "Program.Data.value")).intValue());
        ProcessRunner.terminateProcess(90);
    }

    @Test
    @Order(11)
    void runningSnapshotRestoresAsReadyAfterCrash() {
        Map<String, Object> saved = process(100, List.of("value = 1"), Map.of(), Map.of(), Map.of());
        saved.put("ProcessState", ProcessState.RUNNING.name());
        writeProcess(saved);

        ProcessRunner restored = new ProcessRunner(100, saved);
        restored.init();
        assertEquals(ProcessState.READY, restored.getState());
        assertEquals(ProcessState.READY.name(), field(100, "ProcessState"));
        restored.step();
        assertEquals(1, ((Number) field(100, "Program.Data.value")).intValue());
        ProcessRunner.terminateProcess(100);
    }

    @Test
    @Order(12)
    void concurrentForkAllocatesUniqueChildPids() throws Exception {
        List<ProcessRunner> parents = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int pid = 110; pid < 118; pid++) {
            ProcessRunner parent = createRunner(pid, List.of("childPid = fork()"));
            parents.add(parent);
            threads.add(Thread.ofVirtual().start(parent::step));
        }
        for (Thread thread : threads) thread.join(2_000);

        Set<Integer> childPids = new HashSet<>();
        Map<Integer, Integer> parentToChild = new LinkedHashMap<>();
        Map<Integer, Integer> childToParent = new LinkedHashMap<>();
        for (ProcessRunner parent : parents) {
            int childPid = ((Number) field(parent.getPid(), "Program.Data.childPid")).intValue();
            assertTrue(childPids.add(childPid), "duplicate child PID " + childPid);
            assertTrue(FileUtil.exists(processPath(childPid)));
            parentToChild.put(parent.getPid(), childPid);
            childToParent.put(childPid, ((Number) field(childPid, "Parent.PID")).intValue());
        }
        assertEquals(parents.size(), childPids.size());
        for (Map.Entry<Integer, Integer> entry : parentToChild.entrySet()) {
            assertEquals(entry.getKey(), childToParent.get(entry.getValue()),
                    "assignments=" + parentToChild + ", child parents=" + childToParent);
        }

        for (ProcessRunner parent : parents) ProcessRunner.terminateProcess(parent.getPid());
        for (int childPid : childPids) ProcessRunner.terminateProcess(childPid);
    }

    @Test
    @Order(13)
    void interruptedAtomicProcessWriteRecoversValidTemporarySnapshot() throws Exception {
        Map<String, Object> process = process(130, List.of("value = 1"), Map.of(), Map.of(), Map.of());
        writeProcess(process);
        Path real = Path.of(PathUtil.toRealPath(processPath(130)));
        Path temporary = real.resolveSibling(real.getFileName() + ".tmp");

        Files.move(real, temporary, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(FileUtil.exists(processPath(130)));
        assertTrue(Files.exists(real));
        assertFalse(Files.exists(temporary));
        assertEquals(130, ((Number) field(130, "PID")).intValue());

        Files.move(real, temporary, StandardCopyOption.REPLACE_EXISTING);
        Files.createFile(real);
        assertTrue(FileUtil.exists(processPath(130)));
        assertEquals(130, ((Number) field(130, "PID")).intValue());
        ProcessRunner.terminateProcess(130);
    }

    @Test
    @Order(14)
    void nestedIndexAssignmentUpdatesTheFinalContainer() {
        ProcessRunner runner = createRunner(95, List.of(
                "record = {\"nested\": {\"value\": 9}}",
                "record[\"nested\"][\"value\"] = 11",
                "numbers = [1, 2]",
                "numbers[0] = 7"));

        runner.step();
        runner.step();
        runner.step();
        runner.step();

        assertEquals(11, ((Number) field(95, "Program.Data.record.nested.value")).intValue());
        assertEquals(7, ((Number) field(95, "Program.Data.numbers.0")).intValue());
        ProcessRunner.terminateProcess(95);
    }

    @Test
    @Order(15)
    void nestedFunctionBodyAndRecursiveAssignmentComplete() {
        ProcessRunner runner = createRunner(96, List.of(
                "func factorial(n) {",
                "if n <= 1",
                "{",
                "return 1",
                "}",
                "smaller = factorial(n - 1)",
                "return n * smaller",
                "}",
                "result = factorial(5)"));

        for (int i = 0; i < 100 && !runner.getState().isTerminal(); i++) runner.step();

        assertEquals(120, ((Number) field(96, "Program.Data.result")).intValue());
        assertEquals(ProcessState.TERMINATED, runner.getState());
        ProcessRunner.terminateProcess(96);
    }

    @Test
    @Order(16)
    void continueDiscardsNestedControlFramesAndResumesLoop() {
        ProcessRunner runner = createRunner(97, List.of(
                "sum = 0",
                "i = 0",
                "while i < 5",
                "{",
                "i = i + 1",
                "if i == 3",
                "{",
                "continue",
                "}",
                "sum = sum + i",
                "}"));

        for (int i = 0; i < 100 && !runner.getState().isTerminal(); i++) runner.step();

        assertEquals(12, ((Number) field(97, "Program.Data.sum")).intValue());
        assertEquals(5, ((Number) field(97, "Program.Data.i")).intValue());
        assertEquals(ProcessState.TERMINATED, runner.getState());
        ProcessRunner.terminateProcess(97);
    }

    private ProcessRunner createRunner(int pid, List<String> code) {
        return createRunner(pid, code, Map.of(), Map.of(), Map.of());
    }

    private ProcessRunner createRunner(int pid, List<String> code, Map<String, Object> data,
                                       Map<String, Object> parent, Map<String, Object> children) {
        Map<String, Object> process = process(pid, code, data, parent, children);
        writeProcess(process);
        ProcessRunner runner = new ProcessRunner(pid, process);
        runner.init();
        return runner;
    }

    private Map<String, Object> process(int pid, List<String> lines, Map<String, Object> data,
                                        Map<String, Object> parent, Map<String, Object> children) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", "test-" + pid);
        process.put("Owner", "local");
        process.put("PID", pid);
        process.put("Path", "/system/app/test-" + pid + ".fcl");
        process.put("Status", true);
        process.put("ProcessState", ProcessState.NEW.name());
        process.put("BlockReason", null);
        process.put("ExitReason", null);
        process.put("StateMessage", null);
        process.put("Parent", new LinkedHashMap<>(parent));
        process.put("Child", new LinkedHashMap<>(children));

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

    private void writeProcess(Map<String, Object> process) {
        int pid = ((Number) process.get("PID")).intValue();
        JsonUtil.writeFile(processPath(pid), JsonUtil.toJson(process));
    }

    private Object field(int pid, String path) {
        return JsonUtil.getField(processPath(pid), path);
    }

    private String processPath(int pid) {
        return Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
    }

    private static void await(BooleanSupplier condition, String failure) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail(failure);
    }
}
