package com.follarce.log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 日志系统 —— 基于 PrintWriter + FileWriter，追加模式。
 */
public final class Logger {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static PrintWriter writer;
    private static Level currentLevel = Level.DEBUG;
    private static String logPath;
    private static boolean initialized = false;
    private static boolean closed = false;

    private Logger() {}

    /**
     * 初始化日志系统。
     */
    public static synchronized void init(String path) {
        if (initialized) return;
        logPath = path;
        try {
            File logFile = new File(path);
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            writer = new PrintWriter(new FileWriter(logFile, true), true);
            initialized = true;
            log(Level.INFO, "Logger initialized, path=" + path);
        } catch (IOException e) {
            System.err.println("[Logger] Failed to init logger at " + path + ": " + e.getMessage());
            // fallback to stderr
            writer = new PrintWriter(System.err, true);
            initialized = true;
        }
    }

    /**
     * 初始化日志系统，默认路径为 cilexec.log。
     */
    public static synchronized void init() {
        init("cilexec.log");
    }

    public static synchronized void setLevel(Level level) {
        currentLevel = level;
    }

    public static synchronized void setLogPath(String path) {
        logPath = path;
    }

    public static synchronized void log(Level level, String message) {
        if (!initialized) init();
        if (level.ordinal() < currentLevel.ordinal()) return;
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        writer.printf("[%s] [%s] %s%n", timestamp, level, message);
    }

    public static void debug(String message) { log(Level.DEBUG, message); }
    public static void info(String message) { log(Level.INFO, message); }
    public static void warn(String message) { log(Level.WARN, message); }
    public static void error(String message) { log(Level.ERROR, message); }

    public static synchronized void logStartup() {
        info("=== CilExec starting up ===");
    }

    public static synchronized void logShutdown() {
        info("=== CilExec shutting down ===");
    }

    public static synchronized void logException(String context, Throwable e) {
        if (!initialized) init();
        error(context + ": " + e.getMessage());
        if (writer != null) {
            e.printStackTrace(writer);
        }
    }

    /**
     * 关闭日志系统。
     */
    public static synchronized void close() {
        if (closed) return;
        closed = true;
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
        initialized = false;
    }

    public static synchronized boolean isInitialized() {
        return initialized;
    }
}
