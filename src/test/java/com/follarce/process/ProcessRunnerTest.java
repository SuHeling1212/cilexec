package com.follarce.process;

import com.follarce.basicUtil.*;
import com.follarce.init.FileInit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessRunnerTest {

    @TempDir
    Path tempDir;

    private static Path testRoot;
    private static boolean originalVfsRootSet = false;
    private static String originalVfsRoot = null;

    @BeforeAll
    static void setupClass() throws Exception {
        try {
            java.lang.reflect.Field vfsRootField = FileUtil.class.getDeclaredField("VFS_ROOT");
            vfsRootField.setAccessible(true);
            originalVfsRoot = (String) vfsRootField.get(null);
            originalVfsRootSet = true;
        } catch (Exception e) {
            originalVfsRootSet = false;
        }
    }

    @AfterAll
    static void teardownClass() throws Exception {
        if (originalVfsRootSet && originalVfsRoot != null) {
            java.lang.reflect.Field vfsRootField = FileUtil.class.getDeclaredField("VFS_ROOT");
            vfsRootField.setAccessible(true);
            vfsRootField.set(null, originalVfsRoot);
        }
    }

    @BeforeEach
    void setup() throws Exception {
        testRoot = tempDir.resolve("test_vfs");
        Files.createDirectories(testRoot);

        java.lang.reflect.Field vfsRootField = FileUtil.class.getDeclaredField("VFS_ROOT");
        vfsRootField.setAccessible(true);
        vfsRootField.set(null, testRoot.toString());

        FileInit.init();
    }

    private int createTestProcess(String scriptContent) throws Exception {
        int pid = allocateTestPid();

        FileUtil.createDirectory("/", "system");
        FileUtil.createDirectory("/system/", "process");

        Map<String, Object> process = new HashMap<>();
        process.put("Name", "TestProcess");
        process.put("Owner", "local");
        process.put("Local", true);
        process.put("PID", pid);
        process.put("Path", "/test/script.fcl");
        process.put("Status", true);

        int[] now = TimeUtil.getTime();
        process.put("startTime", new int[]{now[0], now[1], now[2], now[3], now[4], now[5], now[6]});
        process.put("RunningTime", 0);
        process.put("Parent", new HashMap<>());
        process.put("Child", new HashMap<>());

        Map<String, Object> program = new HashMap<>();
        program.put("Data", new HashMap<>());

        Map<String, Object> code = new HashMap<>();
        code.put("runningCodeLine", 0);

        List<String> codeLines = Arrays.asList(scriptContent.split("\n"));
        code.put("Code", codeLines);
        code.put("BlockStack", new ArrayList<>());

        program.put("Code", code);
        process.put("Program", program);

        String[] createResult = FileUtil.createFile("/system/process/", pid + ".json");
        if (!"SUCCESS".equals(createResult[0])) {
            throw new RuntimeException("Failed to create process file: " + Arrays.toString(createResult));
        }
        FileUtil.write("/system/process/" + pid + ".json", JsonUtil.toJson(process));

        return pid;
    }

    private int allocateTestPid() {
        String[] listResult = FileUtil.getListOfFileAndDirectory("/system/process/");
        if (!listResult[0].equals("SUCCESS")) {
            return 100;
        }

        int maxPid = 99;
        for (int i = 1; i < listResult.length; i++) {
            String name = listResult[i];
            if (name.endsWith(".json")) {
                try {
                    int pid = Integer.parseInt(name.replace(".json", ""));
                    if (pid > maxPid) maxPid = pid;
                } catch (NumberFormatException e) {
                }
            }
        }
        return maxPid + 1;
    }

    private List<Map<String, Object>> getBlockStack(int pid) {
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        assertEquals("SUCCESS", readResult[0]);

        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> code = (Map<String, Object>) program.get("Code");
        Object blockStackObj = code.get("BlockStack");

        if (blockStackObj instanceof List) {
            return (List<Map<String, Object>>) blockStackObj;
        }
        return new ArrayList<>();
    }

    private int getRunningCodeLine(int pid) {
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        assertEquals("SUCCESS", readResult[0]);

        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> code = (Map<String, Object>) program.get("Code");
        Object runningLine = code.get("runningCodeLine");

        if (runningLine instanceof Number) {
            return ((Number) runningLine).intValue();
        }
        return -1;
    }

    @Test
    @Order(1)
    @DisplayName("BlockStack稳定性 - while循环迭代时stack不应无限增长")
    void testBlockStackStability_WhileLoopIteration() throws Exception {
        String script = "i = 0\nwhile i < 3 {\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        for (int iter = 0; iter < 10; iter++) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 4) break;

            runner.executeLine();

            List<Map<String, Object>> blockStack = getBlockStack(pid);
            currentLine = getRunningCodeLine(pid);
            if (currentLine >= 4) break;
            assertTrue(blockStack.size() <= 1,
                "BlockStack size should not exceed 1 during while loop iteration, but got: " + blockStack.size());
        }

        int currentLine = getRunningCodeLine(pid);
        if (currentLine < 4) {
            runner.executeLine();
        }
        currentLine = getRunningCodeLine(pid);
        if (currentLine < 4) {
            runner.executeLine();
        }

        List<Map<String, Object>> blockStack = getBlockStack(pid);
        assertTrue(blockStack.isEmpty() || blockStack.size() <= 1,
            "BlockStack should be empty or have at most 1 entry after while loop completes");
    }

    @Test
    @Order(2)
    @DisplayName("BlockStack稳定性 - 验证blockStack持久化正确")
    void testBlockStackStability_Persistence() throws Exception {
        String script = "i = 0\nwhile i < 5 {\n    i = i + 1\n}";

        int pid = createTestProcess(script);

        ProcessRunner runner1 = new ProcessRunner(pid);
        runner1.executeLine();
        runner1.executeLine();
        runner1.executeLine();

        List<Map<String, Object>> blockStack1 = getBlockStack(pid);
        assertFalse(blockStack1.isEmpty(), "BlockStack should not be empty during while loop");

        ProcessRunner runner2 = new ProcessRunner(pid);
        List<Map<String, Object>> blockStack2 = getBlockStack(pid);
        assertEquals(blockStack1.size(), blockStack2.size(),
            "BlockStack size should be consistent after reload");

        assertEquals("WHILE", blockStack2.get(0).get("type"));
        assertEquals(1, blockStack2.get(0).get("startLine"));
    }

    @Test
    @Order(3)
    @DisplayName("嵌套while循环 - 外层循环计数器正确")
    void testNestedWhile_OuterLoopCounter() throws Exception {
        String script = "i = 0\nwhile i < 2 {\n    j = 0\n    while j < 2 {\n        j = j + 1\n    }\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        int maxIterations = 50;
        int count = 0;
        while (count < maxIterations) {
            runner.executeLine();
            count++;
            if (getRunningCodeLine(pid) >= 8) break;
        }

        assertTrue(count < maxIterations, "While loop should complete without infinite iteration");

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(2, data.get("i"));
        assertEquals(2, data.get("j"));
    }

    @Test
    @Order(4)
    @DisplayName("嵌套while循环 - 内层循环独立")
    void testNestedWhile_InnerLoopIndependent() throws Exception {
        String script = "i = 0\nouter_result = 0\nwhile i < 3 {\n    j = 0\n    inner_result = 0\n    while j < 2 {\n        inner_result = inner_result + 1\n        j = j + 1\n    }\n    outer_result = outer_result + inner_result\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        int maxIterations = 500;
        int count = 0;
        while (count < maxIterations) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 11) break;
            runner.executeLine();
            count++;
        }

        assertTrue(count < maxIterations, "Nested while loops should complete without infinite iteration");

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(3, data.get("i"));
        assertEquals(2, data.get("j"));
        assertEquals(6, data.get("outer_result"));
        assertEquals(2, data.get("inner_result"));
    }

    @Test
    @Order(5)
    @DisplayName("嵌套while循环 - BlockStack正确管理多层嵌套")
    void testNestedWhile_BlockStackManagement() throws Exception {
        String script = "i = 0\nwhile i < 2 {\n    j = 0\n    while j < 2 {\n        k = 0\n        while k < 2 {\n            k = k + 1\n        }\n        j = j + 1\n    }\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        for (int i = 0; i < 50; i++) {
            runner.executeLine();

            List<Map<String, Object>> blockStack = getBlockStack(pid);
            assertTrue(blockStack.size() <= 3,
                "BlockStack size should not exceed 3 for triple nested while, got: " + blockStack.size());
        }

        int maxIterations = 500;
        int count = 0;
        while (count < maxIterations) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 13) break;
            runner.executeLine();
            count++;
        }

        assertTrue(count < maxIterations, "Triple nested while should complete without infinite iteration");
    }

    @Test
    @Order(6)
    @DisplayName("if/while混合嵌套 - while内含if语句")
    void testIfWhileMixed_WhileWithIf() throws Exception {
        String script = "i = 0\nresult = 0\nwhile i < 5 {\n    if i > 2 {\n        result = result + 1\n    }\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        int maxIterations = 500;
        int count = 0;
        while (count < maxIterations) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 9) break;
            runner.executeLine();
            count++;
        }

        assertTrue(count < maxIterations, "while with inner if should complete");

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(5, data.get("i"));
        assertEquals(2, data.get("result"));
    }

    @Test
    @Order(7)
    @DisplayName("if/while混合嵌套 - if内含while语句")
    void testIfWhileMixed_IfWithWhile() throws Exception {
        String script = "condition = true\ncounter = 0\nif condition {\n    i = 0\n    while i < 3 {\n        counter = counter + 1\n        i = i + 1\n    }\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        int maxIterations = 500;
        int count = 0;
        while (count < maxIterations) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 9) break;
            runner.executeLine();
            count++;
        }

        assertTrue(count < maxIterations, "if with inner while should complete");

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(3, data.get("counter"));
    }

    @Test
    @Order(8)
    @DisplayName("if/while混合嵌套 - 多层嵌套正确执行")
    void testIfWhileMixed_MultiLevelNesting() throws Exception {
        String script = "i = 0\nresult = 0\nwhile i < 3 {\n    if i == 1 {\n        j = 0\n        while j < 2 {\n            result = result + 1\n            j = j + 1\n        }\n    }\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        int maxIterations = 500;
        int count = 0;
        while (count < maxIterations) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 11) break;
            runner.executeLine();
            count++;
        }

        assertTrue(count < maxIterations, "Multi-level if/while nesting should complete");

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(3, data.get("i"));
        assertEquals(2, data.get("result"));
    }

    @Test
    @Order(9)
    @DisplayName("break语句 - 跳出while循环")
    void testBreak_SimpleWhileLoop() throws Exception {
        String script = "i = 0\nresult = 0\nwhile i < 10 {\n    if i == 5 {\n        break\n    }\n    result = result + 1\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        int maxIterations = 500;
        int count = 0;
        while (count < maxIterations) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 7) break;
            runner.executeLine();
            count++;
        }

        assertTrue(count < maxIterations, "break should exit loop");

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(5, data.get("i"));
        assertEquals(5, data.get("result"));
    }

    @Test
    @Order(10)
    @DisplayName("break语句 - 跳出嵌套while的最内层循环")
    void testBreak_NestedWhileInnerLoop() throws Exception {
        String script = "i = 0\nouter_result = 0\nwhile i < 3 {\n    j = 0\n    inner_result = 0\n    while j < 10 {\n        if j == 2 {\n            break\n        }\n        inner_result = inner_result + 1\n        j = j + 1\n    }\n    outer_result = outer_result + inner_result\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        int maxIterations = 500;
        int count = 0;
        while (count < maxIterations) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 14) break;
            runner.executeLine();
            count++;
        }

        assertTrue(count < maxIterations, "break should only exit innermost loop");

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(3, data.get("i"));
        assertEquals(2, data.get("inner_result"));
        assertEquals(6, data.get("outer_result"));
    }

    @Test
    @Order(11)
    @DisplayName("break语句 - BlockStack在break后正确恢复")
    void testBreak_BlockStackRecovery() throws Exception {
        String script = "i = 0\nwhile i < 3 {\n    if i == 1 {\n        break\n    }\n    i = i + 1\n}\nafter_loop = 99";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        int maxIterations = 500;
        int count = 0;
        while (count < maxIterations) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 6) break;
            runner.executeLine();
            count++;
        }

        assertTrue(count < maxIterations, "break should not corrupt BlockStack");

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(1, data.get("i"));
        assertTrue(data.containsKey("after_loop"), "Code after break should still execute");
    }

    @Test
    @Order(12)
    @DisplayName("进程状态持久化 - runningCodeLine正确保存和恢复")
    void testProcessPersistence_RunningCodeLine() throws Exception {
        String script = "a = 1\nb = 2\nc = 3\nd = 4\ne = 5";

        int pid = createTestProcess(script);

        ProcessRunner runner1 = new ProcessRunner(pid);
        runner1.executeLine();
        runner1.executeLine();

        int lineAfterTwoExecutions = getRunningCodeLine(pid);
        assertEquals(2, lineAfterTwoExecutions);

        ProcessRunner runner2 = new ProcessRunner(pid);
        int restoredLine = getRunningCodeLine(pid);
        assertEquals(lineAfterTwoExecutions, restoredLine);

        runner2.executeLine();
        int lineAfterThreeExecutions = getRunningCodeLine(pid);
        assertEquals(3, lineAfterThreeExecutions);
    }

    @Test
    @Order(13)
    @DisplayName("进程状态持久化 - Data正确保存和恢复")
    void testProcessPersistence_Data() throws Exception {
        String script = "x = 10\ny = 20\nz = x + y";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        runner.executeLine();
        runner.executeLine();
        runner.executeLine();

        ProcessRunner runner2 = new ProcessRunner(pid);

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(10, data.get("x"));
        assertEquals(20, data.get("y"));
        assertEquals(30, data.get("z"));
    }

    @Test
    @Order(14)
    @DisplayName("进程状态持久化 - while循环中途停止和恢复")
    void testProcessPersistence_WhileLoopMidExecution() throws Exception {
        String script = "i = 0\nsum = 0\nwhile i < 100 {\n    sum = sum + i\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        for (int i = 0; i < 10; i++) {
            runner.executeLine();
        }

        int midLine = getRunningCodeLine(pid);
        assertTrue(midLine > 0 && midLine < 5, "Should be mid-execution in while loop");

        ProcessRunner runner2 = new ProcessRunner(pid);
        int restoredLine = getRunningCodeLine(pid);
        assertEquals(midLine, restoredLine, "runningCodeLine should be restored");

        int maxIterations = 500;
        int count = 0;
        while (count < maxIterations) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 6) break;
            runner2.executeLine();
            count++;
        }

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(100, data.get("i"));
        assertEquals(4950, data.get("sum"));
    }

    @Test
    @Order(15)
    @DisplayName("进程状态持久化 - BlockStack在恢复后正确工作")
    void testProcessPersistence_BlockStackAfterRecovery() throws Exception {
        String script = "i = 0\nwhile i < 10 {\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        for (int i = 0; i < 5; i++) {
            runner.executeLine();
        }

        int midLine = getRunningCodeLine(pid);

        ProcessRunner runner2 = new ProcessRunner(pid);
        int restoredLine = getRunningCodeLine(pid);
        assertEquals(midLine, restoredLine);

        int maxIterations = 50;
        int count = 0;
        while (count < maxIterations) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 4) break;
            runner2.executeLine();
            count++;
        }

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(10, data.get("i"));
    }

    @Test
    @Order(16)
    @DisplayName("BlockStack稳定性 - 反复迭代同一while循环")
    void testBlockStackStability_RepeatedIteration() throws Exception {
        String script = "i = 0\nwhile i < 5 {\n    temp = i * 2\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        for (int iteration = 0; iteration < 15; iteration++) {
            runner.executeLine();

            List<Map<String, Object>> blockStack = getBlockStack(pid);
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 5) break;
            assertTrue(blockStack.size() <= 1,
                "Iteration " + iteration + ": BlockStack should be at most 1, got: " + blockStack.size());
        }
    }

    @Test
    @Order(17)
    @DisplayName("BlockStack稳定性 - while条件为false时不push")
    void testBlockStackStability_WhileConditionFalse() throws Exception {
        String script = "i = 10\nwhile i < 5 {\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        runner.executeLine();
        runner.executeLine();

        List<Map<String, Object>> blockStack = getBlockStack(pid);
        assertTrue(blockStack.isEmpty(),
            "BlockStack should be empty when while condition is false");
    }

    @Test
    @Order(18)
    @DisplayName("if/while混合嵌套 - 复杂条件组合")
    void testIfWhileMixed_ComplexCondition() throws Exception {
        String script = "i = 0\nj = 0\nresult = 0\nwhile i < 4 {\n    if i > 0 and j < 3 {\n        result = result + i * j\n    }\n    j = 0\n    while j < 2 {\n        if j == 1 {\n            result = result + 1\n        }\n        j = j + 1\n    }\n    i = i + 1\n}";

        int pid = createTestProcess(script);
        ProcessRunner runner = new ProcessRunner(pid);

        int maxIterations = 500;
        int count = 0;
        while (count < maxIterations) {
            int currentLine = getRunningCodeLine(pid);
            if (currentLine >= 13) break;
            runner.executeLine();
            count++;
        }

        assertTrue(count < maxIterations, "Complex if/while nesting should complete");

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");

        assertEquals(4, data.get("i"));
        assertEquals(4, data.get("result"));
    }
}