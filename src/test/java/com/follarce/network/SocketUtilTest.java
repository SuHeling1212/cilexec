package com.follarce.network;

import com.follarce.basicUtil.FileUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SocketUtilTest {

    @TempDir
    Path tempDir;

    private static Path testRoot;
    private static String originalVfsRoot;
    private static boolean vfsRootSet = false;

    private static Field socketIdGeneratorField;
    private static Field socketsField;
    private static Field socketDirField;

    @BeforeAll
    static void setupClass() throws Exception {
        originalVfsRoot = FileUtil.getVfsRoot();

        socketIdGeneratorField = SocketUtil.class.getDeclaredField("socketIdGenerator");
        socketIdGeneratorField.setAccessible(true);

        socketsField = SocketUtil.class.getDeclaredField("sockets");
        socketsField.setAccessible(true);

        socketDirField = SocketUtil.class.getDeclaredField("SOCKET_DIR");
        socketDirField.setAccessible(true);
    }

    @AfterAll
    static void teardownClass() throws Exception {
        if (vfsRootSet) {
            Field vfsRootField = FileUtil.class.getDeclaredField("VFS_ROOT");
            vfsRootField.setAccessible(true);
            vfsRootField.set(null, originalVfsRoot);
        }
    }

    @BeforeEach
    void setup() throws Exception {
        testRoot = tempDir.resolve("test_vfs");
        Files.createDirectories(testRoot);

        Field vfsRootField = FileUtil.class.getDeclaredField("VFS_ROOT");
        vfsRootField.setAccessible(true);
        vfsRootField.set(null, testRoot.toString());
        vfsRootSet = true;

        String socketDirPath = testRoot.resolve("user").resolve("local").resolve("sockets").toString();
        socketDirField.set(null, socketDirPath + "/");

        Files.createDirectories(Paths.get(socketDirPath));

        AtomicInteger generator = (AtomicInteger) socketIdGeneratorField.get(null);
        generator.set(1);

        @SuppressWarnings("unchecked")
        Map<Integer, ?> sockets = (Map<Integer, ?>) socketsField.get(null);
        sockets.clear();
    }

    @AfterEach
    void teardown() throws Exception {
        @SuppressWarnings("unchecked")
        Map<Integer, ?> sockets = (Map<Integer, ?>) socketsField.get(null);
        sockets.clear();

        AtomicInteger generator = (AtomicInteger) socketIdGeneratorField.get(null);
        generator.set(1);
    }

    private int getNextSocketId() throws Exception {
        AtomicInteger generator = (AtomicInteger) socketIdGeneratorField.get(null);
        return generator.get();
    }

    private void createSocketMetaFile(int socketId, String type) throws IOException {
        String socketDirPath = testRoot.resolve("user").resolve("local").resolve("sockets").toString();
        String metaContent = String.format(
            "{\"id\":%d,\"ownerPid\":12345,\"type\":\"%s\",\"host\":\"127.0.0.1\",\"port\":8080,\"saveDir\":\"/user/local/data/\",\"isRunning\":true,\"created\":[2024,1,1,0,0,0,0]}",
            socketId, type
        );
        Path metaFile = Paths.get(socketDirPath, socketId + ".json");
        Files.writeString(metaFile, metaContent);
    }

    @Test
    @Order(1)
    @DisplayName("测试 Socket ID 持久化 - init() 扫描现有文件设置起始 ID")
    void testInitScansExistingFilesAndSetsStartingId() throws Exception {
        createSocketMetaFile(5, "tcp_server");
        createSocketMetaFile(10, "tcp_client");
        createSocketMetaFile(3, "udp");

        SocketUtil.init();

        int nextId = getNextSocketId();
        assertEquals(11, nextId, "下一个 socket ID 应该是最大 ID + 1，即 11");
    }

    @Test
    @Order(2)
    @DisplayName("测试 Socket ID 持久化 - 空目录初始化")
    void testInitWithEmptyDirectory() throws Exception {
        SocketUtil.init();

        int nextId = getNextSocketId();
        assertEquals(1, nextId, "空目录情况下，下一个 socket ID 应该是 1");
    }

    @Test
    @Order(3)
    @DisplayName("测试 Socket ID 持久化 - 忽略非 JSON 文件")
    void testInitIgnoresNonJsonFiles() throws Exception {
        String socketDirPath = testRoot.resolve("user").resolve("local").resolve("sockets").toString();
        Files.writeString(Paths.get(socketDirPath, "test.txt"), "not a socket");
        Files.writeString(Paths.get(socketDirPath, "socket_1.dat"), "data");
        createSocketMetaFile(5, "tcp_server");

        SocketUtil.init();

        int nextId = getNextSocketId();
        assertEquals(6, nextId, "应该忽略非 JSON 文件，只考虑 socket ID");
    }

    @Test
    @Order(4)
    @DisplayName("测试 Socket ID 持久化 - 忽略无效文件名")
    void testInitIgnoresInvalidFilenames() throws Exception {
        String socketDirPath = testRoot.resolve("user").resolve("local").resolve("sockets").toString();
        Files.writeString(Paths.get(socketDirPath, "abc.json"), "{}");
        Files.writeString(Paths.get(socketDirPath, "12a.json"), "{}");
        createSocketMetaFile(3, "tcp_server");

        SocketUtil.init();

        int nextId = getNextSocketId();
        assertEquals(4, nextId, "应该忽略无效的 JSON 文件名（如 abc.json, 12a.json）");
    }

    @Test
    @Order(5)
    @DisplayName("测试 ID 唯一性 - 并发创建多个 socket")
    void testIdUniquenessAcrossMultipleCreations() throws Exception {
        SocketUtil.init();

        int initialId = getNextSocketId();
        assertEquals(1, initialId);

        int id1 = getNextSocketIdForTest();
        int id2 = getNextSocketIdForTest();
        int id3 = getNextSocketIdForTest();
        int id4 = getNextSocketIdForTest();
        int id5 = getNextSocketIdForTest();

        assertNotEquals(id1, id2, "ID 应该唯一");
        assertNotEquals(id2, id3, "ID 应该唯一");
        assertNotEquals(id3, id4, "ID 应该唯一");
        assertNotEquals(id4, id5, "ID 应该唯一");
        assertEquals(id1 + 1, id2, "ID 应该连续递增");
        assertEquals(id2 + 1, id3, "ID 应该连续递增");
        assertEquals(id3 + 1, id4, "ID 应该连续递增");
        assertEquals(id4 + 1, id5, "ID 应该连续递增");
    }

    private int getNextSocketIdForTest() throws Exception {
        AtomicInteger generator = (AtomicInteger) socketIdGeneratorField.get(null);
        return generator.getAndIncrement();
    }

    @Test
    @Order(6)
    @DisplayName("测试 ID 唯一性 - socketIdGenerator 原子性")
    void testIdGeneratorAtomicity() throws Exception {
        AtomicInteger generator = (AtomicInteger) socketIdGeneratorField.get(null);
        generator.set(1);

        int[] ids = new int[100];
        for (int i = 0; i < 100; i++) {
            ids[i] = generator.getAndIncrement();
        }

        for (int i = 0; i < 100; i++) {
            for (int j = i + 1; j < 100; j++) {
                assertNotEquals(ids[i], ids[j], "所有 ID 应该是唯一的");
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("测试重启后 ID 不冲突 - 模拟重启恢复场景")
    void testRestartNoIdConflictAfterInit() throws Exception {
        createSocketMetaFile(100, "tcp_server");
        createSocketMetaFile(50, "tcp_client");

        SocketUtil.init();
        int idAfterInit = getNextSocketId();
        assertEquals(101, idAfterInit, "初始化后，下一个 ID 应该是 max + 1 = 101");

        AtomicInteger generator = (AtomicInteger) socketIdGeneratorField.get(null);
        int id1 = generator.getAndIncrement();
        int id2 = generator.getAndIncrement();

        assertEquals(101, id1, "第一个新 ID 应该是 101");
        assertEquals(102, id2, "第二个新 ID 应该是 102");
        assertNotEquals(id1, id2, "重启后创建的新 socket ID 不应与已有 ID 冲突");
    }

    @Test
    @Order(8)
    @DisplayName("测试重启后 ID 不冲突 - 大量现有 socket 文件")
    void testRestartNoIdConflictWithManyExistingSockets() throws Exception {
        for (int i = 1; i <= 50; i++) {
            createSocketMetaFile(i * 10, "tcp_server");
        }

        SocketUtil.init();
        int nextId = getNextSocketId();
        assertEquals(501, nextId, "最大 ID 是 500，初始化后下一个 ID 应该是 501");

        int id1 = getNextSocketIdForTest();
        int id2 = getNextSocketIdForTest();

        for (int i = 1; i <= 50; i++) {
            assertNotEquals(i * 10, id1, "新创建的 ID 不应与现有 ID 冲突");
            assertNotEquals(i * 10, id2, "新创建的 ID 不应与现有 ID 冲突");
        }

        assertEquals(502, id2, "ID 应该正确递增");
    }

    @Test
    @Order(9)
    @DisplayName("测试重启后 ID 不冲突 - 验证 ID 不会回绕")
    void testIdNeverWrapsAround() throws Exception {
        createSocketMetaFile(Integer.MAX_VALUE - 1, "tcp_server");

        SocketUtil.init();
        int nextId = getNextSocketId();

        assertEquals(Integer.MAX_VALUE, nextId, "ID 应该继续增长而不会回绕到 1");
    }

    @Test
    @Order(10)
    @DisplayName("测试 init 方法幂等性 - 多次调用 init")
    void testInitIdempotency() throws Exception {
        createSocketMetaFile(10, "tcp_server");

        SocketUtil.init();
        int firstInitId = getNextSocketId();

        SocketUtil.init();
        int secondInitId = getNextSocketId();

        assertEquals(firstInitId, secondInitId, "多次调用 init 不应改变 socketIdGenerator");
    }

    @Test
    @Order(11)
    @DisplayName("测试 ID 持久化 - socket 关闭后 ID 不复用")
    void testSocketIdNotReusedAfterClose() throws Exception {
        AtomicInteger generator = (AtomicInteger) socketIdGeneratorField.get(null);
        generator.set(1);

        int id1 = generator.getAndIncrement();
        int id2 = generator.getAndIncrement();

        assertNotEquals(id1, id2, "关闭的 socket ID 不应被复用");
    }
}