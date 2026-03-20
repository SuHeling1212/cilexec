package com.follarce.util;

/**
 * 系统常量定义
 * 统一管理所有魔法字符串和配置值
 */
public class Constants {

    // 系统路径
    public static final String SYSTEM_PROCESS_PATH = "/system/process/";
    public static final String SYSTEM_CONFIG_PATH = "/system/config/";
    public static final String SYSTEM_SWAP_PATH = "/system/swap/";
    public static final String USER_LOCAL_APP_PATH = "/user/local/app/";
    public static final String USER_HOME_PREFIX = "/user/";

    // 默认用户
    public static final String DEFAULT_USER_LOCAL = "local";
    public static final String DEFAULT_PASSWORD_LOCAL = "local";

    // 文件扩展名
    public static final String JSON_EXTENSION = ".json";
    public static final String META_EXTENSION = ".META";

    // 进程文件
    public static final String INIT_PROCESS_NAME = "INIT";
    public static final int INIT_PID = 1;

    // 用户配置文件
    public static final String USERS_CONFIG_FILE = "/system/config/users.json";

    // 错误码
    public static final String ERROR = "ERROR";
    public static final String SUCCESS = "SUCCESS";

    // 元数据标记
    public static final String META_START = "#<META>";
    public static final String META_END = "<META>#";

    // 权限
    public static final String PERMISSION_READ = "read";
    public static final String PERMISSION_WRITE = "write";
    public static final String PERMISSION_EXECUTE = "execute";

    // 锁状态
    public static final String LOCKED_BY = "lockedBy";
    public static final String IS_LOCKED = "isLocked";

    // 时间格式
    public static final int TIME_ARRAY_SIZE = 7; // 年,月,日,时,分,秒,毫秒
}
