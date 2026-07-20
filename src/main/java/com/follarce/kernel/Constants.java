package com.follarce.kernel;

import java.util.List;

/**
 * 系统常量定义 —— 所有魔数集中管理。
 */
public final class Constants {

    private Constants() {}

    // ── 调度 ──
    public static final long PROCESS_TICK_MS = 10L;

    // 调度器休眠间隔（每轮调度后的休眠时间）
    public static final long SCHEDULER_TICK_MS = 50L;

    // ── 进程优先级 ──
    public static final int PRIORITY_HIGH = 5;
    public static final int PRIORITY_NORMAL = 3;
    public static final int PRIORITY_LOW = 1;
    public static final int DEFAULT_PRIORITY = PRIORITY_NORMAL;

    // ── 执行引擎模式 ──
    // true  = 每进程一个虚拟线程（需 Java 21+）
    // false = 单线程调度器（当前模式，兼容 Java 11+）


    // ── 网络 ──
    public static final int DEFAULT_TIMEOUT = 10000;
    public static final int BUFFER_SIZE = 8192;

    // ── 路径 ──
    public static final String SYSTEM_PROCESS_PATH = "/system/process/";
    public static final String SYSTEM_PROCESS_INBOX_PATH = "/system/process-inbox/";
    public static final String SYSTEM_EFFECT_PATH = "/system/effects/";
    public static final String SYSTEM_APPLIED_EFFECT_PATH = "/system/effects/applied/";
    public static final String SYSTEM_FORK_EFFECT_PATH = "/system/effects/forks/";
    public static final String SYSTEM_CONFIG_PATH = "/system/config/";
    public static final String SYSTEM_SWAP_PATH = "/system/swap/";
    public static final String SYSTEM_APP_PATH = "/system/app/";
    public static final String SYSTEM_APP_PACKAGE_PATH = "/system/app/package/";
    public static final String SYSTEM_APP_DATA_PATH = "/system/app/data/";
    public static final String SYSTEM_PACKAGE_OBJECTS_PATH = "/system/app/package/objects/";
    public static final String SYSTEM_PACKAGE_MANAGER_DATA_PATH = "/system/app/data/package/";
    public static final String SYSTEM_PACKAGE_REFS_PATH = "/system/app/data/package/refs/";
    public static final String SYSTEM_PACKAGE_STAGING_PATH = "/system/app/data/package/staging/";
    public static final String SYSTEM_PACKAGE_REPOSITORY_PATH = "/system/app/data/package/repository/";
    public static final String USER_HOME_PREFIX = "/user/";
    public static final String USER_LOCAL_PATH = "/user/local/";
    public static final String USER_LOCAL_APP_PATH = "/user/local/app/";
    public static final String USER_LOCAL_APP_PACKAGE_PATH = "/user/local/app/package/";
    public static final String USER_LOCAL_APP_DATA_PATH = "/user/local/app/data/";
    public static final String USER_LOCAL_PACKAGE_DATA_PATH = "/user/local/app/data/package/";

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
    public static final long DEFAULT_FILE_LOCK_LEASE_MS = 30_000L;

    // ── 交换池类型 ──
    public static final String SWAP_TYPE_ALWAYS = "always";
    public static final String SWAP_TYPE_SYNC = "sync";
    public static final String SWAP_TYPE_TIMES_PREFIX = "times";

    // ── 默认名称 ──
    public static final String DEFAULT_DIR_OWNER = "local";

    // ── 路径别名 ──
    public static final List<String> VFS_ROOT_DIRS = List.of(
            "/system/", SYSTEM_APP_PATH, SYSTEM_APP_PACKAGE_PATH, SYSTEM_APP_DATA_PATH,
            SYSTEM_PACKAGE_OBJECTS_PATH, SYSTEM_PACKAGE_MANAGER_DATA_PATH,
            SYSTEM_PACKAGE_REFS_PATH, SYSTEM_PACKAGE_STAGING_PATH, SYSTEM_PACKAGE_REPOSITORY_PATH,
            "/system/config/",
            "/system/process/", "/system/process-inbox/", "/system/effects/",
            "/system/effects/applied/", "/system/effects/forks/", "/system/swap/",
            "/user/", "/user/local/", USER_LOCAL_APP_PATH,
            USER_LOCAL_APP_PACKAGE_PATH, USER_LOCAL_APP_DATA_PATH, USER_LOCAL_PACKAGE_DATA_PATH
    );
}
