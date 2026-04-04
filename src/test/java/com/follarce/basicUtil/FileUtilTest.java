package com.follarce.basicUtil;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
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

    @Test
    @Order(1)
    @DisplayName("测试路径白名单验证 - isValidPathCharacter 方法")
    void testIsValidPathCharacter() throws Exception {
        Method method = FileUtil.class.getDeclaredMethod("isValidPathCharacter", String.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(null, "valid_name"));
        assertTrue((boolean) method.invoke(null, "valid-name"));
        assertTrue((boolean) method.invoke(null, "valid.name"));
        assertTrue((boolean) method.invoke(null, "ValidName123"));
        assertTrue((boolean) method.invoke(null, "123"));
        assertTrue((boolean) method.invoke(null, "a"));

        assertFalse((boolean) method.invoke(null, "invalid/name"));
        assertFalse((boolean) method.invoke(null, "invalid\\name"));
        assertFalse((boolean) method.invoke(null, "invalid name"));
        assertFalse((boolean) method.invoke(null, "invalid@name"));
        assertFalse((boolean) method.invoke(null, "invalid#name"));
        assertFalse((boolean) method.invoke(null, "invalid$name"));
        assertFalse((boolean) method.invoke(null, "invalid%name"));
        assertFalse((boolean) method.invoke(null, "invalid^name"));
        assertFalse((boolean) method.invoke(null, "invalid&name"));
        assertFalse((boolean) method.invoke(null, "invalid*name"));
        assertFalse((boolean) method.invoke(null, "invalid(name"));
        assertFalse((boolean) method.invoke(null, "invalid)name"));
        assertFalse((boolean) method.invoke(null, "invalid+name"));
        assertFalse((boolean) method.invoke(null, "invalid=name"));
        assertFalse((boolean) method.invoke(null, "invalid[name"));
        assertFalse((boolean) method.invoke(null, "invalid]name"));
        assertFalse((boolean) method.invoke(null, "invalid{name"));
        assertFalse((boolean) method.invoke(null, "invalid}name"));
        assertFalse((boolean) method.invoke(null, "invalid|name"));
        assertFalse((boolean) method.invoke(null, "invalid;name"));
        assertFalse((boolean) method.invoke(null, "invalid:name"));
        assertFalse((boolean) method.invoke(null, "invalid'name"));
        assertFalse((boolean) method.invoke(null, "invalid\"name"));
        assertFalse((boolean) method.invoke(null, "invalid<name"));
        assertFalse((boolean) method.invoke(null, "invalid>name"));
        assertFalse((boolean) method.invoke(null, "invalid,name"));
        assertFalse((boolean) method.invoke(null, "invalid?name"));
        assertFalse((boolean) method.invoke(null, "invalid!name"));
        assertFalse((boolean) method.invoke(null, "invalid~name"));
        assertFalse((boolean) method.invoke(null, "invalid`name"));

        assertFalse((boolean) method.invoke(null, (String) null));
        assertFalse((boolean) method.invoke(null, ""));
        assertFalse((boolean) method.invoke(null, "   "));
    }

    @Test
    @Order(2)
    @DisplayName("测试文件名验证 - isValidName 方法")
    void testIsValidName() throws Exception {
        Method method = FileUtil.class.getDeclaredMethod("isValidName", String.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(null, "valid_name"));
        assertTrue((boolean) method.invoke(null, "valid-name"));
        assertTrue((boolean) method.invoke(null, "valid.name"));
        assertTrue((boolean) method.invoke(null, "ValidName123"));
        assertTrue((boolean) method.invoke(null, "file.txt"));
        assertTrue((boolean) method.invoke(null, "config.json"));

        assertFalse((boolean) method.invoke(null, ".hidden"));
        assertFalse((boolean) method.invoke(null, ".gitignore"));
        assertFalse((boolean) method.invoke(null, ".env"));

        assertFalse((boolean) method.invoke(null, (String) null));
        assertFalse((boolean) method.invoke(null, ""));
        assertFalse((boolean) method.invoke(null, "   "));

        assertFalse((boolean) method.invoke(null, "invalid/name"));
        assertFalse((boolean) method.invoke(null, "invalid\\name"));
        assertFalse((boolean) method.invoke(null, "invalid name"));
        assertFalse((boolean) method.invoke(null, "invalid@name"));
    }

    @Test
    @Order(3)
    @DisplayName("测试路径规范化 - normalizePath 方法")
    void testNormalizePath() throws Exception {
        Method method = FileUtil.class.getDeclaredMethod("normalizePath", String.class);
        method.setAccessible(true);

        assertEquals("/", method.invoke(null, (String) null));
        assertEquals("/", method.invoke(null, ""));
        assertEquals("/", method.invoke(null, "/"));
        assertEquals("/", method.invoke(null, "//"));
        assertEquals("/", method.invoke(null, "///"));

        assertEquals("/a", method.invoke(null, "a"));
        assertEquals("/a", method.invoke(null, "/a"));
        assertEquals("/a", method.invoke(null, "//a"));
        assertEquals("/a", method.invoke(null, "/a/"));

        assertEquals("/a/b", method.invoke(null, "/a/b"));
        assertEquals("/a/b", method.invoke(null, "/a//b"));
        assertEquals("/a/b", method.invoke(null, "//a//b//"));
        assertEquals("/a/b/c", method.invoke(null, "/a/b/c"));

        assertEquals("/a/b", method.invoke(null, "/a\\b"));

        assertEquals("/a/b", method.invoke(null, "/a/./b"));
        assertEquals("/a/b", method.invoke(null, "/a/././b"));
        assertEquals("/b", method.invoke(null, "/a/../b"));
        assertEquals("/b", method.invoke(null, "/a/b/../../b"));
        assertEquals("/", method.invoke(null, "/a/.."));
        assertEquals("/", method.invoke(null, "/../"));

        assertEquals("/a/b/c", method.invoke(null, "\\a\\b\\c"));
        assertEquals("/a/b/c", method.invoke(null, "\\\\a\\\\b\\\\c"));
    }

    @Test
    @Order(4)
    @DisplayName("测试路径规范化 - 无效路径组件")
    void testNormalizePathInvalidComponent() throws Exception {
        Method method = FileUtil.class.getDeclaredMethod("normalizePath", String.class);
        method.setAccessible(true);

        assertEquals("/", method.invoke(null, "/valid/invalid path/valid"));
        assertEquals("/", method.invoke(null, "/valid/invalid@name/valid"));
        assertEquals("/", method.invoke(null, "/test/hello world"));
    }

    @Test
    @Order(5)
    @DisplayName("测试 getVfsRoot 方法")
    void testGetVfsRoot() {
        String vfsRoot = FileUtil.getVfsRoot();
        assertNotNull(vfsRoot);
        assertEquals(testRoot.toString(), vfsRoot);
    }

    @Test
    @Order(6)
    @DisplayName("测试 extractMetaContent 方法 - 有效元数据")
    void testExtractMetaContentValid() {
        String content = "#<META>\n{\"key\": \"value\"}\n<META>#\nbody content";
        String[] result = FileUtil.extractMetaContent(content);

        assertEquals("SUCCESS", result[0]);
        assertEquals("{\"key\": \"value\"}", result[1]);
    }

    @Test
    @Order(7)
    @DisplayName("测试 extractMetaContent 方法 - 无元数据")
    void testExtractMetaContentNoMeta() {
        String content = "body content without metadata";
        String[] result = FileUtil.extractMetaContent(content);

        assertEquals("ERROR", result[0]);
        assertEquals("NO_META", result[1]);
    }

    @Test
    @Order(8)
    @DisplayName("测试 extractMetaContent 方法 - 元数据未闭合")
    void testExtractMetaContentNotClosed() {
        String content = "#<META>\n{\"key\": \"value\"}\nbody content";
        String[] result = FileUtil.extractMetaContent(content);

        assertEquals("ERROR", result[0]);
        assertEquals("META_NOT_CLOSED", result[1]);
    }

    @Test
    @Order(9)
    @DisplayName("测试 extractMetaContent 方法 - null 和空字符串")
    void testExtractMetaContentNullOrEmpty() {
        String[] resultNull = FileUtil.extractMetaContent(null);
        assertEquals("ERROR", resultNull[0]);
        assertEquals("NO_META", resultNull[1]);

        String[] resultEmpty = FileUtil.extractMetaContent("");
        assertEquals("ERROR", resultEmpty[0]);
        assertEquals("NO_META", resultEmpty[1]);
    }

    @Test
    @Order(10)
    @DisplayName("测试创建目录 - createDirectory")
    void testCreateDirectory() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.createDirectory("/parent/", "testdir");

        assertEquals("SUCCESS", result[0]);
        assertNull(result[1]);
        assertTrue(Files.exists(testRoot.resolve("parent").resolve("testdir")));
        assertTrue(Files.isDirectory(testRoot.resolve("parent").resolve("testdir")));
    }

    @Test
    @Order(11)
    @DisplayName("测试创建目录 - 无效名称")
    void testCreateDirectoryInvalidName() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.createDirectory("/parent/", ".hidden");
        assertEquals("ERROR", result[0]);
        assertEquals("INVALID_NAME", result[1]);

        String[] result2 = FileUtil.createDirectory("/parent/", "invalid name");
        assertEquals("ERROR", result2[0]);
        assertEquals("INVALID_NAME", result2[1]);
    }

    @Test
    @Order(12)
    @DisplayName("测试创建目录 - 目录已存在")
    void testCreateDirectoryAlreadyExists() throws IOException {
        Files.createDirectories(testRoot.resolve("parent").resolve("existing"));
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.createDirectory("/parent/", "existing");

        assertEquals("ERROR", result[0]);
        assertEquals("DIRECTORY_EXIST", result[1]);
    }

    @Test
    @Order(13)
    @DisplayName("测试创建目录 - 父目录不存在")
    void testCreateDirectoryParentNotExist() {
        String[] result = FileUtil.createDirectory("/nonexistent/", "testdir");

        assertEquals("ERROR", result[0]);
        assertEquals("DIRECTORY_DOES_NOT_EXIST", result[1]);
    }

    @Test
    @Order(14)
    @DisplayName("测试创建文件 - createFile")
    void testCreateFile() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.createFile("/parent/", "testfile.txt");

        assertEquals("SUCCESS", result[0]);
        assertNull(result[1]);
        assertTrue(Files.exists(testRoot.resolve("parent").resolve("testfile.txt")));
        assertTrue(Files.isRegularFile(testRoot.resolve("parent").resolve("testfile.txt")));
    }

    @Test
    @Order(15)
    @DisplayName("测试创建文件 - 无效名称")
    void testCreateFileInvalidName() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.createFile("/parent/", ".hidden");
        assertEquals("ERROR", result[0]);
        assertEquals("INVALID_NAME", result[1]);

        String[] result2 = FileUtil.createFile("/parent/", "invalid/name");
        assertEquals("ERROR", result2[0]);
        assertEquals("INVALID_NAME", result2[1]);
    }

    @Test
    @Order(16)
    @DisplayName("测试创建文件 - 文件已存在")
    void testCreateFileAlreadyExists() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Files.writeString(testRoot.resolve("parent").resolve("existing.txt"), "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.createFile("/parent/", "existing.txt");

        assertEquals("ERROR", result[0]);
        assertEquals("FILE_EXIST", result[1]);
    }

    @Test
    @Order(17)
    @DisplayName("测试写入文件 - write")
    void testWriteFile() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFile(filePath, "initial content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.write("/parent/testfile.txt", "new content");

        assertEquals("SUCCESS", result[0]);
        assertNull(result[1]);

        String fullContent = Files.readString(filePath);
        assertTrue(fullContent.contains("new content"));
        assertTrue(fullContent.contains("#<META>"));
    }

    @Test
    @Order(18)
    @DisplayName("测试读取文件 - read")
    void testReadFile() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFile(filePath, "test content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.read("/parent/testfile.txt");

        assertEquals("SUCCESS", result[0]);
        assertEquals("test content", result[1]);
    }

    @Test
    @Order(19)
    @DisplayName("测试读取文件 - 文件不存在")
    void testReadFileNotExist() throws IOException {
        Files.createDirectories(testRoot.resolve("existent"));
        createDirectoryMeta(testRoot.resolve("existent"));

        String[] result = FileUtil.read("/existent/nonexistent_file.txt");

        assertEquals("ERROR", result[0]);
        assertEquals("FILE_DOES_NOT_EXIST", result[1]);
    }

    @Test
    @Order(20)
    @DisplayName("测试删除文件 - removeFile")
    void testRemoveFile() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFile(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        assertTrue(Files.exists(filePath));

        String[] result = FileUtil.removeFile("/parent/testfile.txt");

        assertEquals("SUCCESS", result[0]);
        assertNull(result[1]);
        assertFalse(Files.exists(filePath));
    }

    @Test
    @Order(21)
    @DisplayName("测试删除文件 - 文件不存在")
    void testRemoveFileNotExist() {
        String[] result = FileUtil.removeFile("/nonexistent/file.txt");

        assertEquals("ERROR", result[0]);
    }

    @Test
    @Order(22)
    @DisplayName("测试删除目录 - removeDirectory")
    void testRemoveDirectory() throws IOException {
        Path parentPath = testRoot.resolve("parent");
        Files.createDirectories(parentPath);
        Path dirPath = parentPath.resolve("testdir");
        Files.createDirectories(dirPath);
        createDirectoryMeta(parentPath);
        createDirectoryMeta(dirPath);

        String[] result = FileUtil.removeDirectory("/parent/testdir/");

        assertEquals("SUCCESS", result[0]);
        assertNull(result[1]);
        assertFalse(Files.exists(dirPath));
    }

    @Test
    @Order(23)
    @DisplayName("测试删除目录 - 目录非空")
    void testRemoveDirectoryNotEmpty() throws IOException {
        Path parentPath = testRoot.resolve("parent");
        Files.createDirectories(parentPath);
        Path dirPath = parentPath.resolve("testdir");
        Files.createDirectories(dirPath);
        Files.writeString(dirPath.resolve("file.txt"), "content");
        createDirectoryMeta(parentPath);
        createDirectoryMeta(dirPath);

        String[] result = FileUtil.removeDirectory("/parent/testdir/");

        assertEquals("ERROR", result[0]);
        assertEquals("DIRECTORY_IS_NOT_EMPTY", result[1]);
        assertTrue(Files.exists(dirPath));
    }

    @Test
    @Order(24)
    @DisplayName("测试重命名文件 - Rename")
    void testRenameFile() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("oldname.txt");
        createTestFile(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.Rename("/parent/oldname.txt", "newname.txt");

        assertEquals("SUCCESS", result[0]);
        assertNull(result[1]);
        assertFalse(Files.exists(filePath));
        assertTrue(Files.exists(testRoot.resolve("parent").resolve("newname.txt")));
    }

    @Test
    @Order(25)
    @DisplayName("测试重命名文件 - 无效新名称")
    void testRenameFileInvalidName() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("oldname.txt");
        createTestFile(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.Rename("/parent/oldname.txt", ".hidden");

        assertEquals("ERROR", result[0]);
        assertEquals("INVALID_NEW_NAME", result[1]);
    }

    @Test
    @Order(26)
    @DisplayName("测试获取目录列表 - getListOfFileAndDirectory")
    void testGetListOfFileAndDirectory() throws IOException {
        Path parentPath = testRoot.resolve("parent");
        Files.createDirectories(parentPath);
        Files.createDirectories(parentPath.resolve("subdir"));
        createTestFile(parentPath.resolve("file1.txt"), "content1");
        createTestFile(parentPath.resolve("file2.txt"), "content2");
        createDirectoryMeta(parentPath);

        String[] result = FileUtil.getListOfFileAndDirectory("/parent/");

        assertEquals("SUCCESS", result[0]);
        assertTrue(result.length >= 3);

        boolean hasSubdir = false;
        boolean hasFile1 = false;
        boolean hasFile2 = false;

        for (int i = 1; i < result.length; i++) {
            if (result[i].equals("subdir/")) hasSubdir = true;
            if (result[i].equals("file1.txt")) hasFile1 = true;
            if (result[i].equals("file2.txt")) hasFile2 = true;
        }

        assertTrue(hasSubdir);
        assertTrue(hasFile1);
        assertTrue(hasFile2);
    }

    @Test
    @Order(27)
    @DisplayName("测试追加内容 - append")
    void testAppendFile() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFile(filePath, "initial content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.append("/parent/testfile.txt", "appended content");

        assertEquals("SUCCESS", result[0]);
        assertNull(result[1]);

        String[] readResult = FileUtil.read("/parent/testfile.txt");
        assertEquals("initial content\nappended content", readResult[1]);
    }

    @Test
    @Order(28)
    @DisplayName("测试读取文件元数据 - readFileMetaData")
    void testReadFileMetaData() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFile(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String[] result = FileUtil.readFileMetaData("/parent/testfile.txt");

        assertEquals("SUCCESS", result[0]);
        assertTrue(result[1].contains("Owner"));
        assertTrue(result[1].contains("Time"));
    }

    @Test
    @Order(29)
    @DisplayName("测试写入文件元数据 - writeFileMetaData")
    void testWriteFileMetaData() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFile(filePath, "content");
        createDirectoryMeta(testRoot.resolve("parent"));

        String newMeta = "{\"Owner\": \"testuser\"}";
        String[] result = FileUtil.writeFileMetaData("/parent/testfile.txt", newMeta);

        assertEquals("SUCCESS", result[0]);
        assertNull(result[1]);

        String[] readResult = FileUtil.readFileMetaData("/parent/testfile.txt");
        assertTrue(readResult[1].contains("testuser"));
    }

    @Test
    @Order(30)
    @DisplayName("测试 call 方法 - read")
    void testCallRead() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFile(filePath, "test content");
        createDirectoryMeta(testRoot.resolve("parent"));

        Object result = FileUtil.call("read", new Object[]{"/parent/testfile.txt"});

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("SUCCESS", arr[0]);
        assertEquals("test content", arr[1]);
    }

    @Test
    @Order(31)
    @DisplayName("测试 call 方法 - write")
    void testCallWrite() throws IOException {
        Files.createDirectories(testRoot.resolve("parent"));
        Path filePath = testRoot.resolve("parent").resolve("testfile.txt");
        createTestFile(filePath, "initial");
        createDirectoryMeta(testRoot.resolve("parent"));

        Object result = FileUtil.call("write", new Object[]{"/parent/testfile.txt", "new content"});

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("SUCCESS", arr[0]);
    }

    @Test
    @Order(32)
    @DisplayName("测试 call 方法 - 无效参数")
    void testCallInvalidArguments() {
        Object result = FileUtil.call("read", new Object[]{});

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("ERROR", arr[0]);
        assertEquals("INVALID_ARGUMENTS", arr[1]);
    }

    @Test
    @Order(33)
    @DisplayName("测试 call 方法 - 未知方法")
    void testCallUnknownMethod() {
        Object result = FileUtil.call("unknownMethod", new Object[]{});

        assertNull(result);
    }

    private void createTestFile(Path filePath, String content) throws IOException {
        String metaJson = "{\"Owner\": \"local\", \"Time\": {\"createTime\": [2024, 1, 1, 0, 0, 0, 0]}, \"locked\": {\"isLocked\": false}}";
        String fileContent = "#<META>\n" + metaJson + "\n<META>#\n" + content;
        Files.writeString(filePath, fileContent);
    }

    private void createDirectoryMeta(Path dirPath) throws IOException {
        Path metaPath = dirPath.resolve(".META");
        if (!Files.exists(metaPath)) {
            String metaJson = "{\"Owner\": \"local\", \"Time\": {\"createTime\": [2024, 1, 1, 0, 0, 0, 0]}, \"locked\": {\"isLocked\": false}, \"Permission\": {\"Owner\": \"read, write\", \"Others\": \"read\"}}";
            String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";
            Files.writeString(metaPath, fileContent);
        }
    }
}
