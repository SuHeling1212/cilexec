package com.follarce.market.server;

import com.google.gson.Gson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketHttpServerTest {
    @TempDir Path temporary;

    @Test
    void servesValidatedIndexHeadAndByteRanges() throws Exception {
        Path repository = temporary.resolve("repository");
        Path packageDirectory = repository.resolve("packages/demo/tool/1.0.0");
        Files.createDirectories(packageDirectory);
        Path database = packageDirectory.resolve("tool.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=2");
            statement.execute("CREATE TABLE package_metadata(metadata_key TEXT PRIMARY KEY,"
                    + "metadata_value TEXT NOT NULL)");
            statement.execute("CREATE TABLE package_dependency(dependency_file_hash TEXT PRIMARY KEY,"
                    + "optional INTEGER NOT NULL)");
            statement.execute("INSERT INTO package_metadata VALUES ('namespace','demo'),"
                    + "('name','tool'),('version','1.0.0'),('package_kind','application')");
        }
        Path catalog = temporary.resolve("catalog.json");
        Files.writeString(catalog, "{\"demo/tool/1.0.0\":{\"summary\":\"tool\"}}");
        ServerOptions options = new ServerOptions(repository, catalog,
                InetAddress.getByName("127.0.0.1"), 0,
                List.of(IpNetwork.parse("127.0.0.0/8")), 4);
        MarketRepository market = new MarketRepository(repository, catalog);
        try (MarketHttpServer server = new MarketHttpServer(options, market)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            URI indexUri = URI.create("http://127.0.0.1:" + server.port()
                    + "/market/v1/index.json");
            HttpResponse<String> index = client.send(HttpRequest.newBuilder(indexUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, index.statusCode());
            assertEquals("nosniff", index.headers().firstValue("X-Content-Type-Options").orElseThrow());
            Object decoded = new Gson().fromJson(index.body(), Object.class);
            String packageId = (String) ((java.util.Map<?, ?>) ((java.util.List<?>)
                    ((java.util.Map<?, ?>) decoded).get("packages")).getFirst()).get("sha256");
            URI packageUri = URI.create("http://127.0.0.1:" + server.port()
                    + "/market/v1/" + packageId);
            HttpResponse<Void> head = client.send(HttpRequest.newBuilder(packageUri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(200, head.statusCode());
            assertTrue(head.headers().firstValueAsLong("Content-Length").orElseThrow() > 10);
            HttpResponse<byte[]> range = client.send(HttpRequest.newBuilder(packageUri)
                    .header("Range", "bytes=0-9").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(206, range.statusCode());
            assertEquals(10, range.body().length);
        }
    }
}
