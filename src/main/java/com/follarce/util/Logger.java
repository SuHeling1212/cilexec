package com.follarce.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple logger
 * Provides unified log output interface
 */
public class Logger {

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    private static Level currentLevel = Level.INFO;

    public static void setLevel(Level level) {
        currentLevel = level;
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
                throwable.printStackTrace();
            }
        }
    }

    private static void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(formatter);
        String output = String.format("[%s] [%s] %s", timestamp, level, message);
        if (level.equals("ERROR")) {
            System.err.println(output);
        } else {
            System.out.println(output);
        }
    }
}
