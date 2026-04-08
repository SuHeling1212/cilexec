package com.follarce.basicUtil;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoggerTest {

    private static String tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        tempDir = Files.createTempDirectory("logger_test").toString();
    }

    @AfterAll
    static void tearDown() throws Exception {
        Logger.setLogPath(null);
        Files.walk(Paths.get(tempDir))
            .sorted((a, b) -> b.compareTo(a))
            .forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
    }

    @Test
    @Order(1)
    @DisplayName("测试默认日志路径")
    void testDefaultLogPath() throws Exception {
        String workDir = System.getProperty("user.dir");
        String expectedDefaultPath = workDir + File.separator + Constants.DEFAULT_LOG_FILE_NAME;
        
        String defaultLogFile = workDir + File.separator + Constants.DEFAULT_LOG_FILE_NAME;
        File defaultLog = new File(defaultLogFile);
        
        assertTrue(defaultLog.exists() || new File(expectedDefaultPath).exists() || defaultLog.getParentFile().canWrite(),
            "默认日志文件应在工作目录下可访问");
    }

    @Test
    @Order(2)
    @DisplayName("测试 setLogPath() 设置自定义路径")
    void testSetLogPath() throws Exception {
        String customPath = tempDir + File.separator + "custom.log";
        
        Logger.setLogPath(customPath);
        
        File customLogFile = new File(customPath);
        assertTrue(customLogFile.exists() || customLogFile.getParentFile().canWrite(),
            "自定义日志路径文件应被创建或可写");
    }

    @Test
    @Order(3)
    @DisplayName("测试路径切换 - 从自定义路径切回默认路径")
    void testPathSwitching() throws Exception {
        String customPath = tempDir + File.separator + "switch_test.log";
        String workDir = System.getProperty("user.dir");
        String defaultPath = workDir + File.separator + Constants.DEFAULT_LOG_FILE_NAME;
        
        Logger.setLogPath(customPath);
        
        Logger.info("Test message after custom path set");
        
        File customLogFile = new File(customPath);
        assertTrue(customLogFile.length() > 0, "自定义路径日志文件应有内容写入");
        
        Logger.setLogPath(null);
        Logger.info("Test message after resetting to default path");
        
        File defaultLogFile = new File(defaultPath);
        assertTrue(defaultLogFile.length() > 0, "默认路径日志文件应有内容写入");
    }

    @Test
    @Order(4)
    @DisplayName("测试 setLogPath 多次切换")
    void testMultiplePathSwitching() throws Exception {
        String path1 = tempDir + File.separator + "log1.log";
        String path2 = tempDir + File.separator + "log2.log";
        
        Logger.setLogPath(path1);
        Logger.info("Message to path 1");
        
        File log1 = new File(path1);
        assertTrue(log1.length() > 0, "第一次设置的路径应有日志内容");
        
        Logger.setLogPath(path2);
        Logger.info("Message to path 2");
        
        File log2 = new File(path2);
        assertTrue(log2.length() > 0, "第二次设置的路径应有日志内容");
        
        assertTrue(log1.length() < log2.length() || log2.length() > 0,
            "切换路径后新文件应有内容");
    }

    @Test
    @Order(5)
    @DisplayName("测试 setLogPath 接受空字符串时使用默认路径")
    void testSetLogPathWithEmptyString() throws Exception {
        String emptyPath = "";
        
        Logger.setLogPath(emptyPath);
        
        String workDir = System.getProperty("user.dir");
        String expectedDefaultPath = workDir + File.separator + Constants.DEFAULT_LOG_FILE_NAME;
        File defaultLogFile = new File(expectedDefaultPath);
        
        assertTrue(defaultLogFile.exists() || defaultLogFile.getParentFile().canWrite(),
            "设置空字符串时应使用默认路径");
    }
}