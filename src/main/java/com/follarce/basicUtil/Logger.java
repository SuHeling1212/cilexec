package com.follarce.basicUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple logger
 * Provides unified log output interface
 */
public class Logger {

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static PrintWriter logWriter;
    private static String customLogPath = null;

    static {
        initLogFile();
    }

    private static String getWorkDirectory() {
        try {
            String path = Logger.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            File jarFile = new File(path);
            return jarFile.getParent();
        } catch (Exception e) {
            return System.getProperty("user.dir");
        }
    }

    private static void initLogFile() {
        try {
            String logPath;
            if (customLogPath != null && !customLogPath.isEmpty()) {
                logPath = customLogPath;
            } else {
                String workDir = getWorkDirectory();
                logPath = workDir + File.separator + Constants.DEFAULT_LOG_FILE_NAME;
            }
            
            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            logWriter = new PrintWriter(new FileWriter(logPath, true));
        } catch (IOException e) {
            System.err.println("Failed to initialize log file: " + e.getMessage());
        }
    }

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    private static Level currentLevel = Level.INFO;

    public static void setLevel(Level level) {
        currentLevel = level;
    }

    public static void setLogPath(String path) {
        if (logWriter != null) {
            logWriter.close();
            logWriter = null;
        }
        customLogPath = path;
        initLogFile();
    }

    public static void debug(String message) {
        if (currentLevel.ordinal() <= Level.DEBUG.ordinal()) {
            log("DEBUG", message);
        }
    }

    public static void info(String message) {
        if (currentLevel.ordinal() <= Level.INFO.ordinal()) {
            log("INFO", message);
        }
    }

    public static void warn(String message) {
        if (currentLevel.ordinal() <= Level.WARN.ordinal()) {
            log("WARN", message);
        }
    }

    public static void error(String message) {
        if (currentLevel.ordinal() <= Level.ERROR.ordinal()) {
            log("ERROR", message);
        }
    }

    public static void error(String message, Throwable throwable) {
        if (currentLevel.ordinal() <= Level.ERROR.ordinal()) {
            log("ERROR", message);
            if (throwable != null) {
                logException(throwable);
            }
        }
    }

    private static void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(formatter);
        String output = String.format("[%s] [%s] %s", timestamp, level, message);
        if (logWriter != null) {
            logWriter.println(output);
            logWriter.flush();
        }
    }

    private static void logException(Throwable throwable) {
        if (logWriter != null) {
            throwable.printStackTrace(logWriter);
            logWriter.flush();
        }
    }

    public static void close() {
        if (logWriter != null) {
            logWriter.close();
        }
    }

    public static void logStartup() {
        String separator = "=".repeat(Constants.LOG_SEPARATOR_LENGTH);
        String timestamp = LocalDateTime.now().format(formatter);
        if (logWriter != null) {
            logWriter.println();
            logWriter.println(separator);
            logWriter.println("[" + timestamp + "] [STARTUP] Application started");
            logWriter.println(separator);
            logWriter.flush();
        }
    }

    public static void logShutdown() {
        String separator = "=".repeat(Constants.LOG_SEPARATOR_LENGTH);
        String timestamp = LocalDateTime.now().format(formatter);
        if (logWriter != null) {
            logWriter.println(separator);
            logWriter.println("[" + timestamp + "] [SHUTDOWN] Application ended");
            logWriter.println(separator);
            logWriter.println();
            logWriter.flush();
        }
    }
}
