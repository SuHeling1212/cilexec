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
    private static String originalVfsRoot;

    @BeforeAll
    static void setupClass() throws Exception {
        Field vfsRootField = FileUtil.class.getDeclaredField("VFS_ROOT");
        vfsRootField.setAccessible(true);
        originalVfsRoot = (String) vfsRootField.get(null);
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

        UserUtil.setCurrentUser("local");
    }

    @AfterEach
    void teardown() {
        UserUtil.setCurrentUser("local");
    }

    @Nested
    @DisplayName("用户状态测试")
    class UserStateTests {

        @Test
        @DisplayName("设置和获取当前用户")
        void testSetAndGetCurrentUser() {
            UserUtil.setCurrentUser("testuser");
            assertEquals("testuser", UserUtil.getCurrentUser());

            UserUtil.setCurrentUser("admin");
            assertEquals("admin", UserUtil.getCurrentUser());
        }

        @Test
        @DisplayName("本地用户判断")
        void testIsLocal() {
            UserUtil.setCurrentUser("local");
            assertTrue(UserUtil.isLocal());

            UserUtil.setCurrentUser("remote_user");
            assertFalse(UserUtil.isLocal());

            UserUtil.setCurrentUser("Local");
            assertFalse(UserUtil.isLocal());
        }

        @Test
        @DisplayName("线程本地用户隔离")
        void testThreadLocalUserIsolation() throws InterruptedException {
            UserUtil.setCurrentUser("mainUser");

            Thread thread = new Thread(() -> {
                assertEquals("local", UserUtil.getCurrentUser());
                UserUtil.setCurrentUser("threadUser");
                assertEquals("threadUser", UserUtil.getCurrentUser());
            });

            thread.start();
            thread.join();
            assertEquals("mainUser", UserUtil.getCurrentUser());
        }
    }

    @Nested
    @DisplayName("PermissionResult 测试")
    class PermissionResultTests {

        @Test
        @DisplayName("成功结果")
        void testSuccessResult() {
            UserUtil.PermissionResult result = UserUtil.PermissionResult.success();

            assertTrue(result.isSuccess());
            assertNull(result.getErrorMessage());
            assertNull(result.getErrorContext());
            assertEquals("PermissionResult[SUCCESS]", result.toString());
        }

        @Test
        @DisplayName("失败结果")
        void testFailureResult() {
            UserUtil.PermissionResult result = UserUtil.PermissionResult.failure("Access denied", "Context info");

            assertFalse(result.isSuccess());
            assertEquals("Access denied", result.getErrorMessage());
            assertEquals("Context info", result.getErrorContext());
            assertTrue(result.toString().contains("FAILED"));
            assertTrue(result.toString().contains("Access denied"));
        }
    }

    @Nested
    @DisplayName("文件权限验证测试")
    class FilePermissionValidationTests {

        @Test
        @DisplayName("本地用户拥有所有文件权限")
        void testLocalUserHasAllPermissions() {
            UserUtil.setCurrentUser("local");

            assertTrue(UserUtil.checkFilePermission("/any/path", "read"));
            assertTrue(UserUtil.checkFilePermission("/any/path", "write"));
            assertTrue(UserUtil.checkFilePermission("/any/path", "execute"));

            UserUtil.PermissionResult result = UserUtil.validatePermission("/any/path", "read");
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("文件不存在错误")
        void testFileNotExist() {
            UserUtil.setCurrentUser("remote_user");

            UserUtil.PermissionResult result = UserUtil.validatePermission("/nonexistent/file.txt", "read");
            assertFalse(result.isSuccess());
            assertEquals("File does not exist", result.getErrorMessage());
            assertTrue(result.getErrorContext().contains("RealPath"));
        }

        @Test
        @DisplayName("元数据提取失败")
        void testMetadataExtractionFailure() throws IOException {
            UserUtil.setCurrentUser("testuser");

            Path filePath = testRoot.resolve("nofile.txt");
            Files.writeString(filePath, "content without metadata");

            UserUtil.PermissionResult result = UserUtil.validatePermission("/nofile.txt", "read");
            assertFalse(result.isSuccess());
            assertEquals("Failed to extract metadata", result.getErrorMessage());
        }

        @Test
        @DisplayName("权限元数据缺失")
        void testMissingPermissionMetadata() throws IOException {
            UserUtil.setCurrentUser("testuser");

            Path filePath = testRoot.resolve("noperm.txt");
            String metaJson = "{\"Owner\": \"owner\"}";
            Files.writeString(filePath, "#<META>\n" + metaJson + "\n<META>#\ncontent");

            UserUtil.PermissionResult result = UserUtil.validatePermission("/noperm.txt", "read");
            assertFalse(result.isSuccess());
            assertEquals("Permission metadata missing", result.getErrorMessage());
        }

        @Test
        @DisplayName("无效元数据格式")
        void testInvalidMetadataFormat() throws IOException {
            UserUtil.setCurrentUser("testuser");

            Path filePath = testRoot.resolve("invalid.txt");
            String invalidJson = "{ invalid json }";
            Files.writeString(filePath, "#<META>\n" + invalidJson + "\n<META>#\ncontent");

            UserUtil.PermissionResult result = UserUtil.validatePermission("/invalid.txt", "read");
            assertFalse(result.isSuccess());
            assertEquals("Failed to extract metadata", result.getErrorMessage());
        }
    }

    @Nested
    @DisplayName("所有者权限测试")
    class OwnerPermissionTests {

        @Test
        @DisplayName("所有者拥有对应权限")
        void testOwnerHasPermission() throws IOException {
            UserUtil.setCurrentUser("owner");

            Path filePath = testRoot.resolve("ownerfile.txt");
            createTestFile(filePath, "owner", "read, write, execute", "read");

            assertTrue(UserUtil.checkFilePermission("/ownerfile.txt", "read"));
            assertTrue(UserUtil.checkFilePermission("/ownerfile.txt", "write"));
            assertTrue(UserUtil.checkFilePermission("/ownerfile.txt", "execute"));

            UserUtil.PermissionResult result = UserUtil.validatePermission("/ownerfile.txt", "read");
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("所有者权限不足")
        void testOwnerPermissionDenied() throws IOException {
            UserUtil.setCurrentUser("owner");

            Path filePath = testRoot.resolve("ownerfile.txt");
            createTestFile(filePath, "owner", "read", "read");

            UserUtil.PermissionResult result = UserUtil.validatePermission("/ownerfile.txt", "write");
            assertFalse(result.isSuccess());
            assertEquals("Owner permission denied", result.getErrorMessage());
            assertTrue(result.getErrorContext().contains("Owner: owner"));
        }
    }

    @Nested
    @DisplayName("其他用户权限测试")
    class OthersPermissionTests {

        @Test
        @DisplayName("其他用户有权限")
        void testOthersHavePermission() throws IOException {
            UserUtil.setCurrentUser("otheruser");

            Path filePath = testRoot.resolve("otherfile.txt");
            createTestFile(filePath, "owner", "read, write", "read, execute");

            UserUtil.PermissionResult result = UserUtil.validatePermission("/otherfile.txt", "read");
            assertTrue(result.isSuccess());

            result = UserUtil.validatePermission("/otherfile.txt", "execute");
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("其他用户权限不足")
        void testOthersPermissionDenied() throws IOException {
            UserUtil.setCurrentUser("otheruser");

            Path filePath = testRoot.resolve("otherfile.txt");
            createTestFile(filePath, "owner", "read, write", "read");

            UserUtil.PermissionResult result = UserUtil.validatePermission("/otherfile.txt", "write");
            assertFalse(result.isSuccess());
            assertEquals("Others permission denied", result.getErrorMessage());
        }
    }

    @Nested
    @DisplayName("进程权限验证测试")
    class ProcessPermissionValidationTests {

        @Test
        @DisplayName("本地用户拥有所有进程权限")
        void testLocalUserHasProcessPermission() {
            UserUtil.setCurrentUser("local");

            assertTrue(UserUtil.checkProcessPermission(1));
            assertTrue(UserUtil.checkProcessPermission(999));

            UserUtil.PermissionResult result = UserUtil.validateProcessPermission(123);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("进程文件不存在")
        void testProcessFileNotExist() {
            UserUtil.setCurrentUser("remote_user");

            UserUtil.PermissionResult result = UserUtil.validateProcessPermission(99999);
            assertFalse(result.isSuccess());
            assertEquals("Failed to read process file", result.getErrorMessage());
        }

        @Test
        @DisplayName("进程所有者匹配")
        void testProcessOwnerMatch() throws IOException {
            UserUtil.setCurrentUser("processowner");

            createProcessFile(123, "processowner");

            UserUtil.PermissionResult result = UserUtil.validateProcessPermission(123);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("进程所有者不匹配")
        void testProcessOwnerMismatch() throws IOException {
            UserUtil.setCurrentUser("otheruser");

            createProcessFile(456, "processowner");

            UserUtil.PermissionResult result = UserUtil.validateProcessPermission(456);
            assertFalse(result.isSuccess());
            assertEquals("Process ownership mismatch", result.getErrorMessage());
        }

        @Test
        @DisplayName("进程所有者信息缺失")
        void testProcessOwnerMissing() throws IOException {
            UserUtil.setCurrentUser("testuser");

            Path systemDir = testRoot.resolve("system");
            Path processDir = systemDir.resolve("process");
            Files.createDirectories(processDir);

            Path metaDir = systemDir.resolve(".META");
            String metaJson = "{\"Owner\": \"local\", \"Permission\": {\"Owner\": \"read, write\", \"Others\": \"read\"}}";
            Files.writeString(metaDir, "#<META>\n" + metaJson + "\n<META>#\n");

            Path metaProcessDir = processDir.resolve(".META");
            Files.writeString(metaProcessDir, "#<META>\n" + metaJson + "\n<META>#\n");

            Path processFile = processDir.resolve("789.json");
            String content = "{\"pid\": 789}";
            Files.writeString(processFile, "#<META>\n{\"Owner\": \"\"}\n<META>#\n" + content);

            UserUtil.PermissionResult result = UserUtil.validateProcessPermission(789);
            assertFalse(result.isSuccess());
            assertEquals("Process owner information missing", result.getErrorMessage());
        }

        @Test
        @DisplayName("进程JSON格式无效")
        void testProcessInvalidJson() throws IOException {
            UserUtil.setCurrentUser("testuser");

            Path systemDir = testRoot.resolve("system");
            Path processDir = systemDir.resolve("process");
            Files.createDirectories(processDir);

            Path metaDir = systemDir.resolve(".META");
            String metaJson = "{\"Owner\": \"local\", \"Permission\": {\"Owner\": \"read, write\", \"Others\": \"read\"}}";
            Files.writeString(metaDir, "#<META>\n" + metaJson + "\n<META>#\n");

            Path metaProcessDir = processDir.resolve(".META");
            Files.writeString(metaProcessDir, "#<META>\n" + metaJson + "\n<META>#\n");

            Path processFile = processDir.resolve("999.json");
            String invalidContent = "{ invalid json content }";
            Files.writeString(processFile, "#<META>\n{\"Owner\": \"testuser\"}\n<META>#\n" + invalidContent);

            UserUtil.PermissionResult result = UserUtil.validateProcessPermission(999);
            assertFalse(result.isSuccess());
            assertEquals("Process permission check error: invalid JSON structure", result.getErrorMessage());
        }
    }

    private void createTestFile(Path filePath, String owner, String ownerPerm, String othersPerm) throws IOException {
        String metaJson = String.format(
            "{\"Owner\": \"%s\", \"Permission\": {\"Owner\": \"%s\", \"Others\": \"%s\"}, \"locked\": {\"isLocked\": false}}",
            owner, ownerPerm, othersPerm
        );
        String fileContent = "#<META>\n" + metaJson + "\n<META>#\ntest content";
        Files.writeString(filePath, fileContent);
    }

    private void createProcessFile(int pid, String owner) throws IOException {
        Path systemDir = testRoot.resolve("system");
        Path processDir = systemDir.resolve("process");
        Files.createDirectories(processDir);

        Path metaDir = systemDir.resolve(".META");
        String metaJson = "{\"Owner\": \"local\", \"Permission\": {\"Owner\": \"read, write\", \"Others\": \"read\"}}";
        Files.writeString(metaDir, "#<META>\n" + metaJson + "\n<META>#\n");

        Path metaProcessDir = processDir.resolve(".META");
        Files.writeString(metaProcessDir, "#<META>\n" + metaJson + "\n<META>#\n");

        Path processFile = processDir.resolve(pid + ".json");
        String content = String.format("{\"pid\": %d, \"Owner\": \"%s\"}", pid, owner);
        String processMetaJson = String.format("{\"Owner\": \"%s\", \"Permission\": {\"Owner\": \"read, write\", \"Others\": \"read\"}}", owner);
        Files.writeString(processFile, "#<META>\n" + processMetaJson + "\n<META>#\n" + content);
    }
}