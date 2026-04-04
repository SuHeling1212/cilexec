package com.follarce.basicUtil;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConstantsTest {

    @Test
    @Order(1)
    @DisplayName("测试系统路径常量 - SYSTEM_PROCESS_PATH")
    void testSystemProcessPath() {
        assertEquals("/system/process/", Constants.SYSTEM_PROCESS_PATH);
    }

    @Test
    @Order(2)
    @DisplayName("测试系统路径常量 - SYSTEM_CONFIG_PATH")
    void testSystemConfigPath() {
        assertEquals("/system/config/", Constants.SYSTEM_CONFIG_PATH);
    }

    @Test
    @Order(3)
    @DisplayName("测试系统路径常量 - SYSTEM_SWAP_PATH")
    void testSystemSwapPath() {
        assertEquals("/system/swap/", Constants.SYSTEM_SWAP_PATH);
    }

    @Test
    @Order(4)
    @DisplayName("测试系统路径常量 - USER_LOCAL_APP_PATH")
    void testUserLocalAppPath() {
        assertEquals("/user/local/app/", Constants.USER_LOCAL_APP_PATH);
    }

    @Test
    @Order(5)
    @DisplayName("测试系统路径常量 - USER_HOME_PREFIX")
    void testUserHomePrefix() {
        assertEquals("/user/", Constants.USER_HOME_PREFIX);
    }

    @Test
    @Order(6)
    @DisplayName("测试默认用户常量 - DEFAULT_USER_LOCAL")
    void testDefaultUserLocal() {
        assertEquals("local", Constants.DEFAULT_USER_LOCAL);
    }

    @Test
    @Order(7)
    @DisplayName("测试默认用户常量 - DEFAULT_PASSWORD_LOCAL")
    void testDefaultPasswordLocal() {
        assertEquals("local", Constants.DEFAULT_PASSWORD_LOCAL);
    }

    @Test
    @Order(8)
    @DisplayName("测试文件扩展名常量 - JSON_EXTENSION")
    void testJsonExtension() {
        assertEquals(".json", Constants.JSON_EXTENSION);
    }

    @Test
    @Order(9)
    @DisplayName("测试文件扩展名常量 - META_EXTENSION")
    void testMetaExtension() {
        assertEquals(".META", Constants.META_EXTENSION);
    }

    @Test
    @Order(10)
    @DisplayName("测试进程常量 - INIT_PROCESS_NAME")
    void testInitProcessName() {
        assertEquals("INIT", Constants.INIT_PROCESS_NAME);
    }

    @Test
    @Order(11)
    @DisplayName("测试进程常量 - INIT_PID")
    void testInitPid() {
        assertEquals(1, Constants.INIT_PID);
    }

    @Test
    @Order(12)
    @DisplayName("测试用户配置文件常量 - USERS_CONFIG_FILE")
    void testUsersConfigFile() {
        assertEquals("/system/config/users.json", Constants.USERS_CONFIG_FILE);
    }

    @Test
    @Order(13)
    @DisplayName("测试错误码常量 - ERROR")
    void testErrorConstant() {
        assertEquals("ERROR", Constants.ERROR);
    }

    @Test
    @Order(14)
    @DisplayName("测试错误码常量 - SUCCESS")
    void testSuccessConstant() {
        assertEquals("SUCCESS", Constants.SUCCESS);
    }

    @Test
    @Order(15)
    @DisplayName("测试元数据标记常量 - META_START")
    void testMetaStart() {
        assertEquals("#<META>", Constants.META_START);
    }

    @Test
    @Order(16)
    @DisplayName("测试元数据标记常量 - META_END")
    void testMetaEnd() {
        assertEquals("<META>#", Constants.META_END);
    }

    @Test
    @Order(17)
    @DisplayName("测试权限常量 - PERMISSION_READ")
    void testPermissionRead() {
        assertEquals("read", Constants.PERMISSION_READ);
    }

    @Test
    @Order(18)
    @DisplayName("测试权限常量 - PERMISSION_WRITE")
    void testPermissionWrite() {
        assertEquals("write", Constants.PERMISSION_WRITE);
    }

    @Test
    @Order(19)
    @DisplayName("测试权限常量 - PERMISSION_EXECUTE")
    void testPermissionExecute() {
        assertEquals("execute", Constants.PERMISSION_EXECUTE);
    }

    @Test
    @Order(20)
    @DisplayName("测试锁状态常量 - LOCKED_BY")
    void testLockedBy() {
        assertEquals("lockedBy", Constants.LOCKED_BY);
    }

    @Test
    @Order(21)
    @DisplayName("测试锁状态常量 - IS_LOCKED")
    void testIsLocked() {
        assertEquals("isLocked", Constants.IS_LOCKED);
    }

    @Test
    @Order(22)
    @DisplayName("测试时间格式常量 - TIME_ARRAY_SIZE")
    void testTimeArraySize() {
        assertEquals(7, Constants.TIME_ARRAY_SIZE);
    }

    @Test
    @Order(23)
    @DisplayName("测试进程配置常量 - PROCESS_TICK_MS")
    void testProcessTickMs() {
        assertEquals(10, Constants.PROCESS_TICK_MS);
    }

    @Test
    @Order(24)
    @DisplayName("测试进程配置常量 - TIME_DIVISOR")
    void testTimeDivisor() {
        assertEquals(1000, Constants.TIME_DIVISOR);
    }

    @Test
    @Order(25)
    @DisplayName("测试 Socket 配置常量 - DEFAULT_TIMEOUT")
    void testDefaultTimeout() {
        assertEquals(10000, Constants.DEFAULT_TIMEOUT);
    }

    @Test
    @Order(26)
    @DisplayName("测试 Socket 配置常量 - BUFFER_SIZE")
    void testBufferSize() {
        assertEquals(8192, Constants.BUFFER_SIZE);
    }

    @Test
    @Order(27)
    @DisplayName("测试 Socket 配置常量 - SERVER_SOCKET_TIMEOUT")
    void testServerSocketTimeout() {
        assertEquals(1000, Constants.SERVER_SOCKET_TIMEOUT);
    }

    @Test
    @Order(28)
    @DisplayName("测试 Socket 配置常量 - SOCKET_READ_TIMEOUT")
    void testSocketReadTimeout() {
        assertEquals(5000, Constants.SOCKET_READ_TIMEOUT);
    }

    @Test
    @Order(29)
    @DisplayName("测试 Socket 配置常量 - RECEIVE_THREAD_SLEEP_MS")
    void testReceiveThreadSleepMs() {
        assertEquals(100, Constants.RECEIVE_THREAD_SLEEP_MS);
    }

    @Test
    @Order(30)
    @DisplayName("测试文件大小单位常量 - SIZE_UNIT_KB")
    void testSizeUnitKb() {
        assertEquals(1024, Constants.SIZE_UNIT_KB);
    }

    @Test
    @Order(31)
    @DisplayName("测试文件大小单位常量 - SIZE_UNIT_MB")
    void testSizeUnitMb() {
        assertEquals(1024 * 1024, Constants.SIZE_UNIT_MB);
        assertEquals(1048576, Constants.SIZE_UNIT_MB);
    }

    @Test
    @Order(32)
    @DisplayName("测试文件大小单位常量 - SIZE_UNIT_GB")
    void testSizeUnitGb() {
        assertEquals(1024 * 1024 * 1024, Constants.SIZE_UNIT_GB);
        assertEquals(1073741824, Constants.SIZE_UNIT_GB);
    }

    @Test
    @Order(33)
    @DisplayName("测试文件大小单位之间的关系")
    void testSizeUnitRelationships() {
        assertEquals(Constants.SIZE_UNIT_KB * 1024, Constants.SIZE_UNIT_MB);
        assertEquals(Constants.SIZE_UNIT_MB * 1024, Constants.SIZE_UNIT_GB);
    }

    @Test
    @Order(34)
    @DisplayName("测试路径常量以斜杠结尾")
    void testPathConstantsEndWithSlash() {
        assertTrue(Constants.SYSTEM_PROCESS_PATH.endsWith("/"));
        assertTrue(Constants.SYSTEM_CONFIG_PATH.endsWith("/"));
        assertTrue(Constants.SYSTEM_SWAP_PATH.endsWith("/"));
        assertTrue(Constants.USER_LOCAL_APP_PATH.endsWith("/"));
        assertTrue(Constants.USER_HOME_PREFIX.endsWith("/"));
    }

    @Test
    @Order(35)
    @DisplayName("测试扩展名常量以点开头")
    void testExtensionConstantsStartWithDot() {
        assertTrue(Constants.JSON_EXTENSION.startsWith("."));
        assertTrue(Constants.META_EXTENSION.startsWith("."));
    }

    @Test
    @Order(36)
    @DisplayName("测试所有常量不为 null")
    void testAllConstantsNotNull() {
        assertNotNull(Constants.SYSTEM_PROCESS_PATH);
        assertNotNull(Constants.SYSTEM_CONFIG_PATH);
        assertNotNull(Constants.SYSTEM_SWAP_PATH);
        assertNotNull(Constants.USER_LOCAL_APP_PATH);
        assertNotNull(Constants.USER_HOME_PREFIX);
        assertNotNull(Constants.DEFAULT_USER_LOCAL);
        assertNotNull(Constants.DEFAULT_PASSWORD_LOCAL);
        assertNotNull(Constants.JSON_EXTENSION);
        assertNotNull(Constants.META_EXTENSION);
        assertNotNull(Constants.INIT_PROCESS_NAME);
        assertNotNull(Constants.USERS_CONFIG_FILE);
        assertNotNull(Constants.ERROR);
        assertNotNull(Constants.SUCCESS);
        assertNotNull(Constants.META_START);
        assertNotNull(Constants.META_END);
        assertNotNull(Constants.PERMISSION_READ);
        assertNotNull(Constants.PERMISSION_WRITE);
        assertNotNull(Constants.PERMISSION_EXECUTE);
        assertNotNull(Constants.LOCKED_BY);
        assertNotNull(Constants.IS_LOCKED);
    }

    @Test
    @Order(37)
    @DisplayName("测试常量值唯一性 - 错误码")
    void testErrorCodeUniqueness() {
        assertNotEquals(Constants.ERROR, Constants.SUCCESS);
    }

    @Test
    @Order(38)
    @DisplayName("测试常量值唯一性 - 权限")
    void testPermissionUniqueness() {
        assertNotEquals(Constants.PERMISSION_READ, Constants.PERMISSION_WRITE);
        assertNotEquals(Constants.PERMISSION_READ, Constants.PERMISSION_EXECUTE);
        assertNotEquals(Constants.PERMISSION_WRITE, Constants.PERMISSION_EXECUTE);
    }

    @Test
    @Order(39)
    @DisplayName("测试元数据标记配对")
    void testMetaMarkersPairing() {
        String testContent = Constants.META_START + "\n{\"test\": \"value\"}\n" + Constants.META_END;
        assertTrue(testContent.contains(Constants.META_START));
        assertTrue(testContent.contains(Constants.META_END));
        assertTrue(testContent.indexOf(Constants.META_START) < testContent.indexOf(Constants.META_END));
    }

    @Test
    @Order(40)
    @DisplayName("测试 INIT_PID 是正整数")
    void testInitPidIsPositive() {
        assertTrue(Constants.INIT_PID > 0);
    }
}
