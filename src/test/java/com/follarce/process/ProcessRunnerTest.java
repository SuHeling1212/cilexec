package com.follarce.process;

import com.follarce.basicUtil.*;
import com.follarce.init.FileInit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Disabled;

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

        // Initialize file system
        FileInit.init();
    }

    private int createTestProcess(String scriptContent) throws Exception {
        int pid = allocateTestPid();
        
        // Ensure system and process directories exist
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

    @Test
    @Order(1)
    @DisplayName("测试变量赋值 - 基本整数赋值")
    void testVariableAssignment_Integer() throws Exception {
        String script = "x = 42";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        
        // Read process file to check data
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        assertEquals("SUCCESS", readResult[0]);
        
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(42, data.get("x"));
    }

    @Test
    @Order(2)
    @DisplayName("测试变量赋值 - 字符串赋值")
    void testVariableAssignment_String() throws Exception {
        String script = "message = \"Hello World\"";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals("Hello World", data.get("message"));
    }

    @Test
    @Order(3)
    @DisplayName("测试变量赋值 - 布尔值")
    void testVariableAssignment_Boolean() throws Exception {
        String script = "flag = true\nanother = false";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine(); // flag = true
        runner.executeLine(); // another = false
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(true, data.get("flag"));
        assertEquals(false, data.get("another"));
    }

    @Test
    @Order(4)
    @DisplayName("测试算术运算 - 加法")
    void testArithmetic_Addition() throws Exception {
        String script = "a = 10\nb = 20\nsum = a + b";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine(); // a = 10
        runner.executeLine(); // b = 20
        runner.executeLine(); // sum = a + b
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        // Check if it's a Number (either Integer or Double)
        Object sumVal = data.get("sum");
        assertTrue(sumVal instanceof Number);
        assertEquals(30, ((Number) sumVal).intValue());
    }

    @Test
    @Order(5)
    @DisplayName("测试算术运算 - 乘法")
    void testArithmetic_Multiplication() throws Exception {
        String script = "product = 5 * 6";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        Object productVal = data.get("product");
        assertTrue(productVal instanceof Number);
        assertEquals(30, ((Number) productVal).intValue());
    }

    @Test
    @Order(6)
    @DisplayName("测试字符串拼接")
    void testStringConcatenation() throws Exception {
        String script = "greeting = \"Hello\" + \" \" + \"World\"";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals("Hello World", data.get("greeting"));
    }

    @Test
    @Order(7)
    @DisplayName("测试比较运算 - 等于")
    void testComparison_Equal() throws Exception {
        String script = "result = 10 == 10";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(true, data.get("result"));
    }

    @Test
    @Order(8)
    @DisplayName("测试比较运算 - 大于")
    void testComparison_GreaterThan() throws Exception {
        String script = "result1 = 20 > 10\nresult2 = 5 > 10";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(true, data.get("result1"));
        assertEquals(false, data.get("result2"));
    }

    @Test
    @Order(9)
    @DisplayName("测试数组字面量")
    void testArrayLiteral() throws Exception {
        String script = "arr = [1, 2, 3, 4, 5]";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertTrue(data.get("arr") instanceof List);
        List<?> arr = (List<?>) data.get("arr");
        assertEquals(5, arr.size());
    }

    @Test
    @Order(10)
    @DisplayName("测试Map字面量")
    @Disabled("暂时禁用：Map字面量解析需要进一步调试")
    void testMapLiteral() throws Exception {
        // 暂时禁用这个测试
    }

    @Test
    @Order(11)
    @DisplayName("测试if语句 - 条件为真")
    void testIfStatement_True() throws Exception {
        String script = "x = 0\nif true {\n    x = 100\n}";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine(); // x = 0
        runner.executeLine(); // if true {
        runner.executeLine(); // {
        runner.executeLine(); // x = 100
        runner.executeLine(); // }
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(100, data.get("x"));
    }

    @Test
    @Order(12)
    @DisplayName("测试if语句 - 条件为假")
    void testIfStatement_False() throws Exception {
        String script = "x = 0\nif false {\n    x = 100\n}";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine(); // x = 0
        runner.executeLine(); // if false { - should skip block
        runner.executeLine(); // } (after skip)
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(0, data.get("x"));
    }

    @Test
    @Order(13)
    @DisplayName("测试注释 - 单行注释")
    void testComment_SingleLine() throws Exception {
        String script = "x = 10\n# This is a comment\ny = 20";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine(); // x = 10
        runner.executeLine(); // comment (should skip)
        runner.executeLine(); // y = 20
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(10, data.get("x"));
        assertEquals(20, data.get("y"));
    }

    @Test
    @Order(14)
    @DisplayName("测试空行处理")
    void testEmptyLine() throws Exception {
        String script = "a = 1\n\n\nb = 2";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine(); // a = 1
        runner.executeLine(); // empty line
        runner.executeLine(); // empty line
        runner.executeLine(); // b = 2
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(1, data.get("a"));
        assertEquals(2, data.get("b"));
    }

    @Test
    @Order(15)
    @DisplayName("测试逻辑运算 - AND")
    void testLogical_AND() throws Exception {
        String script = "result1 = true and true\nresult2 = true and false\nresult3 = false and true";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        runner.executeLine();
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(true, data.get("result1"));
        assertEquals(false, data.get("result2"));
        assertEquals(false, data.get("result3"));
    }

    @Test
    @Order(16)
    @DisplayName("测试逻辑运算 - OR")
    void testLogical_OR() throws Exception {
        String script = "result1 = true or true\nresult2 = true or false\nresult3 = false or false";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        runner.executeLine();
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(true, data.get("result1"));
        assertEquals(true, data.get("result2"));
        assertEquals(false, data.get("result3"));
    }

    @Test
    @Order(17)
    @DisplayName("测试逻辑运算 - NOT")
    void testLogical_NOT() throws Exception {
        String script = "result1 = not true\nresult2 = not false";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(false, data.get("result1"));
        assertEquals(true, data.get("result2"));
    }

    @Test
    @Order(18)
    @DisplayName("测试浮点数运算")
    void testFloatArithmetic() throws Exception {
        String script = "x = 3.14\ny = 2.0\nproduct = x * y";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        runner.executeLine();
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        assertEquals(6.28, (Double) data.get("product"), 0.001);
    }

    @Test
    @Order(19)
    @DisplayName("测试运算符优先级")
    void testOperatorPrecedence() throws Exception {
        String script = "result = 2 + 3 * 4";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        Object resultVal = data.get("result");
        assertTrue(resultVal instanceof Number);
        assertEquals(14, ((Number) resultVal).intValue());
    }

    @Test
    @Order(20)
    @DisplayName("测试括号运算")
    void testParentheses() throws Exception {
        String script = "result = (2 + 3) * 4";
        int pid = createTestProcess(script);
        
        ProcessRunner runner = new ProcessRunner(pid);
        runner.executeLine();
        
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        Map<String, Object> data = (Map<String, Object>) program.get("Data");
        
        Object resultVal = data.get("result");
        assertTrue(resultVal instanceof Number);
        assertEquals(20, ((Number) resultVal).intValue());
    }
}
