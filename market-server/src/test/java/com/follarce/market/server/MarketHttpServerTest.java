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
    void refreshesNewPublicationsWithoutDroppingOlderVersions() throws Exception {
        Path repository = temporary.resolve("refresh-repository");
        createPackage(repository, "tool", "1.0.0");
        Path catalog = temporary.resolve("refresh-catalog.json");
        Files.writeString(catalog, "{\"demo/tool/1.0.0\":{\"summary\":\"tool\"}}");
        MarketRepository market = new MarketRepository(repository, catalog);

        java.util.Map<?, ?> first = (java.util.Map<?, ?>) new Gson().fromJson(
                new String(market.index(), java.nio.charset.StandardCharsets.UTF_8),
                Object.class);
        assertEquals(1, ((java.util.List<?>) first.get("packages")).size());

        createPackage(repository, "tool", "1.1.0");
        Files.writeString(catalog, "{\"demo/tool/1.0.0\":{\"summary\":\"tool\"},"
                + "\"demo/tool/1.1.0\":{\"summary\":\"new tool\"}}");
        market.refresh();

        java.util.List<?> packages = (java.util.List<?>) ((java.util.Map<?, ?>)
                new Gson().fromJson(new String(market.index(),
                        java.nio.charset.StandardCharsets.UTF_8), Object.class)).get("packages");
        assertEquals(2, packages.size());
        assertTrue(packages.stream().map(java.util.Map.class::cast)
                .anyMatch(item -> "1.0.0".equals(item.get("version"))));
        assertTrue(packages.stream().map(java.util.Map.class::cast)
                .anyMatch(item -> "1.1.0".equals(item.get("version"))));
    }

    @Test
    void servesValidatedIndexHeadAndByteRanges() throws Exception {
        Path repository = temporary.resolve("repository");
        createPackage(repository, "tool", "1.0.0");
        Path catalog = temporary.resolve("catalog.json");
        Files.writeString(catalog, "{\"demo/tool/1.0.0\":{\"summary\":\"tool\"}}");
        ServerOptions options = new ServerOptions(repository, catalog,
                temporary.resolve("tokens.json"), InetAddress.getByName("127.0.0.1"), 0,
                List.of(IpNetwork.parse("127.0.0.0/8")), 4, false, false);
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

            createPackage(repository, "tool", "1.1.0");
            Files.writeString(catalog, "{\"demo/tool/1.0.0\":{\"summary\":\"tool\"},"
                    + "\"demo/tool/1.1.0\":{\"summary\":\"new tool\"}}");
            HttpResponse<String> refreshed = client.send(HttpRequest.newBuilder(indexUri).GET()
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, refreshed.statusCode());
            java.util.List<?> packages = (java.util.List<?>) ((java.util.Map<?, ?>)
                    new Gson().fromJson(refreshed.body(), Object.class)).get("packages");
            assertEquals(2, packages.size());
            java.util.Map<?, ?> latest = packages.stream().map(java.util.Map.class::cast)
                    .filter(item -> "1.1.0".equals(item.get("version")))
                    .findFirst().orElseThrow();
            URI newPackageUri = URI.create("http://127.0.0.1:" + server.port()
                    + latest.get("download"));
            HttpResponse<byte[]> newPackage = client.send(HttpRequest.newBuilder(newPackageUri)
                            .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, newPackage.statusCode());
            assertEquals(((Number) latest.get("bytes")).longValue(), newPackage.body().length);
        }
    }

    @Test
    void publishesThroughTheHttpEndpointWithABearerToken() throws Exception {
        Path repository = temporary.resolve("publish-repository");
        Path catalog = temporary.resolve("publish-catalog.json");
        Files.writeString(catalog, "{}");
        Path tokens = temporary.resolve("tokens.json");
        TokenStore tokenStore = new TokenStore(tokens);
        String token = tokenStore.add("developer");
        ServerOptions options = new ServerOptions(repository, catalog, tokens,
                InetAddress.getByName("127.0.0.1"), 0,
                List.of(IpNetwork.parse("127.0.0.0/8")), 4, false, false);
        MarketRepository market = new MarketRepository(repository, catalog);
        Path database = temporary.resolve("remote-tool.db");
        createPackageAt(database, "remote", "2.0.0", "remote summary");

        try (MarketHttpServer server = new MarketHttpServer(options, market)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            URI publishUri = URI.create("http://127.0.0.1:" + server.port()
                    + "/market/v1/publish?summary=uploaded");
            HttpRequest request = HttpRequest.newBuilder(publishUri)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/vnd.sqlite3")
                    .POST(HttpRequest.BodyPublishers.ofFile(database)).build();
            HttpResponse<String> published = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, published.statusCode(), published.body());
            java.util.Map<?, ?> body = (java.util.Map<?, ?>)
                    new Gson().fromJson(published.body(), Object.class);
            assertEquals("demo/remote/2.0.0", body.get("coordinate"));

            URI indexUri = URI.create("http://127.0.0.1:" + server.port()
                    + "/market/v1/index.json");
            HttpResponse<String> index = client.send(HttpRequest.newBuilder(indexUri).GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(index.body().contains("demo/remote/2.0.0"), index.body());
            assertTrue(index.body().contains("uploaded"),
                    "query summary must override the package metadata: " + index.body());
            assertTrue(Files.isRegularFile(repository.resolve(
                    "packages/demo/remote/2.0.0/remote.db")));
        }
    }

    @Test
    void httpPublishRejectsMissingWrongAndInvalidTokensAndBodies() throws Exception {
        Path repository = temporary.resolve("auth-repository");
        Path catalog = temporary.resolve("auth-catalog.json");
        Files.writeString(catalog, "{}");
        Path tokens = temporary.resolve("tokens.json");
        TokenStore tokenStore = new TokenStore(tokens);
        String token = tokenStore.add("developer");
        ServerOptions options = new ServerOptions(repository, catalog, tokens,
                InetAddress.getByName("127.0.0.1"), 0,
                List.of(IpNetwork.parse("127.0.0.0/8")), 4, false, false);
        MarketRepository market = new MarketRepository(repository, catalog);
        Path database = temporary.resolve("auth-tool.db");
        createPackageAt(database, "auth", "1.0.0", "auth");

        try (MarketHttpServer server = new MarketHttpServer(options, market)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            URI publishUri = URI.create("http://127.0.0.1:" + server.port()
                    + "/market/v1/publish");

            HttpResponse<String> missing = client.send(HttpRequest.newBuilder(publishUri)
                    .POST(HttpRequest.BodyPublishers.ofFile(database)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, missing.statusCode());

            HttpResponse<String> wrong = client.send(HttpRequest.newBuilder(publishUri)
                    .header("Authorization", "Bearer " + "f".repeat(64))
                    .POST(HttpRequest.BodyPublishers.ofFile(database)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, wrong.statusCode());

            HttpResponse<String> notPackage = client.send(HttpRequest.newBuilder(publishUri)
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("not a database")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, notPackage.statusCode());

            // A raw socket is used because HttpClient forbids explicit Content-Length.
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", server.port())) {
                socket.getOutputStream().write(("POST /market/v1/publish HTTP/1.1\r\n"
                        + "Host: market\r\nAuthorization: Bearer " + token
                        + "\r\nContent-Length: 99999999999\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.io.BufferedReader response = new java.io.BufferedReader(
                        new java.io.InputStreamReader(socket.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8));
                assertTrue(response.readLine().startsWith("HTTP/1.1 413"),
                        "oversized uploads must be rejected");
            }

            assertEquals(0, market.published().size(),
                    "no rejected upload may publish anything");
        }
    }

    private static void createPackage(Path repository, String name, String version)
            throws Exception {
        Path packageDirectory = repository.resolve("packages/demo/" + name + "/" + version);
        Files.createDirectories(packageDirectory);
        createPackageAt(packageDirectory.resolve(name + ".db"), name, version, "tool");
    }

    private static void createPackageAt(Path database, String name, String version,
                                        String summary) throws Exception {
        Files.createDirectories(database.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=2");
            statement.execute("CREATE TABLE package_metadata(metadata_key TEXT PRIMARY KEY,"
                    + "metadata_value TEXT NOT NULL)");
            statement.execute("CREATE TABLE package_dependency(dependency_file_hash TEXT PRIMARY KEY,"
                    + "optional INTEGER NOT NULL)");
            statement.execute("INSERT INTO package_metadata VALUES ('namespace','demo'),"
                    + "('name','" + name + "'),('version','" + version
                    + "'),('package_kind','application'),('summary','" + summary + "')");
        }
    }
}
