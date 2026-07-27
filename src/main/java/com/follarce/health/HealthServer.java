package com.follarce.health;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal management endpoint with independent liveness and readiness semantics. */
public final class HealthServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final HealthState state;
    private final Gson gson = new Gson();

    public HealthServer(int port, HealthState state) {
        this.state = state;
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot bind health endpoint", exception);
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/health/live", exchange -> respond(exchange, state.snapshot().live()));
        server.createContext("/health/ready", exchange -> respond(exchange, state.snapshot().ready()));
    }

    public void start() {
        server.start();
    }

    private void respond(HttpExchange exchange, boolean healthy) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        // Gson cannot reflectively serialize java.time.Instant on strongly encapsulated JDKs.
        // Keep the management response to JSON primitives so a health-check failure can never
        // turn into a dropped HTTP connection.
        HealthState.Snapshot snapshot = state.snapshot();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("live", snapshot.live());
        response.put("ready", snapshot.ready());
        response.put("phase", snapshot.phase().name());
        response.put("database", snapshot.database());
        response.put("schema", snapshot.schema());
        response.put("controlLock", snapshot.controlLock());
        response.put("recoveryComplete", snapshot.recoveryComplete());
        response.put("schedulerLoop", snapshot.schedulerLoop());
        response.put("startedAt", snapshot.startedAt().toString());
        byte[] body = gson.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(healthy ? 200 : 503, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    int port() {
        return server.getAddress().getPort();
    }
}
