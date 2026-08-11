package com.follarce.market.server;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MarketServerMain {
    private MarketServerMain() { }

    public static void main(String[] arguments) {
        try {
            if (contains(arguments, "--setup")) {
                MarketSetup.run(arguments);
                return;
            }
            if (arguments.length > 0 && arguments[0].equals("--token")) {
                tokenCommand(arguments);
                return;
            }
            String publishFile = value(arguments, "--publish");
            ServerOptions options = ServerOptions.parse(without(arguments, "--publish"));
            MarketRepository repository = new MarketRepository(options.repository(),
                    options.catalog());
            if (publishFile != null) {
                publishOnce(repository, Path.of(publishFile));
                return;
            }
            if (options.checkOnly()) {
                System.out.println("CilExec market repository is valid");
                return;
            }
            if (options.headless()) {
                runHeadless(options, repository);
                return;
            }
            try (MarketConsole console = MarketConsole.interactive(options)) {
                console.run();
            }
        } catch (ServerOptions.HelpRequested help) {
            System.out.println(ServerOptions.usage());
        } catch (Exception failure) {
            System.err.println("Cannot start CilExec market: " + safe(failure.getMessage()));
            System.exit(1);
        }
    }

    /** One-shot publish: validates, publishes with the package's own metadata, and exits. */
    private static void publishOnce(MarketRepository repository, Path source)
            throws IOException, SQLException {
        MarketRepository.StagedPackage staged = repository.stage(source);
        Map<String, String> metadata = MarketRepository.readMetadata(staged.source());
        String summary = metadata.getOrDefault("summary", "");
        String description = metadata.getOrDefault("description", "");
        List<String> tags = new ArrayList<>();
        for (String tag : metadata.getOrDefault("tags", "").split("[,;]")) {
            String cleaned = tag.strip();
            if (!cleaned.isEmpty()) tags.add(cleaned);
        }
        repository.publish(staged, summary, description, tags);
        System.out.println("Published " + staged.coordinate());
        System.out.println("Stored at: " + repository.packageFile(staged.coordinate()));
    }

    /** Manages the publish tokens external developers use for HTTP uploads. */
    private static void tokenCommand(String[] arguments) throws Exception {
        String subcommand = arguments.length > 1 ? arguments[1] : "";
        String name = null;
        List<String> options = new ArrayList<>();
        for (int index = 2; index < arguments.length; index++) {
            if (subcommand.equals("add") || subcommand.equals("remove")) {
                if (name == null && !arguments[index].startsWith("-")) {
                    name = arguments[index];
                } else {
                    options.add(arguments[index]);
                }
            } else {
                options.add(arguments[index]);
            }
        }
        if (name == null && (subcommand.equals("add") || subcommand.equals("remove"))) {
            throw new IllegalArgumentException("--token " + subcommand + " requires a name");
        }
        ServerOptions server = ServerOptions.parse(options.toArray(String[]::new));
        TokenStore store = new TokenStore(server.tokens());
        switch (subcommand) {
            case "add" -> {
                String plaintext = store.add(name);
                System.out.println("Created token '" + name + "' — shown once, copy it now:");
                System.out.println(plaintext);
            }
            case "list" -> {
                if (store.names().isEmpty()) {
                    System.out.println("No publish tokens");
                } else {
                    System.out.println("Publish tokens:");
                    store.names().forEach(entry -> System.out.println("  " + entry));
                }
            }
            case "remove" -> System.out.println(store.remove(name)
                    ? "Removed token '" + name + "'" : "No such token: " + name);
            default -> throw new IllegalArgumentException(
                    "Usage: --token add|list|remove [name] [--tokens PATH]");
        }
    }

    private static void runHeadless(ServerOptions options, MarketRepository repository)
            throws IOException {
        MarketHttpServer server = new MarketHttpServer(options, repository);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close,
                "cilexec-market-shutdown"));
        server.start();
        System.out.println("CilExec market: http://" + options.bind().getHostAddress() + ":"
                + server.port() + "/market/v1/index.json");
        System.out.println("Allowed clients: " + String.join(", ",
                options.allowedNetworks().stream().map(Object::toString).toList()));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean contains(String[] arguments, String option) {
        for (String argument : arguments) {
            if (argument.equals(option)) return true;
        }
        return false;
    }

    /** Value of a --option (null when absent); the option must carry a value. */
    private static String value(String[] arguments, String option) {
        for (int index = 0; index < arguments.length; index++) {
            if (arguments[index].equals(option)) {
                if (index + 1 >= arguments.length) {
                    throw new IllegalArgumentException("Missing value for " + option);
                }
                return arguments[index + 1];
            }
        }
        return null;
    }

    /** Arguments without the option and its value. */
    private static String[] without(String[] arguments, String option) {
        List<String> rest = new ArrayList<>();
        for (int index = 0; index < arguments.length; index++) {
            if (arguments[index].equals(option)) {
                index++;
            } else {
                rest.add(arguments[index]);
            }
        }
        return rest.toArray(String[]::new);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown failure";
        return value.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "?")
                .replace('\n', ' ').replace('\r', ' ');
    }
}
