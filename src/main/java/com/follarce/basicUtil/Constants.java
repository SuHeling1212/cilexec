package com.follarce.basicUtil;

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

    // Process configuration
    public static final int PROCESS_TICK_MS = 10; // Process execution interval in milliseconds
    public static final int TIME_DIVISOR = 1000; // Divisor for converting milliseconds to seconds

    // Socket configuration
    public static final int DEFAULT_TIMEOUT = 10000; // Default connection timeout in milliseconds (10 seconds)
    public static final int BUFFER_SIZE = 8192; // Socket buffer size in bytes (8KB)
    public static final int SERVER_SOCKET_TIMEOUT = 1000; // Server socket accept timeout in milliseconds
    public static final int SOCKET_READ_TIMEOUT = 5000; // Socket read timeout in milliseconds (5 seconds)
    public static final int RECEIVE_THREAD_SLEEP_MS = 100; // Receive thread sleep interval in milliseconds

    // File size units
    public static final int SIZE_UNIT_KB = 1024; // Kilobyte in bytes
    public static final int SIZE_UNIT_MB = 1024 * 1024; // Megabyte in bytes
    public static final int SIZE_UNIT_GB = 1024 * 1024 * 1024; // Gigabyte in bytes

    // Time conversion
    public static final int NANOS_TO_MILLIS = 1_000_000; // Nanoseconds to milliseconds divisor

    // Scheduler configuration
    public static final int SCHEDULER_SLEEP_MS = 100; // Scheduler thread sleep interval in milliseconds

    // Logging configuration
    public static final int LOG_SEPARATOR_LENGTH = 60; // Log separator repeat count
    public static final String DEFAULT_LOG_FILE_NAME = "cilexec.log"; // Default log file name

}
