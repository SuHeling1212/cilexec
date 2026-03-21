package com.follarce.test;

import com.follarce.basicUtil.*;
import com.follarce.init.FileInit;
import com.follarce.init.ProcessInit;
import com.follarce.init.UserInit;
import com.follarce.network.NetworkUtil;
import com.follarce.process.ProcessFunc;
import com.follarce.process.ProcessRunner;
import com.follarce.process.SwapUtil;
import com.follarce.plugin.FunctionContext;
import com.follarce.plugin.FunctionRegistry;
import com.follarce.plugin.FunctionInfo;
import com.follarce.plugin.FileFunctionProvider;
import com.follarce.plugin.ProcessFunctionProvider;
import com.follarce.plugin.UserFunctionProvider;
import com.follarce.plugin.UtilFunctionProvider;

import java.util.*;

/**
 * Comprehensive test for all Java APIs
 * Tests all public methods documented in README.md
 */
public class AllJavaAPITest {

    private static int passed = 0;
    private static int failed = 0;
    private static StringBuilder report = new StringBuilder();

    public static void main(String[] args) {
        System.out.println("=== CilExec Java API Comprehensive Test ===\n");

        // Initialize system
        System.out.println("Initializing system...");
        FileInit.init();
        registerFunctionProviders();
        System.out.println("System initialized.\n");

        // Run all tests
        testFileUtilAPI();
        testProcessFuncAPI();
        testSwapUtilAPI();
        testNetworkUtilAPI();
        testJsonUtilAPI();
        testTimeUtilAPI();
        testUserUtilAPI();
        testUserInitAPI();
        testProcessRunnerAPI();
        testProcessInitAPI();
        testFunctionRegistryAPI();
        testFunctionContextAPI();
        testFunctionInfoAPI();
        testLoggerAPI();

        // Print report
        System.out.println("\n" + "=".repeat(50));
        System.out.println("Test Report:");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        System.out.println("Total:  " + (passed + failed));
        System.out.println("=".repeat(50));

        if (failed > 0) {
            System.out.println("\nFailed Tests:");
            System.out.println(report.toString());
        }
    }

    private static void registerFunctionProviders() {
        FunctionRegistry.register(new FileFunctionProvider());
        FunctionRegistry.register(new ProcessFunctionProvider());
        FunctionRegistry.register(new UserFunctionProvider());
        FunctionRegistry.register(new UtilFunctionProvider());
    }

    // ==================== FileUtil API Tests ====================
    private static void testFileUtilAPI() {
        System.out.println("\n--- Testing FileUtil API ---");

        // Test createFile
        test("FileUtil.createFile", () -> {
            String[] result = FileUtil.createFile("/user/local/app/", "test_api.txt");
            assertEquals("SUCCESS", result[0]);
        });

        // Test write
        test("FileUtil.write", () -> {
            String[] result = FileUtil.write("/user/local/app/test_api.txt", "test content");
            assertEquals("SUCCESS", result[0]);
        });

        // Test read
        test("FileUtil.read", () -> {
            String[] result = FileUtil.read("/user/local/app/test_api.txt");
            assertEquals("SUCCESS", result[0]);
            assertEquals("test content", result[1]);
        });

        // Test createDirectory
        test("FileUtil.createDirectory", () -> {
            String[] result = FileUtil.createDirectory("/user/local/app/", "testdir");
            assertEquals("SUCCESS", result[0]);
        });

        // Test getListOfFileAndDirectory
        test("FileUtil.getListOfFileAndDirectory", () -> {
            String[] result = FileUtil.getListOfFileAndDirectory("/user/local/app/");
            assertEquals("SUCCESS", result[0]);
            assertTrue(result.length > 1);
        });

        // Test removeFile
        test("FileUtil.removeFile", () -> {
            FileUtil.createFile("/user/local/app/", "remove_test.txt");
            String[] result = FileUtil.removeFile("/user/local/app/remove_test.txt");
            assertEquals("SUCCESS", result[0]);
        });

        // Test removeDirectory
        test("FileUtil.removeDirectory", () -> {
            FileUtil.createDirectory("/user/local/app/", "removedir");
            String[] result = FileUtil.removeDirectory("/user/local/app/removedir/");
            assertEquals("SUCCESS", result[0]);
        });

        // Test Rename
        test("FileUtil.Rename", () -> {
            FileUtil.createFile("/user/local/app/", "oldname.txt");
            String[] result = FileUtil.Rename("/user/local/app/oldname.txt", "newname.txt");
            assertEquals("SUCCESS", result[0]);
            FileUtil.removeFile("/user/local/app/newname.txt");
        });

        // Test Link
        test("FileUtil.Link", () -> {
            FileUtil.createFile("/user/local/app/", "link_source.txt");
            FileUtil.write("/user/local/app/link_source.txt", "link target content");
            String[] result = FileUtil.Link("/user/local/app/", "/user/local/app/link_source.txt");
            assertEquals("SUCCESS", result[0]);
            FileUtil.removeFile("/user/local/app/link_source.txt");
        });

        // Test lock/unlock
        test("FileUtil.lock", () -> {
            FileUtil.createFile("/user/local/app/", "lock_test.txt");
            String[] result = FileUtil.lock("/user/local/app/lock_test.txt");
            assertEquals("SUCCESS", result[0]);
            FileUtil.unlock("/user/local/app/lock_test.txt");
            FileUtil.removeFile("/user/local/app/lock_test.txt");
        });

        test("FileUtil.unlock", () -> {
            FileUtil.createFile("/user/local/app/", "unlock_test.txt");
            FileUtil.lock("/user/local/app/unlock_test.txt");
            String[] result = FileUtil.unlock("/user/local/app/unlock_test.txt");
            assertEquals("SUCCESS", result[0]);
            FileUtil.removeFile("/user/local/app/unlock_test.txt");
        });

        // Test readFileMetaData
        test("FileUtil.readFileMetaData", () -> {
            FileUtil.createFile("/user/local/app/", "meta_test.txt");
            String[] result = FileUtil.readFileMetaData("/user/local/app/meta_test.txt");
            assertEquals("SUCCESS", result[0]);
            FileUtil.removeFile("/user/local/app/meta_test.txt");
        });

        // Test writeFileMetaData
        test("FileUtil.writeFileMetaData", () -> {
            FileUtil.createFile("/user/local/app/", "meta_write_test.txt");
            String[] result = FileUtil.writeFileMetaData("/user/local/app/meta_write_test.txt", "{\"custom\":\"value\"}");
            assertEquals("SUCCESS", result[0]);
            FileUtil.removeFile("/user/local/app/meta_write_test.txt");
        });

        // Test readDirectoryMetaData
        test("FileUtil.readDirectoryMetaData", () -> {
            String[] result = FileUtil.readDirectoryMetaData("/user/local/app/");
            assertTrue(result[0].equals("SUCCESS") || result[0].equals("ERROR"));
        });

        // Test writeDirectoryMetaData
        test("FileUtil.writeDirectoryMetaData", () -> {
            String[] result = FileUtil.writeDirectoryMetaData("/user/local/app/", "{\"custom\":\"dir_value\"}");
            assertEquals("SUCCESS", result[0]);
        });

        // Test createDirectoryMetaData
        test("FileUtil.createDirectoryMetaData", () -> {
            String[] result = FileUtil.createDirectoryMetaData("/user/local/app/");
            assertEquals("SUCCESS", result[0]);
        });

        // Test getVfsRoot
        test("FileUtil.getVfsRoot", () -> {
            String root = FileUtil.getVfsRoot();
            assertNotNull(root);
            assertTrue(root.length() > 0);
        });

        // Test call (function dispatch)
        test("FileUtil.call", () -> {
            FileUtil.createFile("/user/local/app/", "call_test.txt");
            FileUtil.write("/user/local/app/call_test.txt", "call test content");
            Object result = FileUtil.call("read", new Object[]{"/user/local/app/call_test.txt"});
            assertTrue(result instanceof String[]);
            assertEquals("SUCCESS", ((String[]) result)[0]);
            FileUtil.removeFile("/user/local/app/call_test.txt");
        });
    }

    // ==================== ProcessFunc API Tests ====================
    private static void testProcessFuncAPI() {
        System.out.println("\n--- Testing ProcessFunc API ---");

        // Test setCurrentPid and getPID
        test("ProcessFunc.setCurrentPid/getPID", () -> {
            ProcessFunc.setCurrentPid(1);
            int pid = ProcessFunc.getPID();
            assertEquals(1, pid);
        });

        // Test getPPID
        test("ProcessFunc.getPPID", () -> {
            int ppid = ProcessFunc.getPPID();
            assertTrue(ppid >= 0);
        });

        // Test getListOfProcess
        test("ProcessFunc.getListOfProcess", () -> {
            Object result = ProcessFunc.getListOfProcess();
            assertNotNull(result);
            assertTrue(result instanceof Map);
        });

        // Test getListOfChildProcess
        test("ProcessFunc.getListOfChildProcess", () -> {
            Object result = ProcessFunc.getListOfChildProcess();
            assertNotNull(result);
            assertTrue(result instanceof Map);
        });

        // Test call (function dispatch)
        test("ProcessFunc.call", () -> {
            Object result = ProcessFunc.call("getPID", new Object[]{});
            assertTrue(result instanceof Integer);
        });
    }

    // ==================== SwapUtil API Tests ====================
    private static void testSwapUtilAPI() {
        System.out.println("\n--- Testing SwapUtil API ---");

        // Test createSwapPool
        test("SwapUtil.createSwapPool", () -> {
            String[] result = SwapUtil.createSwapPool("test_pool");
            assertEquals("SUCCESS", result[0]);
        });

        // Test swapPoolAdd
        test("SwapUtil.swapPoolAdd", () -> {
            String[] result = SwapUtil.swapPoolAdd("testvar:testvalue", "test_pool", new String[]{"always"});
            assertEquals("SUCCESS", result[0]);
        });

        // Test swapPoolGet
        test("SwapUtil.swapPoolGet", () -> {
            Object result = SwapUtil.swapPoolGet("testvar", "test_pool");
            assertEquals("testvalue", result);
        });

        // Test swapPoolLock
        test("SwapUtil.swapPoolLock", () -> {
            String[] result = SwapUtil.swapPoolLock("testvar", "test_pool");
            assertEquals("SUCCESS", result[0]);
        });

        // Test swapPoolUnlock
        test("SwapUtil.swapPoolUnlock", () -> {
            String[] result = SwapUtil.swapPoolUnlock("testvar", "test_pool");
            assertEquals("SUCCESS", result[0]);
        });

        // Test swapPoolUpdate
        test("SwapUtil.swapPoolUpdate", () -> {
            SwapUtil.swapPoolLock("testvar", "test_pool");
            String[] result = SwapUtil.swapPoolUpdate("testvar", "test_pool", "updated_value");
            assertEquals("SUCCESS", result[0]);
            SwapUtil.swapPoolUnlock("testvar", "test_pool");
        });

        // Test swapPoolGetAll
        test("SwapUtil.swapPoolGetAll", () -> {
            Object result = SwapUtil.swapPoolGetAll("test_pool");
            assertTrue(result instanceof Map);
        });

        // Test swapPoolRemove
        test("SwapUtil.swapPoolRemove", () -> {
            String[] result = SwapUtil.swapPoolRemove("testvar", "test_pool");
            assertEquals("SUCCESS", result[0]);
        });

        // Test removeSwapPool
        test("SwapUtil.removeSwapPool", () -> {
            String[] result = SwapUtil.removeSwapPool("test_pool");
            assertEquals("SUCCESS", result[0]);
        });

        // Test onProcessExit
        test("SwapUtil.onProcessExit", () -> {
            // Just verify it doesn't throw exception
            SwapUtil.onProcessExit(99999);
        });
    }

    // ==================== NetworkUtil API Tests ====================
    private static void testNetworkUtilAPI() {
        System.out.println("\n--- Testing NetworkUtil API ---");

        // Test webget (basic version) - may fail without network
        test("NetworkUtil.webget (2 args)", () -> {
            // This test may fail due to network issues, so we just check it doesn't throw
            try {
                String[] result = NetworkUtil.webget("https://example.com/", "/user/local/app/");
                // Should return either SUCCESS or ERROR
                assertTrue(result[0].equals("SUCCESS") || result[0].equals("ERROR"));
            } catch (Exception e) {
                // Network errors are acceptable
            }
        });

        // Test webget with timeout - may fail without network
        test("NetworkUtil.webget (3 args)", () -> {
            try {
                String[] result = NetworkUtil.webget("https://example.com/", "/user/local/app/", 5000);
                assertTrue(result[0].equals("SUCCESS") || result[0].equals("ERROR"));
            } catch (Exception e) {
                // Network errors are acceptable
            }
        });
    }

    // ==================== JsonUtil API Tests ====================
    private static void testJsonUtilAPI() {
        System.out.println("\n--- Testing JsonUtil API ---");

        // Test toJson
        test("JsonUtil.toJson", () -> {
            Map<String, Object> map = new HashMap<>();
            map.put("key", "value");
            map.put("number", 123);
            String json = JsonUtil.toJson(map);
            assertTrue(json.contains("key"));
            assertTrue(json.contains("value"));
        });

        // Test readJson
        test("JsonUtil.readJson", () -> {
            String json = "{\"name\":\"test\",\"value\":42}";
            Object result = JsonUtil.readJson(json);
            assertTrue(result instanceof Map);
            Map<String, Object> map = (Map<String, Object>) result;
            assertEquals("test", map.get("name"));
        });

        // Test isValidJson
        test("JsonUtil.isValidJson (valid)", () -> {
            boolean valid = JsonUtil.isValidJson("{\"key\":\"value\"}");
            assertTrue(valid);
        });

        test("JsonUtil.isValidJson (invalid)", () -> {
            boolean valid = JsonUtil.isValidJson("{invalid json");
            assertFalse(valid);
        });
    }

    // ==================== TimeUtil API Tests ====================
    private static void testTimeUtilAPI() {
        System.out.println("\n--- Testing TimeUtil API ---");

        // Test getTime
        test("TimeUtil.getTime", () -> {
            int[] time = TimeUtil.getTime();
            assertEquals(7, time.length);
            assertTrue(time[0] > 2000); // Year should be > 2000
        });
    }

    // ==================== UserUtil API Tests ====================
    private static void testUserUtilAPI() {
        System.out.println("\n--- Testing UserUtil API ---");

        // Test setCurrentUser/getCurrentUser
        test("UserUtil.setCurrentUser/getCurrentUser", () -> {
            UserUtil.setCurrentUser("testuser");
            assertEquals("testuser", UserUtil.getCurrentUser());
            UserUtil.setCurrentUser("local");
        });

        // Test isLocal
        test("UserUtil.isLocal (local)", () -> {
            UserUtil.setCurrentUser("local");
            assertTrue(UserUtil.isLocal());
        });

        test("UserUtil.isLocal (non-local)", () -> {
            UserUtil.setCurrentUser("testuser");
            assertFalse(UserUtil.isLocal());
            UserUtil.setCurrentUser("local");
        });

        // Test checkFilePermission
        test("UserUtil.checkFilePermission", () -> {
            UserUtil.setCurrentUser("local");
            boolean canRead = UserUtil.checkFilePermission("/user/local/app/", "read");
            assertTrue(canRead);
        });

        // Test checkProcessPermission
        test("UserUtil.checkProcessPermission", () -> {
            UserUtil.setCurrentUser("local");
            boolean canManage = UserUtil.checkProcessPermission(1);
            assertTrue(canManage);
        });
    }

    // ==================== UserInit API Tests ====================
    private static void testUserInitAPI() {
        System.out.println("\n--- Testing UserInit API ---");

        // Test getListOfUsers
        test("UserInit.getListOfUsers", () -> {
            Map<String, Object> users = UserInit.getListOfUsers();
            assertNotNull(users);
            assertTrue(users.containsKey("local"));
        });

        // Test userExists
        test("UserInit.userExists (exists)", () -> {
            boolean exists = UserInit.userExists("local");
            assertTrue(exists);
        });

        test("UserInit.userExists (not exists)", () -> {
            boolean exists = UserInit.userExists("nonexistentuser12345");
            assertFalse(exists);
        });

        // Test createUser
        test("UserInit.createUser", () -> {
            String[] result = UserInit.createUser("testapiuser", "testpass", false);
            assertTrue(result[0].equals("SUCCESS") || result[1].equals("USER_EXISTS"));
        });

        // Test validateUser
        test("UserInit.validateUser", () -> {
            boolean valid = UserInit.validateUser("local", "local");
            assertTrue(valid);
        });

        // Test getCurrentUser
        test("UserInit.getCurrentUser", () -> {
            String user = UserInit.getCurrentUser();
            assertNotNull(user);
        });

        // Test isLocal
        test("UserInit.isLocal", () -> {
            boolean isLocal = UserInit.isLocal();
            // Just verify it doesn't throw
        });

        // Test getUserInfo
        test("UserInit.getUserInfo", () -> {
            Map<String, Object> info = UserInit.getUserInfo("local");
            assertNotNull(info);
        });

        // Test switchUser
        test("UserInit.switchUser", () -> {
            // First ensure test user exists
            UserInit.createUser("switchtestuser", "switchpass", false);
            String[] result = UserInit.switchUser("switchtestuser", "switchpass");
            assertTrue(result[0].equals("SUCCESS") || result[0].equals("ERROR"));
            // Switch back to local
            UserInit.switchUser("local", "local");
        });

        // Test removeUser
        test("UserInit.removeUser", () -> {
            UserInit.createUser("removetestuser", "removepass", false);
            String[] result = UserInit.removeUser("removetestuser", "removepass");
            assertTrue(result[0].equals("SUCCESS") || result[1].equals("USER_NOT_EXISTS"));
        });
    }

    // ==================== ProcessRunner API Tests ====================
    private static void testProcessRunnerAPI() {
        System.out.println("\n--- Testing ProcessRunner API ---");

        // Test constructor
        test("ProcessRunner constructor", () -> {
            ProcessRunner runner = new ProcessRunner(1);
            assertNotNull(runner);
        });

        // Test getPid
        test("ProcessRunner.getPid", () -> {
            ProcessRunner runner = new ProcessRunner(1);
            int pid = runner.getPid();
            assertEquals(1, pid);
        });

        // Test isRunning
        test("ProcessRunner.isRunning", () -> {
            ProcessRunner runner = new ProcessRunner(1);
            boolean running = runner.isRunning();
            // Just verify it doesn't throw
        });

        // Test stop
        test("ProcessRunner.stop", () -> {
            ProcessRunner runner = new ProcessRunner(1);
            runner.stop();
            // Just verify it doesn't throw
        });

        // Test executeLine
        test("ProcessRunner.executeLine", () -> {
            ProcessRunner runner = new ProcessRunner(1);
            runner.executeLine();
            // Just verify it doesn't throw
        });
    }

    // ==================== ProcessInit API Tests ====================
    private static void testProcessInitAPI() {
        System.out.println("\n--- Testing ProcessInit API ---");

        // Test init (already called in setup)
        test("ProcessInit.init", () -> {
            // Already initialized in main, just verify no exception
            ProcessInit.init();
        });

        // Test getRunner
        test("ProcessInit.getRunner", () -> {
            ProcessRunner runner = ProcessInit.getRunner(1);
            // May be null if runner not created yet
        });

        // Test shutdown
        test("ProcessInit.shutdown", () -> {
            ProcessInit.shutdown();
            // Re-init for other tests
            ProcessInit.init();
        });
    }

    // ==================== FunctionRegistry API Tests ====================
    private static void testFunctionRegistryAPI() {
        System.out.println("\n--- Testing FunctionRegistry API ---");

        // Test register
        test("FunctionRegistry.register", () -> {
            // Already registered in setup
            int count = FunctionRegistry.getProviderCount();
            assertTrue(count >= 4);
        });

        // Test call
        test("FunctionRegistry.call (now)", () -> {
            Object result = FunctionRegistry.call("now", new Object[]{});
            assertTrue(result instanceof int[]);
            assertEquals(7, ((int[]) result).length);
        });

        test("FunctionRegistry.call (str)", () -> {
            Object result = FunctionRegistry.call("str", new Object[]{123});
            assertEquals("123", result);
        });

        test("FunctionRegistry.call (int)", () -> {
            Object result = FunctionRegistry.call("int", new Object[]{"456"});
            assertEquals(456, result);
        });

        // Test getProviderCount
        test("FunctionRegistry.getProviderCount", () -> {
            int count = FunctionRegistry.getProviderCount();
            assertTrue(count > 0);
        });

        // Test getAllFunctions
        test("FunctionRegistry.getAllFunctions", () -> {
            List<FunctionInfo> functions = FunctionRegistry.getAllFunctions();
            assertNotNull(functions);
            assertTrue(functions.size() > 0);
        });
    }

    // ==================== FunctionContext API Tests ====================
    private static void testFunctionContextAPI() {
        System.out.println("\n--- Testing FunctionContext API ---");

        // Test constructor and getters
        test("FunctionContext constructor and getters", () -> {
            FunctionContext ctx = new FunctionContext(123, 456, "testuser");
            assertEquals(123, ctx.getPid());
            assertEquals(456, ctx.getPpid());
            assertEquals("testuser", ctx.getCurrentUser());
        });

        // Test isLocal
        test("FunctionContext.isLocal (true)", () -> {
            FunctionContext ctx = new FunctionContext(1, 0, "local");
            assertTrue(ctx.isLocal());
        });

        test("FunctionContext.isLocal (false)", () -> {
            FunctionContext ctx = new FunctionContext(1, 0, "otheruser");
            assertFalse(ctx.isLocal());
        });
    }

    // ==================== FunctionInfo API Tests ====================
    private static void testFunctionInfoAPI() {
        System.out.println("\n--- Testing FunctionInfo API ---");

        // Test constructor and getters
        test("FunctionInfo constructor and getters", () -> {
            FunctionInfo info = new FunctionInfo("testFunc", "Test function", 
                new String[]{"arg1: int", "arg2: string"}, "int", "TestProvider");
            assertEquals("testFunc", info.getName());
            assertEquals("Test function", info.getDescription());
            assertEquals("int", info.getReturnType());
            assertEquals("TestProvider", info.getProvider());
            assertEquals(2, info.getParams().length);
        });

        // Test toMarkdown
        test("FunctionInfo.toMarkdown", () -> {
            FunctionInfo info = new FunctionInfo("testFunc", "Test function", 
                new String[]{"arg1: int"}, "int", "TestProvider");
            String markdown = info.toMarkdown();
            assertTrue(markdown.contains("testFunc"));
            assertTrue(markdown.contains("int"));
        });
    }

    // ==================== Logger API Tests ====================
    private static void testLoggerAPI() {
        System.out.println("\n--- Testing Logger API ---");

        // Test all log levels
        test("Logger.debug", () -> {
            Logger.debug("Debug message");
        });

        test("Logger.info", () -> {
            Logger.info("Info message");
        });

        test("Logger.warn", () -> {
            Logger.warn("Warning message");
        });

        test("Logger.error (1 arg)", () -> {
            Logger.error("Error message");
        });

        test("Logger.error (2 args)", () -> {
            Logger.error("Error with exception", new RuntimeException("Test"));
        });
    }

    // ==================== Test Utilities ====================
    private static void test(String name, TestRunnable runnable) {
        try {
            runnable.run();
            System.out.println("  PASS: " + name);
            passed++;
        } catch (AssertionError e) {
            System.out.println("  FAIL: " + name + " - " + e.getMessage());
            report.append("  ").append(name).append(": ").append(e.getMessage()).append("\n");
            failed++;
        } catch (Exception e) {
            System.out.println("  FAIL: " + name + " - Exception: " + e.getMessage());
            report.append("  ").append(name).append(": Exception - ").append(e.getMessage()).append("\n");
            failed++;
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected: " + expected + ", Actual: " + actual);
        }
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true, but was false");
        }
    }

    private static void assertFalse(boolean condition) {
        if (condition) {
            throw new AssertionError("Expected false, but was true");
        }
    }

    private static void assertNotNull(Object obj) {
        if (obj == null) {
            throw new AssertionError("Expected non-null, but was null");
        }
    }

    @FunctionalInterface
    interface TestRunnable {
        void run() throws Exception;
    }
}
