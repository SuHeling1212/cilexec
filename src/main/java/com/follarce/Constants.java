package com.follarce;

import java.util.List;

/**
 * 系统常量定义 —— 所有魔数集中管理。
 */
public final class Constants {

    private Constants() {}

    // ── 调度 ──
    public static final long PROCESS_TICK_MS = 10L;
    public static final long SCHEDULER_SLEEP_MS = 100L;

    // ── 网络 ──
    public static final int DEFAULT_TIMEOUT = 10000;
    public static final int BUFFER_SIZE = 8192;

    // ── 路径 ──
    public static final String SYSTEM_PROCESS_PATH = "/system/process/";
    public static final String SYSTEM_CONFIG_PATH = "/system/config/";
    public static final String SYSTEM_SWAP_PATH = "/system/swap/";
    public static final String SYSTEM_APP_PATH = "/system/app/";
    public static final String USER_HOME_PREFIX = "/user/";
    public static final String USER_LOCAL_PATH = "/user/local/";
    public static final String USER_LOCAL_APP_PATH = "/user/local/app/";

    // ── 配置文件 ──
    public static final String CONFIG_INIT_JSON = "init.json";
    public static final String CONFIG_USERS_JSON = "users.json";
    public static final String CONFIG_ENV_JSON = "env.json";
    public static final String CONFIG_LOCAL_JSON = "local.json";
    public static final String INIT_FCL = "INIT.fcl";

    // ── 元数据 ──
    public static final String META_START = "#<META>";
    public static final String META_END = "<META>#";
    public static final String META_DIR_FILE = ".META";

    // ── 用户 ──
    public static final String DEFAULT_USER_LOCAL = "local";
    public static final String DEFAULT_PASSWORD_LOCAL = "local";
    public static final String THREAD_LOCAL_USER_KEY = "cilExecUser";
    public static final String THREAD_LOCAL_PID_KEY = "cilExecPid";

    // ── 进程 ──
    public static final int PID_INIT = 1;
    public static final String ERROR_MARKER = "ERROR";

    // ── 进程文件清理 ──
    // 正常结束后是否删除进程文件。true=删除，false=保留
    public static final boolean DELETE_PROCESS_FILE_ON_EXIT = false;

    // ── 权限 ──
    public static final String PERM_READ = "read";
    public static final String PERM_WRITE = "write";
    public static final String PERM_OWNER = "Owner";
    public static final String PERM_OTHERS = "Others";

    // ── 交换池类型 ──
    public static final String SWAP_TYPE_ALWAYS = "always";
    public static final String SWAP_TYPE_SYNC = "sync";
    public static final String SWAP_TYPE_TIMES_PREFIX = "times";

    // ── 默认名称 ──
    public static final String DEFAULT_DIR_OWNER = "local";

    // ── 路径别名 ──
    public static final List<String> VFS_ROOT_DIRS = List.of(
            "/system/", "/system/app/", "/system/config/",
            "/system/process/", "/system/swap/",
            "/user/", "/user/local/", "/user/local/app/"
    );
}
