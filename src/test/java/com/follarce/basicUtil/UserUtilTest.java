package com.follarce.basicUtil;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserUtilTest {

    @TempDir
    Path tempDir;

    private static Path testRoot;
    private static String originalVfsRoot = null;
    private static String originalCurrentUser = null;

    @BeforeAll
    static void setupClass() throws Exception {
        try {
            Field vfsRootField = FileUtil.class.getDeclaredField("VFS_ROOT");
            vfsRootField.setAccessible(true);
            originalVfsRoot = (String) vfsRootField.get(null);
        } catch (Exception e) {
            originalVfsRoot = null;
        }
    }

    @AfterAll
    static void teardownClass() throws Exception {
        if (originalVfsRoot != null) {
            Field vfsRootField = FileUtil.class.getDeclaredField("VFS_ROOT");
            vfsRootField.setAccessible(true);
            vfsRootField.set(null, originalVfsRoot);
        }
        UserUtil.setCurrentUser("local");
    }

    @BeforeEach
    void setup() throws Exception {
        testRoot = tempDir.resolve("test_vfs");
        Files.createDirectories(testRoot);

        Field vfsRootField = FileUtil.class.getDeclaredField("VFS_ROOT");
        vfsRootField.setAccessible(true);
        vfsRootField.set(null, testRoot.toString());

        originalCurrentUser = UserUtil.getCurrentUser();
        UserUtil.setCurrentUser("local");
    }

    @AfterEach
    void teardown() {
        UserUtil.setCurrentUser("local");
    }

    @Test
    @Order(1)
    @DisplayName("测试 setCurrentUser 和 getCurrentUser")
    void testSetAndGetCurrentUser() {
        assertEquals("local", UserUtil.getCurrentUser());

        UserUtil.setCurrentUser("testuser");
        assertEquals("testuser", UserUtil.getCurrentUser());

        UserUtil.setCurrentUser("admin");
        assertEquals("admin", UserUtil.getCurrentUser());

        UserUtil.setCurrentUser("local");
        assertEquals("local", UserUtil.getCurrentUser());
    }

    @Test
    @Order(2)
    @DisplayName("测试 isLocal - 本地用户")
    void testIsLocalTrue() {
        UserUtil.setCurrentUser("local");
        assertTrue(UserUtil.isLocal());
    }

    @Test
    @Order(3)
    @DisplayName("测试 isLocal - 非本地用户")
    void testIsLocalFalse() {
        UserUtil.setCurrentUser("remote_user");
        assertFalse(UserUtil.isLocal());

        UserUtil.setCurrentUser("admin");
        assertFalse(UserUtil.isLocal());

        UserUtil.setCurrentUser("Local");
        assertFalse(UserUtil.isLocal());
    }

    @Test
    @Order(4)
    @DisplayName("测试 PermissionResult.success()")
    void testPermissionResultSuccess() {
        UserUtil.PermissionResult result = UserUtil.PermissionResult.success();

        assertTrue(result.isSuccess());
        assertNull(result.getErrorMessage());
        assertNull(result.getErrorContext());
    }

    @Test
    @Order(5)
    @DisplayName("测试 PermissionResult.failure()")
    void testPermissionResultFailure() {
        UserUtil.PermissionResult result = UserUtil.PermissionResult.failure(
            "Permission denied", 
            "File: /test.txt"
        );

        assertFalse(result.isSuccess());
        assertEquals("Permission denied", result.getErrorMessage());
        assertEquals("File: /test.txt", result.getErrorContext());
    }

    @Test
    @Order(6)
    @DisplayName("测试 PermissionResult.toString() - 成功")
    void testPermissionResultToStringSuccess() {
        UserUtil.PermissionResult result = UserUtil.PermissionResult.success();
        assertEquals("PermissionResult[SUCCESS]", result.toString());
    }

    @Test
    @Order(7)
    @DisplayName("测试 PermissionResult.toString() - 失败")
    void testPermissionResultToStringFailure() {
        UserUtil.PermissionResult result = UserUtil.PermissionResult.failure(
            "Access denied", 
            "Context info"
        );
        String str = result.toString();
        assertTrue(str.contains("FAILED"));
        assertTrue(str.contains("Access denied"));
        assertTrue(str.contains("Context info"));
    }

    @Test
    @Order(8)
    @DisplayName("测试 checkFilePermission - 本地用户始终有权限")
    void testCheckFilePermissionLocalUser() throws IOException {
        UserUtil.setCurrentUser("local");

        assertTrue(UserUtil.checkFilePermission("/any/path", "read"));
        assertTrue(UserUtil.checkFilePermission("/any/path", "write"));
        assertTrue(UserUtil.checkFilePermission("/any/path", "execute"));
    }

    @Test
    @Order(9)
    @DisplayName("测试 validatePermission - 本地用户返回成功")
    void testValidatePermissionLocalUser() {
        UserUtil.setCurrentUser("local");

        UserUtil.PermissionResult result = UserUtil.validatePermission("/any/path", "read");
        assertTrue(result.isSuccess());
        assertNull(result.getErrorMessage());
    }

    @Test
    @Order(10)
    @DisplayName("测试 validatePermission - 非本地用户文件不存在")
    void testValidatePermissionFileNotExist() {
        UserUtil.setCurrentUser("remote_user");

        UserUtil.PermissionResult result = UserUtil.validatePermission("/nonexistent/file.txt", "read");
        assertFalse(result.isSuccess());
        assertEquals("File does not exist", result.getErrorMessage());
    }

    @Test
    @Order(11)
    @DisplayName("测试 validatePermission - 非本地用户有权限")
    void testValidatePermissionWithPermission() throws IOException {
        UserUtil.setCurrentUser("testowner");

        Path filePath = testRoot.resolve("testfile.txt");
        createTestFileWithOwner(filePath, "testowner", "read, write", "read");

        UserUtil.PermissionResult result = UserUtil.validatePermission("/testfile.txt", "read");
        assertTrue(result.isSuccess());
    }

    @Test
    @Order(12)
    @DisplayName("测试 validatePermission - 非本地用户无权限")
    void testValidatePermissionWithoutPermission() throws IOException {
        UserUtil.setCurrentUser("otheruser");

        Path filePath = testRoot.resolve("testfile.txt");
        createTestFileWithOwner(filePath, "testowner", "read, write", "read");

        UserUtil.PermissionResult result = UserUtil.validatePermission("/testfile.txt", "write");
        assertFalse(result.isSuccess());
        assertEquals("Others permission denied", result.getErrorMessage());
    }

    @Test
    @Order(13)
    @DisplayName("测试 validatePermission - 所有者权限")
    void testValidatePermissionOwner() throws IOException {
        UserUtil.setCurrentUser("owner");

        Path filePath = testRoot.resolve("testfile.txt");
        createTestFileWithOwner(filePath, "owner", "read, write, execute", "read");

        UserUtil.PermissionResult readResult = UserUtil.validatePermission("/testfile.txt", "read");
        assertTrue(readResult.isSuccess());

        UserUtil.PermissionResult writeResult = UserUtil.validatePermission("/testfile.txt", "write");
        assertTrue(writeResult.isSuccess());

        UserUtil.PermissionResult executeResult = UserUtil.validatePermission("/testfile.txt", "execute");
        assertTrue(executeResult.isSuccess());
    }

    @Test
    @Order(14)
    @DisplayName("测试 validatePermission - 其他用户权限")
    void testValidatePermissionOthers() throws IOException {
        UserUtil.setCurrentUser("otheruser");

        Path filePath = testRoot.resolve("testfile.txt");
        createTestFileWithOwner(filePath, "owner", "read, write", "read");

        UserUtil.PermissionResult readResult = UserUtil.validatePermission("/testfile.txt", "read");
        assertTrue(readResult.isSuccess());

        UserUtil.PermissionResult writeResult = UserUtil.validatePermission("/testfile.txt", "write");
        assertFalse(writeResult.isSuccess());
    }

    @Test
    @Order(15)
    @DisplayName("测试 checkProcessPermission - 本地用户")
    void testCheckProcessPermissionLocalUser() {
        UserUtil.setCurrentUser("local");

        assertTrue(UserUtil.checkProcessPermission(1));
        assertTrue(UserUtil.checkProcessPermission(999));
    }

    @Test
    @Order(16)
    @DisplayName("测试 validateProcessPermission - 本地用户返回成功")
    void testValidateProcessPermissionLocalUser() {
        UserUtil.setCurrentUser("local");

        UserUtil.PermissionResult result = UserUtil.validateProcessPermission(123);
        assertTrue(result.isSuccess());
    }

    @Test
    @Order(17)
    @DisplayName("测试 validateProcessPermission - 非本地用户进程文件不存在")
    void testValidateProcessPermissionFileNotExist() {
        UserUtil.setCurrentUser("remote_user");

        UserUtil.PermissionResult result = UserUtil.validateProcessPermission(999);
        assertFalse(result.isSuccess());
        assertEquals("Failed to read process file", result.getErrorMessage());
    }

    @Test
    @Order(18)
    @DisplayName("测试 validateProcessPermission - 非本地用户有权限")
    void testValidateProcessPermissionWithPermission() throws IOException {
        UserUtil.setCurrentUser("processowner");

        Path systemDir = testRoot.resolve("system");
        Path processDir = systemDir.resolve("process");
        Files.createDirectories(processDir);
        createDirectoryMetaWithPermission(systemDir, "local", "read, write", "read, write");
        createDirectoryMetaWithPermission(processDir, "local", "read, write", "read, write");
        
        Path processFile = processDir.resolve("123.json");
        createProcessFileWithOwner(processFile, "processowner");

        UserUtil.PermissionResult result = UserUtil.validateProcessPermission(123);
        assertTrue(result.isSuccess());
    }

    @Test
    @Order(19)
    @DisplayName("测试 validateProcessPermission - 非本地用户无权限")
    void testValidateProcessPermissionWithoutPermission() throws IOException {
        UserUtil.setCurrentUser("otheruser");

        Path systemDir = testRoot.resolve("system");
        Path processDir = systemDir.resolve("process");
        Files.createDirectories(processDir);
        createDirectoryMetaWithPermission(systemDir, "local", "read, write", "read, write");
        createDirectoryMetaWithPermission(processDir, "local", "read, write", "read, write");
        
        Path processFile = processDir.resolve("456.json");
        createProcessFileWithOwner(processFile, "processowner");

        UserUtil.PermissionResult result = UserUtil.validateProcessPermission(456);
        assertFalse(result.isSuccess());
        assertEquals("Process ownership mismatch", result.getErrorMessage());
    }

    @Test
    @Order(20)
    @DisplayName("测试 validateProcessPermission - 进程文件缺少所有者信息")
    void testValidateProcessPermissionMissingOwner() throws IOException {
        UserUtil.setCurrentUser("testuser");

        Path systemDir = testRoot.resolve("system");
        Path processDir = systemDir.resolve("process");
        Files.createDirectories(processDir);
        createDirectoryMetaWithPermission(systemDir, "local", "read, write", "read, write");
        createDirectoryMetaWithPermission(processDir, "local", "read, write", "read, write");
        
        Path processFile = processDir.resolve("789.json");
        String content = "{\"pid\": 789}";
        String metaJson = "{\"Owner\": \"testuser\", \"Permission\": {\"Owner\": \"read, write\", \"Others\": \"read\"}}";
        String fileContent = "#<META>\n" + metaJson + "\n<META>#\n" + content;
        Files.writeString(processFile, fileContent);

        UserUtil.PermissionResult result = UserUtil.validateProcessPermission(789);
        assertFalse(result.isSuccess());
        assertEquals("Process owner information missing", result.getErrorMessage());
    }

    @Test
    @Order(21)
    @DisplayName("测试多线程用户隔离")
    void testThreadLocalUserIsolation() throws InterruptedException {
        UserUtil.setCurrentUser("mainUser");
        assertEquals("mainUser", UserUtil.getCurrentUser());

        Thread thread = new Thread(() -> {
            assertEquals("local", UserUtil.getCurrentUser());
            UserUtil.setCurrentUser("threadUser");
            assertEquals("threadUser", UserUtil.getCurrentUser());
        });

        thread.start();
        thread.join();

        assertEquals("mainUser", UserUtil.getCurrentUser());
    }

    @Test
    @Order(22)
    @DisplayName("测试 PermissionResult 构造函数")
    void testPermissionResultConstructor() {
        UserUtil.PermissionResult result = new UserUtil.PermissionResult(false, "Error", "Context");

        assertFalse(result.isSuccess());
        assertEquals("Error", result.getErrorMessage());
        assertEquals("Context", result.getErrorContext());
    }

    @Test
    @Order(23)
    @DisplayName("测试 validatePermission - 缺少权限元数据")
    void testValidatePermissionMissingPermissionMetadata() throws IOException {
        UserUtil.setCurrentUser("testuser");

        Path filePath = testRoot.resolve("testfile.txt");
        String metaJson = "{\"Owner\": \"owner\"}";
        String fileContent = "#<META>\n" + metaJson + "\n<META>#\ncontent";
        Files.writeString(filePath, fileContent);

        UserUtil.PermissionResult result = UserUtil.validatePermission("/testfile.txt", "read");
        assertFalse(result.isSuccess());
        assertEquals("Permission metadata missing", result.getErrorMessage());
    }

    @Test
    @Order(24)
    @DisplayName("测试 validatePermission - 所有者权限不足")
    void testValidatePermissionOwnerInsufficientPermission() throws IOException {
        UserUtil.setCurrentUser("owner");

        Path filePath = testRoot.resolve("testfile.txt");
        createTestFileWithOwner(filePath, "owner", "read", "read");

        UserUtil.PermissionResult result = UserUtil.validatePermission("/testfile.txt", "write");
        assertFalse(result.isSuccess());
        assertEquals("Owner permission denied", result.getErrorMessage());
    }

    @Test
    @Order(25)
    @DisplayName("测试 validatePermission - 无效元数据格式")
    void testValidatePermissionInvalidMetadata() throws IOException {
        UserUtil.setCurrentUser("testuser");

        Path filePath = testRoot.resolve("testfile.txt");
        String fileContent = "content without metadata";
        Files.writeString(filePath, fileContent);

        UserUtil.PermissionResult result = UserUtil.validatePermission("/testfile.txt", "read");
        assertFalse(result.isSuccess());
        assertEquals("Failed to extract metadata", result.getErrorMessage());
    }

    private void createTestFileWithOwner(Path filePath, String owner, String ownerPerm, String othersPerm) throws IOException {
        String metaJson = String.format(
            "{\"Owner\": \"%s\", \"Permission\": {\"Owner\": \"%s\", \"Others\": \"%s\"}, \"locked\": {\"isLocked\": false}}",
            owner, ownerPerm, othersPerm
        );
        String fileContent = "#<META>\n" + metaJson + "\n<META>#\ntest content";
        Files.writeString(filePath, fileContent);
    }

    private void createProcessFile(Path filePath, String owner) throws IOException {
        String content = String.format("{\"pid\": %s, \"Owner\": \"%s\"}", 
            filePath.getFileName().toString().replace(".json", ""), owner);
        String metaJson = String.format("{\"Owner\": \"%s\"}", owner);
        String fileContent = "#<META>\n" + metaJson + "\n<META>#\n" + content;
        Files.writeString(filePath, fileContent);
    }

    private void createProcessFileWithOwner(Path filePath, String owner) throws IOException {
        String pidStr = filePath.getFileName().toString().replace(".json", "");
        String content = String.format("{\"pid\": %s, \"Owner\": \"%s\"}", pidStr, owner);
        String metaJson = String.format("{\"Owner\": \"%s\", \"Permission\": {\"Owner\": \"read, write\", \"Others\": \"read\"}}", owner);
        String fileContent = "#<META>\n" + metaJson + "\n<META>#\n" + content;
        Files.writeString(filePath, fileContent);
    }

    private void createDirectoryMetaWithPermission(Path dirPath, String owner, String ownerPerm, String othersPerm) throws IOException {
        Path metaPath = dirPath.resolve(".META");
        if (!Files.exists(metaPath)) {
            String metaJson = String.format(
                "{\"Owner\": \"%s\", \"Time\": {\"createTime\": [2024, 1, 1, 0, 0, 0, 0]}, \"locked\": {\"isLocked\": false}, \"Permission\": {\"Owner\": \"%s\", \"Others\": \"%s\"}}",
                owner, ownerPerm, othersPerm
            );
            String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";
            Files.writeString(metaPath, fileContent);
        }
    }
}
