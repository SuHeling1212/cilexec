package com.follarce.basicUtil;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConstantsTest {

    @Test
    @Order(1)
    @DisplayName("测试时间转换常量 - NANOS_TO_MILLIS")
    void testNanosToMillis() {
        assertEquals(1_000_000, Constants.NANOS_TO_MILLIS);
    }

    @Test
    @Order(2)
    @DisplayName("测试调度器配置常量 - SCHEDULER_SLEEP_MS")
    void testSchedulerSleepMs() {
        assertEquals(100, Constants.SCHEDULER_SLEEP_MS);
    }

    @Test
    @Order(3)
    @DisplayName("测试日志配置常量 - LOG_SEPARATOR_LENGTH")
    void testLogSeparatorLength() {
        assertEquals(60, Constants.LOG_SEPARATOR_LENGTH);
    }

    @Test
    @Order(4)
    @DisplayName("测试日志配置常量 - DEFAULT_LOG_FILE_NAME")
    void testDefaultLogFileName() {
        assertEquals("cilexec.log", Constants.DEFAULT_LOG_FILE_NAME);
    }

    @Test
    @Order(5)
    @DisplayName("测试日志文件名不为 null")
    void testDefaultLogFileNameNotNull() {
        assertNotNull(Constants.DEFAULT_LOG_FILE_NAME);
    }

    @Test
    @Order(6)
    @DisplayName("测试日志文件名包含扩展名")
    void testDefaultLogFileNameHasExtension() {
        assertTrue(Constants.DEFAULT_LOG_FILE_NAME.contains(".log"));
    }

    @Test
    @Order(7)
    @DisplayName("测试 NANOS_TO_MILLIS 为正数")
    void testNanosToMillisIsPositive() {
        assertTrue(Constants.NANOS_TO_MILLIS > 0);
    }

    @Test
    @Order(8)
    @DisplayName("测试 SCHEDULER_SLEEP_MS 为正数")
    void testSchedulerSleepMsIsPositive() {
        assertTrue(Constants.SCHEDULER_SLEEP_MS > 0);
    }

    @Test
    @Order(9)
    @DisplayName("测试 LOG_SEPARATOR_LENGTH 为正数")
    void testLogSeparatorLengthIsPositive() {
        assertTrue(Constants.LOG_SEPARATOR_LENGTH > 0);
    }
}