package com.follarce.health;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Minimal management endpoint with independent liveness and readiness semantics. */
public final class HealthServer implements AutoCloseable {
    private static final int MAX_CONCURRENT = 64;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpServer server;
    private final ExecutorService executor;
    private final ScheduledExecutorService watchdog;
    private final Semaphore capacity = new Semaphore(MAX_CONCURRENT, true);
    private final HealthState state;
    private final Gson gson = new Gson();

    public HealthServer(int port, HealthState state) {
        this.state = state;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot bind health endpoint", exception);
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        watchdog = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon().name("cilexec-health-watchdog")
                        .unstarted(runnable));
        server.setExecutor(executor);
        server.createContext("/health/live", exchange -> respond(exchange, true));
        server.createContext("/health/ready", exchange -> respond(exchange, false));
    }

    public void start() {
        server.start();
    }

    private void respond(HttpExchange exchange, boolean liveEndpoint) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        if (!capacity.tryAcquire()) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        ScheduledFuture<?> deadline = watchdog.schedule(exchange::close,
                REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        try {
            // Gson cannot reflectively serialize java.time.Instant on strongly encapsulated JDKs.
            // Keep the management response to JSON primitives so a health-check failure can never
            // turn into a dropped HTTP connection. One snapshot drives both status and body.
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
            exchange.sendResponseHeaders(
                    (liveEndpoint ? snapshot.live() : snapshot.ready()) ? 200 : 503,
                    body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        } finally {
            deadline.cancel(false);
            capacity.release();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
        watchdog.shutdownNow();
    }

    int port() {
        return server.getAddress().getPort();
    }
}
