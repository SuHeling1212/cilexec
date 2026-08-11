package com.follarce.market.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketConsoleTest {
    @TempDir Path temporary;

    private static MarketConsole console(Path repository, Path catalog, String input)
            throws Exception {
        Files.createDirectories(repository);
        ServerOptions options = new ServerOptions(repository, catalog,
                catalog.resolveSibling("tokens.json"), InetAddress.getByName("127.0.0.1"), 0,
                List.of(IpNetwork.parse("127.0.0.0/8")), 4, false, true);
        MarketRepository market = new MarketRepository(repository, catalog);
        MarketHttpServer server = new MarketHttpServer(options, market);
        return new MarketConsole(options, market, server,
                new BufferedReader(new StringReader(input)),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    private static Path createPackageDatabase(Path directory, String name, String version,
                                              String summary) throws Exception {
        Files.createDirectories(directory);
        Path database = directory.resolve(name + ".db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=2");
            statement.execute("CREATE TABLE package_metadata(metadata_key TEXT PRIMARY KEY,"
                    + "metadata_value TEXT NOT NULL)");
            statement.execute("CREATE TABLE package_dependency(dependency_file_hash TEXT PRIMARY KEY,"
                    + "optional INTEGER NOT NULL)");
            statement.execute("INSERT INTO package_metadata VALUES ('namespace','demo'),"
                    + "('name','" + name + "'),('version','" + version
                    + "'),('package_kind','application')"
                    + (summary.isEmpty() ? "" : ",('summary','" + summary + "')"));
        }
        return database;
    }

    @Test
    void publishesValidatesAndUnpublishes() throws Exception {
        Path repository = temporary.resolve("repository");
        Path catalog = temporary.resolve("catalog.json");
        Files.writeString(catalog, "{}");
        Path database = createPackageDatabase(temporary.resolve("sources"), "tool", "1.0.0",
                "a tool");

        String input = "publish " + database + "\n\n\n\nunpublish demo/tool/1.0.0\nn\nexit\n";
        MarketConsole console = console(repository, catalog, input);
        console.run();
        console.close();

        String published = Files.readString(catalog, StandardCharsets.UTF_8);
        assertTrue(published.contains("demo/tool/1.0.0"),
                "catalog must contain the published coordinate: " + published);
        assertTrue(published.contains("a tool"), "catalog must keep the package summary");
        assertTrue(Files.isRegularFile(repository.resolve(
                "packages/demo/tool/1.0.0/tool.db")), "package must be copied into the repository");
    }

    @Test
    void publishFlagsSkipConfirmationAndPublishDirectly() throws Exception {
        Path repository = temporary.resolve("repository");
        Path catalog = temporary.resolve("catalog.json");
        Files.writeString(catalog, "{}");
        Path database = createPackageDatabase(temporary.resolve("sources"), "tool", "1.0.0",
                "a tool");

        MarketConsole console = console(repository, catalog, "publish " + database
                + " --summary \"one-shot\" --description \"no questions\" --tags alpha,beta\n"
                + "exit\n");
        console.run();
        console.close();

        String published = Files.readString(catalog, StandardCharsets.UTF_8);
        assertTrue(published.contains("one-shot"), published);
        assertTrue(published.contains("no questions"), published);
        assertTrue(published.contains("alpha"), published);
        assertTrue(published.contains("beta"), published);
    }

    @Test
    void refusesToPublishReplacementContentForAnExistingCoordinate() throws Exception {
        Path repository = temporary.resolve("repository");
        Path catalog = temporary.resolve("catalog.json");
        Files.writeString(catalog, "{}");
        Path first = createPackageDatabase(temporary.resolve("sources"), "tool", "1.0.0", "v1");

        MarketConsole console = console(repository, catalog,
                "publish " + first + "\n\n\n\n");
        console.run();
        console.close();

        Path second = createPackageDatabase(temporary.resolve("sources2"), "tool", "1.0.0", "v2");
        MarketConsole again = console(repository, catalog,
                "publish " + second + "\n\n\n\n");
        again.run();
        again.close();

        String published = Files.readString(catalog, StandardCharsets.UTF_8);
        assertTrue(published.contains("v1"), "first publication must remain");
        assertFalse(published.contains("v2"), "conflicting publication must be rejected");
    }

    @Test
    void unpublishRemovesOnlyTheCatalogEntry() throws Exception {
        Path repository = temporary.resolve("repository");
        Path catalog = temporary.resolve("catalog.json");
        Files.writeString(catalog, "{\"demo/tool/1.0.0\":{\"summary\":\"tool\"}}");
        createPackageDatabase(repository.resolve("packages/demo/tool/1.0.0"), "tool", "1.0.0",
                "tool");

        MarketConsole console = console(repository, catalog, "unpublish demo/tool/1.0.0\ny\nexit\n");
        console.run();
        console.close();

        String published = Files.readString(catalog, StandardCharsets.UTF_8);
        assertFalse(published.contains("demo/tool/1.0.0"),
                "coordinate must leave the catalog: " + published);
        assertTrue(Files.isRegularFile(repository.resolve(
                "packages/demo/tool/1.0.0/tool.db")), "package file must be kept");
    }

    @Test
    void publishesReplacementVersionsAndKeepsOlderOnes() throws Exception {
        Path repository = temporary.resolve("repository");
        Path catalog = temporary.resolve("catalog.json");
        Files.writeString(catalog, "{}");
        Path v1 = createPackageDatabase(temporary.resolve("sources"), "tool", "1.0.0", "v1");
        Path v2 = createPackageDatabase(temporary.resolve("sources2"), "tool", "1.1.0", "v2");

        MarketConsole console = console(repository, catalog,
                "publish " + v1 + "\n\n\n\npublish " + v2 + "\n\n\n\nexit\n");
        console.run();
        console.close();

        String published = Files.readString(catalog, StandardCharsets.UTF_8);
        assertTrue(published.contains("demo/tool/1.0.0"));
        assertTrue(published.contains("demo/tool/1.1.0"));
        assertEquals(2, Files.list(repository.resolve("packages/demo/tool")).count());
    }

    @Test
    void splitsQuotedArguments() {
        assertEquals(List.of("publish", "my package.db", "1.0.0"),
                MarketConsole.split("publish \"my package.db\" 1.0.0"));
        assertEquals(List.of("publish", "a", "b"),
                MarketConsole.split("publish  a   b "));
        assertEquals(List.of("unpublish", "demo/tool/1.0.0"),
                MarketConsole.split("unpublish demo/tool/1.0.0"));
    }
}
