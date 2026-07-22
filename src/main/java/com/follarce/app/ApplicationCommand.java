package com.follarce.app;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

public enum ApplicationCommand {
    RUNTIME,
    MIGRATE,
    EXPORT;

    public static ApplicationCommand parse(String[] arguments) {
        if (arguments == null || arguments.length == 0) return RUNTIME;
        if (arguments[0] == null) throw usage();
        return switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "runtime" -> exactly(arguments, RUNTIME);
            case "migrate" -> exactly(arguments, MIGRATE);
            case "export" -> {
                exportPath(arguments);
                yield EXPORT;
            }
            default -> throw usage();
        };
    }

    public static Path exportPath(String[] arguments) {
        if (arguments == null || arguments.length != 2 || arguments[1] == null
                || arguments[1].isBlank()) {
            throw usage();
        }
        try {
            Path path = Path.of(arguments[1]);
            if (path.getFileName() == null || !path.getFileName().toString().endsWith(".db")) {
                throw usage();
            }
            return path;
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
                "Usage: cilexec [runtime|migrate|export <output.db>]");
    }
}
