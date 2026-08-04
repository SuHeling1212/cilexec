package com.follarce.app;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

public enum ApplicationCommand {
    TERMINAL,
    RUNTIME,
    MIGRATE,
    EXPORT,
    PACKAGE_BUILD,
    HOST_MOVE;

    public static ApplicationCommand parse(String[] arguments) {
        if (arguments == null || arguments.length == 0) return TERMINAL;
        if (arguments[0] == null) throw usage();
        return switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "terminal", "repl" -> exactly(arguments, TERMINAL);
            case "runtime" -> exactly(arguments, RUNTIME);
            case "migrate" -> exactly(arguments, MIGRATE);
            case "export" -> {
                exportPath(arguments);
                yield EXPORT;
            }
            case "package" -> {
                packageSourcePath(arguments);
                packageOutputPath(arguments);
                yield PACKAGE_BUILD;
            }
            case "host" -> {
                hostSourcePath(arguments);
                hostTargetPath(arguments);
                hostUsername(arguments);
                yield HOST_MOVE;
            }
            default -> throw usage();
        };
    }

    public static Path exportPath(String[] arguments) {
        if (arguments == null || arguments.length != 2 || arguments[0] == null
                || !"export".equalsIgnoreCase(arguments[0]) || arguments[1] == null
                || arguments[1].isBlank()) {
            throw usage();
        }
        try {
            Path path = Path.of(arguments[1]);
            Path fileName = path.getFileName();
            if (fileName == null || !fileName.toString().endsWith(".db")) {
                throw usage();
            }
            return path;
        } catch (InvalidPathException invalid) {
            throw usage();
        }
    }

    public static Path packageSourcePath(String[] arguments) {
        packageArguments(arguments);
        return safePath(arguments[2]);
    }

    public static Path packageOutputPath(String[] arguments) {
        packageArguments(arguments);
        Path path = safePath(arguments[3]);
        Path fileName = path.getFileName();
        if (fileName == null || !fileName.toString().endsWith(".db")) {
            throw usage();
        }
        return path;
    }

    public static Path hostSourcePath(String[] arguments) {
        hostArguments(arguments);
        return safePath(arguments[2]);
    }

    public static String hostTargetPath(String[] arguments) {
        hostArguments(arguments);
        String target = arguments[3].trim();
        if (!target.startsWith("/") || target.equals("/")) throw usage();
        return target;
    }

    public static String hostUsername(String[] arguments) {
        hostArguments(arguments);
        return arguments[4].trim();
    }

    private static void hostArguments(String[] arguments) {
        if (arguments == null || arguments.length != 5
                || arguments[0] == null || !"host".equalsIgnoreCase(arguments[0])
                || arguments[1] == null || !"move".equalsIgnoreCase(arguments[1])
                || arguments[2] == null || arguments[2].isBlank()
                || arguments[3] == null || arguments[3].isBlank()
                || arguments[4] == null || arguments[4].isBlank()) {
            throw usage();
        }
    }

    private static void packageArguments(String[] arguments) {
        if (arguments == null || arguments.length != 4 || arguments[0] == null
                || !"package".equalsIgnoreCase(arguments[0]) || arguments[1] == null
                || !"build".equalsIgnoreCase(arguments[1]) || arguments[2] == null
                || arguments[2].isBlank() || arguments[3] == null || arguments[3].isBlank()) {
            throw usage();
        }
    }

    private static Path safePath(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException invalid) {
            throw usage();
        }
    }

    private static ApplicationCommand exactly(String[] arguments, ApplicationCommand command) {
        if (arguments.length != 1) throw usage();
        return command;
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "Usage: cilexec [terminal|runtime|migrate|export <output.db>|package build <source-dir> <output.db>|host move <source-file> <absolute-vfs-path> <username>]");
    }
}
