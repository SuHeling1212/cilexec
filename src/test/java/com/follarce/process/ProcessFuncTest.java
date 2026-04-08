package com.follarce.process;

import com.follarce.basicUtil.*;
import com.follarce.init.FileInit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessFuncTest {

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

    @Test
    @Order(1)
    @DisplayName("ThreadLocal隔离 - 不同线程的PID相互独立")
    void testThreadLocalIsolation_DifferentThreads() throws Exception {
        int pid1 = createTestProcess("x = 1");
        int pid2 = createTestProcess("y = 2");

        ProcessFunc.setCurrentPid(pid1);
        assertEquals(pid1, ProcessFunc.getPID());

        ProcessFunc.setCurrentPid(pid2);
        assertEquals(pid2, ProcessFunc.getPID());

        Thread thread1 = new Thread(() -> {
            ProcessFunc.setCurrentPid(pid1);
            assertEquals(pid1, ProcessFunc.getPID());
        });

        Thread thread2 = new Thread(() -> {
            ProcessFunc.setCurrentPid(pid2);
            assertEquals(pid2, ProcessFunc.getPID());
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();

        assertEquals(pid2, ProcessFunc.getPID());
    }

    @Test
    @Order(2)
    @DisplayName("ThreadLocal隔离 - 多线程并发修改PID")
    void testThreadLocalIsolation_MultipleThreadsConcurrent() throws Exception {
        int numThreads = 10;
        Thread[] threads = new Thread[numThreads];
        AtomicInteger[] observedPids = new AtomicInteger[numThreads];
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadIndex = i;
            final int expectedPid = 100 + i;
            observedPids[i] = new AtomicInteger(-1);

            threads[i] = new Thread(() -> {
                try {
                    startLatch.await();
                    ProcessFunc.setCurrentPid(expectedPid);
                    Thread.sleep(10);
                    observedPids[threadIndex].set(ProcessFunc.getPID());
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
            threads[i].start();
        }

        startLatch.countDown();
        doneLatch.await();

        for (int i = 0; i < numThreads; i++) {
            assertEquals(100 + i, observedPids[i].get(),
                "Thread " + i + " observed wrong PID");
        }
    }

    @Test
    @Order(3)
    @DisplayName("ThreadLocal隔离 - 主线程默认值")
    void testThreadLocalIsolation_DefaultValue() {
        ProcessFunc.setCurrentPid(999);
        assertEquals(999, ProcessFunc.getPID());

        ProcessFunc.setCurrentPid(1);
        assertEquals(1, ProcessFunc.getPID());
    }

    @Test
    @Order(4)
    @DisplayName("getPID和setCurrentPid - 基本功能测试")
    void testGetSetCurrentPid_BasicFunctionality() {
        ProcessFunc.setCurrentPid(100);
        assertEquals(100, ProcessFunc.getPID());

        ProcessFunc.setCurrentPid(200);
        assertEquals(200, ProcessFunc.getPID());

        ProcessFunc.setCurrentPid(1);
        assertEquals(1, ProcessFunc.getPID());
    }

    @Test
    @Order(5)
    @DisplayName("getPID和setCurrentPid - 线程内持久性")
    void testGetSetCurrentPid_ThreadPersistence() throws Exception {
        ProcessFunc.setCurrentPid(100);

        Thread thread = new Thread(() -> {
            assertEquals(1, ProcessFunc.getPID());
            ProcessFunc.setCurrentPid(200);
            assertEquals(200, ProcessFunc.getPID());
        });

        thread.start();
        thread.join();

        assertEquals(100, ProcessFunc.getPID());
    }

    @Test
    @Order(6)
    @DisplayName("fork - 传递PID参数创建子进程")
    void testFork_WithPidParameter() throws Exception {
        int parentPid = createTestProcess("x = 1\nfork()");
        ProcessFunc.setCurrentPid(parentPid);

        int childPid = ProcessFunc.fork(parentPid);
        assertTrue(childPid > 0, "Child PID should be positive");

        String[] childResult = FileUtil.read("/system/process/" + childPid + ".json");
        assertEquals("SUCCESS", childResult[0]);

        Map<String, Object> childProcess = (Map<String, Object>) JsonUtil.readJson(childResult[1]);
        assertEquals(childPid, ((Number) childProcess.get("PID")).intValue());
        assertEquals(parentPid, ((Number) ((Map<?, ?>) childProcess.get("Parent")).get("PID")).intValue());
    }

    @Test
    @Order(7)
    @DisplayName("fork - 子进程代码行从fork后继续")
    void testFork_ChildContinuesAfterFork() throws Exception {
        String script = "x = 1\nfork()\ny = 2";
        int parentPid = createTestProcess(script);
        ProcessFunc.setCurrentPid(parentPid);

        int childPid = ProcessFunc.fork(parentPid);
        assertTrue(childPid > 0);

        String[] parentResult = FileUtil.read("/system/process/" + parentPid + ".json");
        Map<String, Object> parentProcess = (Map<String, Object>) JsonUtil.readJson(parentResult[1]);
        Map<String, Object> parentProgram = (Map<String, Object>) parentProcess.get("Program");
        Map<String, Object> parentCode = (Map<String, Object>) parentProgram.get("Code");
        int parentRunningLine = ((Number) parentCode.get("runningCodeLine")).intValue();

        String[] childResult = FileUtil.read("/system/process/" + childPid + ".json");
        Map<String, Object> childProcess = (Map<String, Object>) JsonUtil.readJson(childResult[1]);
        Map<String, Object> childProgram = (Map<String, Object>) childProcess.get("Program");
        Map<String, Object> childCode = (Map<String, Object>) childProgram.get("Code");
        int childRunningLine = ((Number) childCode.get("runningCodeLine")).intValue();

        assertEquals(parentRunningLine + 1, childRunningLine,
            "Child should continue from line after fork()");
    }

    @Test
    @Order(8)
    @DisplayName("fork - 父进程文件Child列表更新")
    void testFork_ParentChildListUpdated() throws Exception {
        int parentPid = createTestProcess("x = 1\nfork()");
        ProcessFunc.setCurrentPid(parentPid);

        int childPid = ProcessFunc.fork(parentPid);
        assertTrue(childPid > 0);

        String[] parentResult = FileUtil.read("/system/process/" + parentPid + ".json");
        Map<String, Object> parentProcess = (Map<String, Object>) JsonUtil.readJson(parentResult[1]);
        Map<String, Object> children = (Map<String, Object>) parentProcess.get("Child");

        assertNotNull(children);
        assertTrue(children.containsKey(String.valueOf(childPid)));
    }

    @Test
    @Order(9)
    @DisplayName("fork - 使用当前PID重载版本")
    void testFork_DefaultPidVersion() throws Exception {
        int parentPid = createTestProcess("x = 1\nfork()");
        ProcessFunc.setCurrentPid(parentPid);

        int childPid = ProcessFunc.fork();
        assertTrue(childPid > 0);

        String[] childResult = FileUtil.read("/system/process/" + childPid + ".json");
        assertEquals("SUCCESS", childResult[0]);
    }

    @Test
    @Order(10)
    @DisplayName("fork - 父进程不存在时返回-1")
    void testFork_ParentNotExists() {
        ProcessFunc.setCurrentPid(99999);
        int result = ProcessFunc.fork(99999);
        assertEquals(-1, result);
    }

    @Test
    @Order(11)
    @DisplayName("allocatePid - 分配新PID递增")
    void testAllocatePid_IncrementalAllocation() throws Exception {
        int pid1 = createTestProcess("x = 1");
        int pid2 = createTestProcess("y = 2");
        int pid3 = createTestProcess("z = 3");

        ProcessFunc.setCurrentPid(pid1);
        int childPid1 = ProcessFunc.fork(pid1);
        ProcessFunc.setCurrentPid(pid2);
        int childPid2 = ProcessFunc.fork(pid2);

        assertTrue(childPid1 > pid3, "First child PID should be greater than existing max");
        assertTrue(childPid2 > childPid1, "Second child PID should be greater than first child");
    }

    @Test
    @Order(12)
    @DisplayName("call方法 - 设置正确的currentPid")
    void testCall_SetsCorrectCurrentPid() throws Exception {
        int parentPid = createTestProcess("x = 1");
        ProcessFunc.setCurrentPid(parentPid);

        Object result = ProcessFunc.call("getPID", new Object[]{}, parentPid);
        assertEquals(parentPid, result);

        int childPid = ProcessFunc.fork(parentPid);
        result = ProcessFunc.call("getPID", new Object[]{}, childPid);
        assertEquals(childPid, result);
    }
}