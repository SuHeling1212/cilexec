package com.follarce.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class ShellCommandParser {
    public ShellCommand parse(String line) {
        List<String> words = words(line == null ? "" : line);
        if (words.isEmpty()) {
            throw new IllegalArgumentException("Command is empty");
        }
        return switch (words.getFirst().toLowerCase(Locale.ROOT)) {
            case "help" -> exact(words, 1, new ShellCommand.Help());
            case "ps" -> exact(words, 1, new ShellCommand.Processes());
            case "inspect" -> exact(words, 2, new ShellCommand.Inspect(pid(words.get(1))));
            case "run" -> run(words);
            case "pause" -> exact(words, 2, new ShellCommand.Pause(pid(words.get(1))));
            case "continue", "resume" -> exact(words, 2, new ShellCommand.Resume(pid(words.get(1))));
            case "kill" -> exact(words, 2, new ShellCommand.Kill(pid(words.get(1))));
            case "attach" -> exact(words, 2, new ShellCommand.Attach(pid(words.get(1))));
            case "submit" -> submit(words);
            case "effect" -> effect(words);
            case "shutdown" -> exact(words, 1, new ShellCommand.Shutdown());
            case "exit", "quit" -> exact(words, 1, new ShellCommand.Exit());
            default -> throw new IllegalArgumentException("Unknown command: " + words.getFirst());
        };
    }

    static List<String> words(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (escaped || quoted) {
            throw new IllegalArgumentException("Unterminated escape or quote");
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return List.copyOf(result);
    }

    private static ShellCommand run(List<String> words) {
        if (words.size() < 2) {
            throw new IllegalArgumentException("run requires a VFS path");
        }
        Optional<String> user = Optional.empty();
        Optional<String> name = Optional.empty();
        for (int index = 2; index < words.size(); index += 2) {
            if (index + 1 >= words.size()) {
                throw new IllegalArgumentException("Missing value for " + words.get(index));
            }
            switch (words.get(index)) {
                case "--user" -> user = Optional.of(words.get(index + 1));
                case "--name" -> name = Optional.of(words.get(index + 1));
                default -> throw new IllegalArgumentException("Unknown run option: " + words.get(index));
            }
        }
        return new ShellCommand.Run(words.get(1), user, name);
    }

    private static ShellCommand submit(List<String> words) {
        if (words.size() < 3) {
            throw new IllegalArgumentException("submit requires session UUID and complete input");
        }
        return new ShellCommand.Submit(uuid(words.get(1)), String.join(" ", words.subList(2, words.size())));
    }

    private static ShellCommand effect(List<String> words) {
        if (words.size() != 4 || !"resolve".equals(words.get(1))) {
            throw new IllegalArgumentException("Usage: effect resolve <uuid> <completed|failed|retry>");
        }
        ShellCommand.ResolveEffect.Decision decision;
        try {
            decision = ShellCommand.ResolveEffect.Decision.valueOf(words.get(3).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown effect decision: " + words.get(3), exception);
        }
        return new ShellCommand.ResolveEffect(uuid(words.get(2)), decision);
    }

    private static long pid(String value) {
        try {
            long pid = Long.parseLong(value);
            if (pid < 1) {
                throw new NumberFormatException("not positive");
            }
            return pid;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid PID: " + value, exception);
        }
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid UUID: " + value, exception);
        }
    }

    private static <T extends ShellCommand> T exact(List<String> words, int size, T command) {
        if (words.size() != size) {
            throw new IllegalArgumentException("Unexpected command arguments");
        }
        return command;
    }
}
