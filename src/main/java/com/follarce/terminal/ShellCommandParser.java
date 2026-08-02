package com.follarce.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ShellCommandParser {
    public ShellCommand parse(String line) {
        List<String> words = words(line == null ? "" : line);
        if (words.isEmpty()) {
            throw new IllegalArgumentException("Command is empty");
        }
        return switch (words.getFirst().toLowerCase(Locale.ROOT)) {
            case "help" -> exact(words, 1, new ShellCommand.Help());
            case "cd" -> exact(words, 2, new ShellCommand.ChangeDirectory(words.get(1)));
            case "pwd" -> exact(words, 1, new ShellCommand.WorkingDirectory());
            case "ls" -> listDirectory(words);
            case "clear", "cls" -> exact(words, 1, new ShellCommand.Clear());
            case "logout" -> exact(words, 1, new ShellCommand.Logout());
            case "exit", "quit" -> exact(words, 1, new ShellCommand.Exit());
            case "shutdown" -> exact(words, 1, new ShellCommand.Shutdown());
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

    private static ShellCommand listDirectory(List<String> words) {
        if (words.size() > 2) {
            throw new IllegalArgumentException("ls accepts at most one path");
        }
        return new ShellCommand.ListDirectory(words.size() == 2
                ? Optional.of(words.get(1)) : Optional.empty());
    }

    private static <T extends ShellCommand> T exact(List<String> words, int size, T command) {
        if (words.size() != size) {
            throw new IllegalArgumentException("Unexpected command arguments");
        }
        return command;
    }
}
