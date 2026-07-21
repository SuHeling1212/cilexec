package com.follarce.extension.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Tokenizes commands without invoking a host operating-system shell. */
public final class ShellCommandParser {
    private ShellCommandParser() {}

    public static Optional<ShellCommand> parse(String line) {
        List<String> tokens = tokenize(line);
        if (tokens.isEmpty()) return Optional.empty();
        return Optional.of(new ShellCommand(
                tokens.getFirst().toLowerCase(Locale.ROOT), tokens.subList(1, tokens.size())));
    }

    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        if (line == null || line.isBlank()) return tokens;

        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        boolean tokenStarted = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (escaping) {
                current.append(value);
                escaping = false;
                tokenStarted = true;
                continue;
            }
            if (value == '\\') {
                escaping = true;
                tokenStarted = true;
                continue;
            }
            if (quote != 0) {
                if (value == quote) {
                    quote = 0;
                } else {
                    current.append(value);
                }
                tokenStarted = true;
                continue;
            }
            if (value == '\'' || value == '"') {
                quote = value;
                tokenStarted = true;
                continue;
            }
            if (Character.isWhitespace(value)) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                continue;
            }
            current.append(value);
            tokenStarted = true;
        }

        if (escaping) throw new IllegalArgumentException("Trailing escape character");
        if (quote != 0) throw new IllegalArgumentException("Unclosed quote");
        if (tokenStarted) tokens.add(current.toString());
        return tokens;
    }
}
