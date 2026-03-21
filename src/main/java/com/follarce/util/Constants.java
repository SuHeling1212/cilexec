package com.follarce.util;

/**
 * System constants definition
 * Unified management of all magic strings and configuration values
 */
public class Constants {

    // System paths
    public static final String SYSTEM_PROCESS_PATH = "/system/process/";
    public static final String SYSTEM_CONFIG_PATH = "/system/config/";
    public static final String SYSTEM_SWAP_PATH = "/system/swap/";
    public static final String USER_LOCAL_APP_PATH = "/user/local/app/";
    public static final String USER_HOME_PREFIX = "/user/";

    // Default user
    public static final String DEFAULT_USER_LOCAL = "local";
    public static final String DEFAULT_PASSWORD_LOCAL = "local";

    // File extensions
    public static final String JSON_EXTENSION = ".json";
    public static final String META_EXTENSION = ".META";

    // Process files
    public static final String INIT_PROCESS_NAME = "INIT";
    public static final int INIT_PID = 1;

    // User configuration file
    public static final String USERS_CONFIG_FILE = "/system/config/users.json";

    // Error codes
    public static final String ERROR = "ERROR";
    public static final String SUCCESS = "SUCCESS";

    // Metadata markers
    public static final String META_START = "#<META>";
    public static final String META_END = "<META>#";

    // Permissions
    public static final String PERMISSION_READ = "read";
    public static final String PERMISSION_WRITE = "write";
    public static final String PERMISSION_EXECUTE = "execute";

    // Lock status
    public static final String LOCKED_BY = "lockedBy";
    public static final String IS_LOCKED = "isLocked";

    // Time format
    public static final int TIME_ARRAY_SIZE = 7; // Year, Month, Day, Hour, Minute, Second, Millisecond
}
