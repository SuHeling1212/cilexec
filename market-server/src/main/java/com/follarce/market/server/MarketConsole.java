package com.follarce.market.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Interactive management console for a CilExec market repository. Runs the HTTP service in
 * the background and manages publications on the shared repository and catalog, so changes
 * become visible to clients without restarting the server.
 */
final class MarketConsole implements AutoCloseable {
    private final ServerOptions options;
    private final MarketRepository repository;
    private final MarketHttpServer server;
    private final BufferedReader input;
    private final PrintStream output;
    private boolean running = true;

    MarketConsole(ServerOptions options, MarketRepository repository, MarketHttpServer server,
                  BufferedReader input, PrintStream output) {
        this.options = options;
        this.repository = repository;
        this.server = server;
        this.input = input;
        this.output = output;
    }

    /** Runs the command loop until EOF or the exit command. */
    void run() throws IOException {
        output.println("CilExec Market Console");
        output.println("Repository: " + options.repository());
        output.println("Catalog:    " + options.catalog());
        output.println("Serving:    http://" + options.bind().getHostAddress() + ":"
                + server.port() + "/market/v1/index.json");
        output.println("Type 'help' for commands, 'exit' to leave (the HTTP service stops "
                + "with this console).");
        while (running) {
            output.print("market> ");
            output.flush();
            String line = input.readLine();
            if (line == null) {
                break;
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                execute(trimmed);
            } catch (IllegalArgumentException | IOException | SQLException failure) {
                output.println("Error: " + failure.getMessage());
            }
        }
    }

    private void execute(String line) throws IOException, SQLException {
        List<String> words = split(line);
        String command = words.getFirst().toLowerCase(Locale.ROOT);
        switch (command) {
            case "help", "?" -> help(words);
            case "list", "ls" -> list(words);
            case "publish" -> publish(words);
            case "unpublish", "remove" -> unpublish(words);
            case "status", "info" -> status();
            case "exit", "quit" -> {
                running = false;
                output.println("Stopping market service.");
            }
            default -> output.println("Unknown command: " + command
                    + " (try 'help')");
        }
    }

    private void help(List<String> words) {
        output.println("""
                Commands:
                  list                      show every published package
                  publish <file.db>         publish a package database (asks for details)
                  publish <file.db> --summary TEXT --description TEXT --tags a,b
                                            publish directly, without confirmation
                  unpublish <coordinate>    remove a package from the catalog (file kept)
                  status                    show repository, catalog, and service state
                  help                      this help
                  exit                      stop the service and leave""");
    }

    private void list(List<String> words) {
        List<MarketRepository.PackageRecord> packages = repository.published();
        if (packages.isEmpty()) {
            output.println("No packages published.");
            return;
        }
        output.println("Published packages (" + packages.size() + "):");
        for (MarketRepository.PackageRecord record : packages) {
            String summary = record.summary().isEmpty() ? "" : " - " + record.summary();
            output.println("  " + record.coordinate() + "  [" + record.kind() + ", "
                    + human(record.bytes()) + "]"
                    + summary);
            output.println("      sha256=" + record.sha256());
            if (!record.dependencies().isEmpty()) {
                output.println("      dependencies=" + record.dependencies().size());
            }
        }
    }

    private void publish(List<String> words) throws IOException, SQLException {
        if (words.size() < 2) {
            output.println("Usage: publish <file.db> [--summary TEXT] [--description TEXT]"
                    + " [--tags a,b,c]");
            return;
        }
        Path source = Path.of(words.get(1));
        if (!Files.isRegularFile(source)) {
            output.println("Error: not a regular file: " + source);
            return;
        }
        // Flags publish directly without the confirmation questions, for scripts.
        String summary = null;
        String description = null;
        List<String> tags = null;
        for (int index = 2; index < words.size(); index++) {
            String option = words.get(index);
            String value = switch (option) {
                case "--summary", "--description", "--tags" -> {
                    if (index + 1 >= words.size()) {
                        throw new IllegalArgumentException("Missing value for " + option);
                    }
                    yield words.get(++index);
                }
                default -> throw new IllegalArgumentException("Unknown publish option: "
                        + option);
            };
            switch (option) {
                case "--summary" -> summary = value;
                case "--description" -> description = value;
                case "--tags" -> tags = splitTags(value);
                default -> throw new AssertionError(option);
            }
        }
        MarketRepository.StagedPackage staged;
        try {
            staged = repository.stage(source);
        } catch (IllegalArgumentException | SQLException failure) {
            output.println("Error: " + failure.getMessage());
            return;
        }
        Map<String, String> metadata;
        try {
            metadata = readMetadata(staged.source());
        } catch (SQLException failure) {
            output.println("Error: cannot read package metadata: " + failure.getMessage());
            return;
        }
        if (summary == null && description == null && tags == null) {
            String answerSummary = metadata.getOrDefault("summary", "");
            String answerDescription = metadata.getOrDefault("description", "");
            List<String> answerTags = splitTags(metadata.getOrDefault("tags", ""));
            output.println("Publishing " + staged.coordinate() + "  [" + staged.kind() + ", "
                    + human(staged.bytes()) + "]");
            output.println("  sha256=" + staged.sha256());
            output.println("  summary:    " + (answerSummary.isEmpty() ? "(none)" : answerSummary));
            output.println("  description:" + (answerDescription.isEmpty() ? " (none)"
                    : answerDescription));
            output.println("  tags:       " + (answerTags.isEmpty() ? "(none)"
                    : String.join(", ", answerTags)));
            output.println("Enter to publish as shown, or provide new values"
                    + " (blank keeps current):");
            answerSummary = prompt("summary [" + answerSummary + "]: ", answerSummary);
            answerDescription = prompt("description [" + truncated(answerDescription) + "]: ",
                    answerDescription);
            String tagsAnswer = prompt("tags (comma separated) [" + String.join(", ", answerTags)
                    + "]: ", String.join(", ", answerTags));
            answerTags = splitTags(tagsAnswer);
            summary = answerSummary;
            description = answerDescription;
            tags = answerTags;
        }
        repository.publish(staged, summary, description, tags);
        output.println("Published " + staged.coordinate() + ".");
        output.println("Stored at: " + repository.packageFile(staged.coordinate()));
    }

    private void unpublish(List<String> words) throws IOException, SQLException {
        if (words.size() < 2) {
            output.println("Usage: unpublish <namespace/name/version>");
            return;
        }
        String coordinate = words.get(1);
        if (repository.published().stream().noneMatch(value -> value.coordinate()
                .equals(coordinate))) {
            output.println("Error: not published: " + coordinate);
            return;
        }
        String answer = prompt("Remove " + coordinate
                + " from the catalog? (package file is kept) [y/N]: ", "n");
        if (!answer.equalsIgnoreCase("y") && !answer.equalsIgnoreCase("yes")) {
            output.println("Cancelled.");
            return;
        }
        repository.unpublish(coordinate);
        output.println("Unpublished " + coordinate + ".");
    }

    private void status() {
        List<MarketRepository.PackageRecord> packages = repository.published();
        output.println("Repository: " + options.repository());
        output.println("Catalog:    " + options.catalog());
        output.println("Published:  " + packages.size() + " packages");
        output.println("Service:    http://" + options.bind().getHostAddress() + ":"
                + server.port() + "/market/v1/index.json");
        output.println("Allowed:    " + String.join(", ",
                options.allowedNetworks().stream().map(Object::toString).toList()));
        output.println("Workers:    " + options.workers());
    }

    private String prompt(String message, String fallback) throws IOException {
        output.print(message);
        output.flush();
        String answer = input.readLine();
        if (answer == null) {
            throw new IOException("Input closed while prompting");
        }
        String trimmed = answer.strip();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static Map<String, String> readMetadata(Path database) throws SQLException {
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        String jdbc = "jdbc:sqlite:" + database.toUri() + "?mode=ro&immutable=1";
        try (java.sql.Connection connection =
                     java.sql.DriverManager.getConnection(jdbc);
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            try (java.sql.ResultSet rows = statement.executeQuery(
                    "SELECT metadata_key,metadata_value FROM package_metadata")) {
                while (rows.next()) metadata.put(rows.getString(1), rows.getString(2));
            }
        }
        return metadata;
    }

    private static List<String> splitTags(String value) {
        List<String> tags = new ArrayList<>();
        for (String tag : value.split("[,;]")) {
            String cleaned = tag.strip();
            if (!cleaned.isEmpty()) tags.add(cleaned);
        }
        return tags;
    }

    private static String truncated(String value) {
        return value.length() <= 60 ? value : value.substring(0, 57) + "...";
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
    }

    /** Splits a console line into words, honoring double quotes. */
    static List<String> split(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) {
                    words.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (!current.isEmpty()) words.add(current.toString());
        return words;
    }

    @Override
    public void close() {
        server.close();
    }

    static MarketConsole interactive(ServerOptions options) throws IOException, SQLException {
        MarketRepository repository = new MarketRepository(options.repository(),
                options.catalog());
        MarketHttpServer server = new MarketHttpServer(options, repository);
        server.start();
        return new MarketConsole(options, repository, server,
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out);
    }
}
