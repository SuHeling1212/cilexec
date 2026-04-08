package com.follarce.basicUtil;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FileUtilTest {

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
    }

    // ==================== 元数据读写测试 ====================

    @Test
    @Order(1)
    @DisplayName("测试创建文件时自动生成元数据")
    void testCreateFileGeneratesMetadata() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.createFile("/parent/", "testfile.txt");

        assertEquals("SUCCESS", result[0]);
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        assertTrue(Files.exists(filePath));

        String content = Files.readString(filePath);
        assertTrue(content.contains("#<META>"));
        assertTrue(content.contains("Owner"));
        assertTrue(content.contains("Time"));
        assertTrue(content.contains("locked"));
        assertTrue(content.contains("Size"));
    }

    @Test
    @Order(2)
    @DisplayName("测试读取文件元数据 - readFileMetaData")
    void testReadFileMetaData() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFileWithMetadata(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.readFileMetaData("/parent/testfile.txt");

        assertEquals("SUCCESS", result[0]);
        assertNotNull(result[1]);
        assertTrue(result[1].contains("Owner"));
        assertTrue(result[1].contains("Time"));
        assertTrue(result[1].contains("createTime"));
        assertTrue(result[1].contains("locked"));
    }

    @Test
    @Order(3)
    @DisplayName("测试读取文件元数据 - 文件不存在")
    void testReadFileMetaDataFileNotExist() {
        String[] result = FileUtil.readFileMetaData("/nonexistent/file.txt");

        assertEquals("ERROR", result[0]);
        assertEquals("FILE_DOES_NOT_EXIST", result[1]);
    }

    @Test
    @Order(4)
    @DisplayName("测试写入文件元数据 - writeFileMetaData")
    void testWriteFileMetaData() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFileWithMetadata(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String newMeta = "{\"Owner\": \"testuser\", \"CustomField\": \"customValue\"}";
        String[] result = FileUtil.writeFileMetaData("/parent/testfile.txt", newMeta);

        assertEquals("SUCCESS", result[0]);

        String[] readResult = FileUtil.readFileMetaData("/parent/testfile.txt");
        assertTrue(readResult[1].contains("testuser"));
        assertTrue(readResult[1].contains("customValue"));
    }

    @Test
    @Order(5)
    @DisplayName("测试写入文件元数据 - 无效JSON")
    void testWriteFileMetaDataInvalidJson() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFileWithMetadata(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.writeFileMetaData("/parent/testfile.txt", "invalid json");

        assertEquals("ERROR", result[0]);
        assertEquals("INVALID_JSON", result[1]);
    }

    @Test
    @Order(6)
    @DisplayName("测试写入文件元数据 - 更新lastEditTime")
    void testWriteFileMetaDataUpdatesLastEditTime() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFileWithMetadata(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] readBefore = FileUtil.readFileMetaData("/parent/testfile.txt");
        assertTrue(readBefore[1].contains("createTime"));

        String newMeta = "{\"Owner\": \"newowner\"}";
        FileUtil.writeFileMetaData("/parent/testfile.txt", newMeta);

        String[] readAfter = FileUtil.readFileMetaData("/parent/testfile.txt");
        assertTrue(readAfter[1].contains("lastEditTime"));
    }

    @Test
    @Order(7)
    @DisplayName("测试创建目录元数据 - createDirectoryMetaData")
    void testCreateDirectoryMetaData() throws IOException {
        Files.createDirectories(testRoot.resolve("parent").resolve("newdir"));
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.createDirectoryMetaData("/parent/newdir/");

        assertEquals("SUCCESS", result[0]);
        Path metaPath = testRoot.resolve("parent").resolve("newdir").resolve(".META");
        assertTrue(Files.exists(metaPath));

        String content = Files.readString(metaPath);
        assertTrue(content.contains("Owner"));
        assertTrue(content.contains("Time"));
        assertTrue(content.contains("locked"));
        assertTrue(content.contains("Permission"));
    }

    @Test
    @Order(8)
    @DisplayName("测试创建目录元数据 - 已存在则失败")
    void testCreateDirectoryMetaDataAlreadyExists() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent"));
        Files.writeString(testRoot.resolve("parent").resolve(".META"), "#<META>\n{}\n<META>#\n");

        String[] result = FileUtil.createDirectoryMetaData("/parent/");

        assertEquals("ERROR", result[0]);
        assertEquals("FILE_EXIST", result[1]);
    }

    @Test
    @Order(9)
    @DisplayName("测试读取目录元数据 - readDirectoryMetaData")
    void testReadDirectoryMetaData() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.readDirectoryMetaData("/parent/");

        assertEquals("SUCCESS", result[0]);
        assertNotNull(result[1]);
        assertTrue(result[1].contains("Owner"));
        assertTrue(result[1].contains("Time"));
    }

    @Test
    @Order(10)
    @DisplayName("测试读取目录元数据 - 目录不存在")
    void testReadDirectoryMetaDataNotExist() {
        String[] result = FileUtil.readDirectoryMetaData("/nonexistent/");

        assertEquals("ERROR", result[0]);
        assertEquals("DIRECTORY_DOES_NOT_EXIST", result[1]);
    }

    @Test
    @Order(11)
    @DisplayName("测试写入目录元数据 - writeDirectoryMetaData")
    void testWriteDirectoryMetaData() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent"));

        String newMeta = "{\"Owner\": \"testuser\"}";
        String[] result = FileUtil.writeDirectoryMetaData("/parent/", newMeta);

        assertEquals("SUCCESS", result[0]);

        String[] readResult = FileUtil.readDirectoryMetaData("/parent/");
        assertTrue(readResult[1].contains("testuser"));
    }

    @Test
    @Order(12)
    @DisplayName("测试写入目录元数据 - 无效JSON")
    void testWriteDirectoryMetaDataInvalidJson() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.writeDirectoryMetaData("/parent/", "invalid json");

        assertEquals("ERROR", result[0]);
        assertEquals("INVALID_JSON", result[1]);
    }

    @Test
    @Order(13)
    @DisplayName("测试extractMetaContent - 正常提取")
    void testExtractMetaContentValid() {
        String content = "#<META>\n{\"key\": \"value\"}\n<META>#\nbody content";
        String[] result = FileUtil.extractMetaContent(content);

        assertEquals("SUCCESS", result[0]);
        assertEquals("{\"key\": \"value\"}", result[1]);
    }

    @Test
    @Order(14)
    @DisplayName("测试extractMetaContent - 无元数据")
    void testExtractMetaContentNoMeta() {
        String content = "body content without metadata";
        String[] result = FileUtil.extractMetaContent(content);

        assertEquals("ERROR", result[0]);
        assertEquals("NO_META", result[1]);
    }

    @Test
    @Order(15)
    @DisplayName("测试extractMetaContent - 元数据未闭合")
    void testExtractMetaContentNotClosed() {
        String content = "#<META>\n{\"key\": \"value\"}\nbody content";
        String[] result = FileUtil.extractMetaContent(content);

        assertEquals("ERROR", result[0]);
        assertEquals("META_NOT_CLOSED", result[1]);
    }

    // ==================== 文件锁定测试 ====================

    @Test
    @Order(16)
    @DisplayName("测试锁定文件 - lock")
    void testLockFile() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFileWithMetadata(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.lock("/parent/testfile.txt");

        assertEquals("SUCCESS", result[0]);

        String[] metaResult = FileUtil.readFileMetaData("/parent/testfile.txt");
        assertTrue(metaResult[1].contains("\"isLocked\":true"));
    }

    @Test
    @Order(17)
    @DisplayName("测试锁定文件 - 重复锁定失败")
    void testLockFileAlreadyLocked() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createLockedFile(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.lock("/parent/testfile.txt");

        assertEquals("ERROR", result[0]);
        assertEquals("FILE_IS_LOCKED", result[1]);
    }

    @Test
    @Order(18)
    @DisplayName("测试解锁文件 - unlock")
    void testUnlockFile() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createLockedFile(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.unlock("/parent/testfile.txt");

        assertEquals("SUCCESS", result[0]);

        String[] metaResult = FileUtil.readFileMetaData("/parent/testfile.txt");
        assertTrue(metaResult[1].contains("\"isLocked\":false"));
    }

    @Test
    @Order(19)
    @DisplayName("测试解锁文件 - 未锁定文件失败")
    void testUnlockFileNotLocked() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFileWithMetadata(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.unlock("/parent/testfile.txt");

        assertEquals("ERROR", result[0]);
        assertEquals("FILE_IS_NOT_LOCKED", result[1]);
    }

    @Test
    @Order(20)
    @DisplayName("测试锁定后无法写入")
    void testLockedFileCannotWrite() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createLockedFile(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.write("/parent/testfile.txt", "new content");

        assertEquals("ERROR", result[0]);
        assertEquals("FILE_IS_LOCKED", result[1]);
    }

    @Test
    @Order(21)
    @DisplayName("测试锁定后无法删除")
    void testLockedFileCannotRemove() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createLockedFile(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.removeFile("/parent/testfile.txt");

        assertEquals("ERROR", result[0]);
        assertEquals("FILE_IS_LOCKED", result[1]);
    }

    @Test
    @Order(22)
    @DisplayName("测试目录锁定后无法创建文件")
    void testLockedDirectoryCannotCreateFile() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent"));
        Files.writeString(testRoot.resolve("parent").resolve(".META"),
            "#<META>\n{\"locked\":{\"isLocked\":true,\"lockedBy\":99999}}\n<META>#\n");

        String[] result = FileUtil.createFile("/parent/", "newfile.txt");

        assertEquals("ERROR", result[0]);
        assertEquals("DIRECTORY_IS_LOCKED", result[1]);
    }

    @Test
    @Order(23)
    @DisplayName("测试目录锁定后无法创建子目录")
    void testLockedDirectoryCannotCreateDir() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent"));
        Files.writeString(testRoot.resolve("parent").resolve(".META"),
            "#<META>\n{\"locked\":{\"isLocked\":true,\"lockedBy\":99999}}\n<META>#\n");

        String[] result = FileUtil.createDirectory("/parent/", "newdir");

        assertEquals("ERROR", result[0]);
        assertEquals("DIRECTORY_IS_LOCKED", result[1]);
    }

    @Test
    @Order(24)
    @DisplayName("测试目录锁定后无法删除目录")
    void testLockedDirectoryCannotRemove() throws IOException {
        Files.createDirectories(testRoot.resolve("parent").resolve("lockedchild"));
        createDirectoryMeta(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent").resolve("lockedchild"));
        Files.writeString(testRoot.resolve("parent").resolve(".META"),
            "#<META>\n{\"locked\":{\"isLocked\":true,\"lockedBy\":99999}}\n<META>#\n");

        String[] result = FileUtil.removeDirectory("/parent/lockedchild/");

        assertEquals("ERROR", result[0]);
        assertEquals("DIRECTORY_IS_LOCKED", result[1]);
    }

    @Test
    @Order(25)
    @DisplayName("测试锁定文件无法重命名")
    void testLockedFileCannotRename() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createLockedFile(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.Rename("/parent/testfile.txt", "newname.txt");

        assertEquals("ERROR", result[0]);
        assertEquals("FILE_IS_LOCKED", result[1]);
    }

    @Test
    @Order(26)
    @DisplayName("测试读取文件时lastOpenTime更新")
    void testReadUpdatesLastOpenTime() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFileWithMetadata(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        FileUtil.read("/parent/testfile.txt");

        String[] metaResult = FileUtil.readFileMetaData("/parent/testfile.txt");
        assertTrue(metaResult[1].contains("lastOpenTime"));
    }

    // ==================== 路径别名解析测试 ====================

    @Test
    @Order(27)
    @DisplayName("测试路径别名解析 - ~ 解析")
    void testPathAliasResolution() {
        String pathWithAlias = "~";
        String resolved = PathUtil.resolvePath(pathWithAlias);
        assertNotNull(resolved);
        assertFalse(resolved.contains("~"));
    }

    @Test
    @Order(28)
    @DisplayName("测试路径别名解析 - 普通路径不受影响")
    void testPathAliasResolutionNormalPath() {
        String normalPath = "/user/local/file.txt";
        String resolved = PathUtil.resolvePath(normalPath);
        assertEquals(normalPath, resolved);
    }

    @Test
    @Order(29)
    @DisplayName("测试路径规范化 - 基本路径")
    void testNormalizePathBasic() throws Exception {
        java.lang.reflect.Method method = FileUtil.class.getDeclaredMethod("normalizePath", String.class);
        method.setAccessible(true);

        assertEquals("/", method.invoke(null, (String) null));
        assertEquals("/", method.invoke(null, ""));
        assertEquals("/a", method.invoke(null, "a"));
        assertEquals("/a/b", method.invoke(null, "/a/b"));
        assertEquals("/a/b/c", method.invoke(null, "/a/b/c"));
    }

    @Test
    @Order(30)
    @DisplayName("测试路径规范化 - 处理 .. 和 .")
    void testNormalizePathDots() throws Exception {
        java.lang.reflect.Method method = FileUtil.class.getDeclaredMethod("normalizePath", String.class);
        method.setAccessible(true);

        assertEquals("/a/b", method.invoke(null, "/a/./b"));
        assertEquals("/b", method.invoke(null, "/a/../b"));
        assertEquals("/", method.invoke(null, "/a/.."));
        assertEquals("/a/b", method.invoke(null, "/a/b/./c/../b"));
    }

    @Test
    @Order(31)
    @DisplayName("测试路径规范化 - Windows反斜杠转换")
    void testNormalizePathBackslash() throws Exception {
        java.lang.reflect.Method method = FileUtil.class.getDeclaredMethod("normalizePath", String.class);
        method.setAccessible(true);

        assertEquals("/a/b/c", method.invoke(null, "\\a\\b\\c"));
        assertEquals("/a/b", method.invoke(null, "/a\\b"));
    }

    @Test
    @Order(32)
    @DisplayName("测试路径规范化 - 无效路径组件返回根目录")
    void testNormalizePathInvalidComponent() throws Exception {
        java.lang.reflect.Method method = FileUtil.class.getDeclaredMethod("normalizePath", String.class);
        method.setAccessible(true);

        assertEquals("/", method.invoke(null, "/valid/invalid path/valid"));
        assertEquals("/", method.invoke(null, "/valid/invalid@name/valid"));
    }

    @Test
    @Order(33)
    @DisplayName("测试路径验证 - 无效路径返回错误")
    void testValidateFileInvalidPath() {
        String[] result = FileUtil.read(null);
        assertEquals("ERROR", result[0]);
        assertEquals("INVALID_PATH", result[1]);

        String[] result2 = FileUtil.read("");
        assertEquals("ERROR", result2[0]);
        assertEquals("INVALID_PATH", result2[1]);
    }

    // ==================== 辅助方法 ====================

    private void createTestFileWithMetadata(Path filePath, String content) throws IOException {
        String metaJson = "{\"Owner\": \"local\", \"Time\": {\"createTime\": [2024, 1, 1, 0, 0, 0, 0], \"lastEditTime\": [2024, 1, 1, 0, 0, 0, 0]}, \"locked\": {\"isLocked\": false, \"lockedBy\": null}}";
        String fileContent = "#<META>\n" + metaJson + "\n<META>#\n" + content;
        Files.writeString(filePath, fileContent);
    }

    private void createLockedFile(Path filePath, String content) throws IOException {
        String metaJson = "{\"Owner\": \"local\", \"Time\": {\"createTime\": [2024, 1, 1, 0, 0, 0, 0]}, \"locked\": {\"isLocked\": true, \"lockedBy\": 12345}}";
        String fileContent = "#<META>\n" + metaJson + "\n<META>#\n" + content;
        Files.writeString(filePath, fileContent);
    }

    private void createDirectoryMeta(Path dirPath) throws IOException {
        Path metaPath = dirPath.resolve(".META");
        if (!Files.exists(metaPath)) {
            String metaJson = "{\"Owner\": \"local\", \"Time\": {\"createTime\": [2024, 1, 1, 0, 0, 0, 0]}, \"locked\": {\"isLocked\": false, \"lockedBy\": null}, \"Permission\": {\"Owner\": \"read, write\", \"Others\": \"read\"}}";
            String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";
            Files.writeString(metaPath, fileContent);
        }
    }
}