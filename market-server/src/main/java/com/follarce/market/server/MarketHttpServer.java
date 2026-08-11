package com.follarce.market.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarketHttpServer implements AutoCloseable {
    private static final Pattern PACKAGE_PATH = Pattern.compile("/market/v1/([0-9a-f]{64})");
    private static final Pattern RANGE = Pattern.compile("bytes=([0-9]{1,20})-([0-9]{0,20})");
    private static final int STREAM_BUFFER_BYTES = 1024 * 1024;
    private static final String PUBLISH_PATH = "/market/v1/publish";

    private final MarketRepository repository;
    private final TokenStore tokens;
    private final List<IpNetwork> allowedNetworks;
    private final Semaphore slots;
    private final HttpServer server;
    private final ExecutorService executor;

    MarketHttpServer(ServerOptions options, MarketRepository repository) throws IOException {
        this.repository = repository;
        this.tokens = new TokenStore(options.tokens());
        this.allowedNetworks = options.allowedNetworks();
        this.slots = new Semaphore(options.workers());
        this.server = HttpServer.create(new InetSocketAddress(options.bind(), options.port()), 32);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        boolean acquired = slots.tryAcquire();
        try {
            secureHeaders(exchange.getResponseHeaders());
            if (!acquired) {
                respondText(exchange, 503, "Market is busy\n");
                return;
            }
            if (!allowed(exchange)) {
                respondText(exchange, 403, "Client network is not allowed\n");
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = exchange.getRequestURI().getRawPath();
            if (method.equals("POST") && path.equals(PUBLISH_PATH)) {
                handlePublish(exchange);
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                respondText(exchange, 405, "Method not allowed\n");
                return;
            }
            if (exchange.getRequestURI().getRawQuery() != null) {
                respondText(exchange, 404, "Unknown market resource\n");
                return;
            }
            if (path.equals("/market/v1/index.json")) {
                try {
                    repository.refresh();
                } catch (IOException | SQLException | IllegalArgumentException invalidCatalog) {
                    respondText(exchange, 503, "Market catalog refresh failed\n");
                    return;
                }
                respondBytes(exchange, 200, "application/json; charset=utf-8",
                        repository.index(), method.equals("HEAD"));
                return;
            }
            Matcher packageRequest = PACKAGE_PATH.matcher(path);
            if (!packageRequest.matches()) {
                respondText(exchange, 404, "Unknown market resource\n");
                return;
            }
            MarketRepository.PublishedPackage value = repository.require(packageRequest.group(1));
            if (value == null) {
                respondText(exchange, 404, "Unknown package ID\n");
                return;
            }
            if (!repository.unchanged(value)) {
                respondText(exchange, 409, "Published package changed after startup\n");
                return;
            }
            sendPackage(exchange, value, method.equals("HEAD"));
        } catch (IllegalArgumentException invalid) {
            respondText(exchange, 400, "Invalid request\n");
        } catch (IOException failure) {
            if (exchange.getResponseCode() < 0) {
                try {
                    respondText(exchange, 500, "Market I/O failure\n");
                } catch (IOException ignored) { }
            }
        } finally {
            if (acquired) slots.release();
            exchange.close();
        }
    }

    /**
     * External publish endpoint: the raw package database is uploaded in the body and
     * authenticated with a Bearer publish token. Metadata (summary/description/tags)
     * may be overridden via query parameters; otherwise the package's own metadata
     * table is used.
     */
    private void handlePublish(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            respondText(exchange, 401, "Missing publish token\n");
            return;
        }
        if (!tokens.isValid(authorization.substring("Bearer ".length()).trim())) {
            respondText(exchange, 403, "Invalid publish token\n");
            return;
        }
        String lengthText = exchange.getRequestHeaders().getFirst("Content-Length");
        long declared;
        try {
            declared = Long.parseLong(lengthText == null ? "" : lengthText);
        } catch (NumberFormatException invalid) {
            respondText(exchange, 411, "Content-Length is required\n");
            return;
        }
        if (declared < 1 || declared > MarketRepository.MAX_PACKAGE_BYTES) {
            respondText(exchange, 413, "Package must be from 1 byte to "
                    + MarketRepository.MAX_PACKAGE_BYTES + " bytes\n");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes((int) declared + 1);
        if (body.length != declared || body.length > MarketRepository.MAX_PACKAGE_BYTES) {
            respondText(exchange, 413, "Package body does not match Content-Length\n");
            return;
        }
        Path temporary = Files.createTempFile("market-publish-", ".db");
        try {
            Files.write(temporary, body);
            MarketRepository.StagedPackage staged = repository.stage(temporary);
            Map<String, String> metadata = MarketRepository.readMetadata(staged.source());
            Map<String, String> query = queryParameters(exchange);
            String summary = query.getOrDefault("summary", metadata.getOrDefault("summary", ""));
            String description = query.getOrDefault("description",
                    metadata.getOrDefault("description", ""));
            String tagsText = query.getOrDefault("tags", metadata.getOrDefault("tags", ""));
            List<String> tags = new ArrayList<>();
            for (String tag : tagsText.split("[,;]")) {
                String cleaned = tag.strip();
                if (!cleaned.isEmpty()) tags.add(cleaned);
            }
            repository.publish(staged, summary, description, tags);
            respondJson(exchange, 201, Map.of(
                    "coordinate", staged.coordinate(),
                    "kind", staged.kind(),
                    "sha256", staged.sha256(),
                    "bytes", Long.toString(staged.bytes()),
                    "storedAt", repository.packageFile(staged.coordinate())));
        } catch (IllegalArgumentException | SQLException invalid) {
            respondText(exchange, 400, "Rejected: " + safeMessage(invalid) + "\n");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, String> queryParameters(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) continue;
            String key = decode(pair.substring(0, separator));
            String value = decode(pair.substring(separator + 1));
            if (!key.isBlank()) result.put(key, value);
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private void respondJson(HttpExchange exchange, int status, Map<String, String> body)
            throws IOException {
        respondBytes(exchange, status, "application/json; charset=utf-8",
                new com.google.gson.Gson().toJson(body).getBytes(StandardCharsets.UTF_8), false);
    }

    private void sendPackage(HttpExchange exchange, MarketRepository.PublishedPackage value,
                             boolean head) throws IOException {
        long size = value.record().bytes();
        long start = 0;
        long end = size - 1;
        boolean partial = false;
        String etag = "\"" + value.record().sha256() + "\"";
        String requestedRange = exchange.getRequestHeaders().getFirst("Range");
        String ifRange = exchange.getRequestHeaders().getFirst("If-Range");
        if (requestedRange != null && (ifRange == null || ifRange.equals(etag))) {
            Matcher match = RANGE.matcher(requestedRange.trim());
            if (!match.matches()) {
                respondText(exchange, 400, "Only one explicit byte range is supported\n");
                return;
            }
            start = parseOffset(match.group(1));
            if (start >= size) {
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Range", "bytes */" + size);
                headers.set("ETag", etag);
                headers.set("Accept-Ranges", "bytes");
                exchange.sendResponseHeaders(416, -1);
                return;
            }
            if (!match.group(2).isEmpty()) end = Math.min(parseOffset(match.group(2)), size - 1);
            if (end < start) {
                respondText(exchange, 400, "Invalid byte range\n");
                return;
            }
            partial = true;
        }
        long length = end - start + 1;
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/vnd.sqlite3");
        headers.set("Accept-Ranges", "bytes");
        headers.set("ETag", etag);
        headers.set("Content-Length", Long.toString(length));
        if (partial) headers.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
        if (head) {
            exchange.sendResponseHeaders(partial ? 206 : 200, -1);
            return;
        }
        exchange.sendResponseHeaders(partial ? 206 : 200, length);
        try (FileChannel source = FileChannel.open(value.path(), StandardOpenOption.READ);
             OutputStream output = exchange.getResponseBody()) {
            source.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(STREAM_BUFFER_BYTES, length));
            long remaining = length;
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int count = source.read(buffer);
                if (count < 0) throw new IOException("Package ended before declared length");
                if (count == 0) continue;
                output.write(buffer.array(), 0, count);
                remaining -= count;
            }
        }
    }

    private boolean allowed(HttpExchange exchange) {
        return allowedNetworks.stream().anyMatch(network ->
                network.contains(exchange.getRemoteAddress().getAddress()));
    }

    private static long parseOffset(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid range offset", invalid);
        }
    }

    private static void secureHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Server", "CilExecMarket/2");
    }

    private static void respondText(HttpExchange exchange, int status, String body)
            throws IOException {
        respondBytes(exchange, status, "text/plain; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8),
                exchange.getRequestMethod().equalsIgnoreCase("HEAD"));
    }

    private static void respondBytes(HttpExchange exchange, int status, String mediaType,
                                     byte[] body, boolean head) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", mediaType);
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
        if (head) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @Override public void close() {
        server.stop(1);
        executor.shutdown();
    }
}
