package com.follarce.effect;

import com.follarce.domain.process.Continuation;
import com.follarce.fcl.FclContinuationCodec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinEffectHandlersTest {
    private final FclContinuationCodec codec = new FclContinuationCodec();

    @BeforeAll
    static void allowOnlyTheLoopbackTestServer() {
        System.setProperty("cilexec.networkAllowPrivateHosts", "127.0.0.1,localhost");
    }

    @Test
    void pinnedHttpKeepsTheOriginalVirtualHost() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> host =
                new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/host", exchange -> {
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            EffectHandler handler = new EffectHandlerRegistry(BuiltinEffectHandlers.defaults())
                    .require("network.http-get");
            handler.execute(typed(Map.of("url", "http://localhost:" + port + "/host")),
                    Optional.empty());
            assertEquals("localhost:" + port, host.get());
            NetworkTargetPolicy.ResolvedHttpTarget target =
                    NetworkTargetPolicy.resolveHttpTarget(java.net.URI.create(
                            "http://localhost:" + port + "/host"));
            assertEquals(target.address().getHostAddress(), target.pinnedUri().getHost());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void assemblesEveryExternalNamespaceAndKeepsHostExecDenyByDefault() throws Exception {
        EffectHandlerRegistry handlers = new EffectHandlerRegistry(
                BuiltinEffectHandlers.defaults(Set.of()));
        assertEquals("io.output", handlers.require("io.output").effectType());
        assertEquals("network.http-get", handlers.require("network.http-get").effectType());
        assertEquals("network.http-post", handlers.require("network.http-post").effectType());
        assertEquals("network.download", handlers.require("network.download").effectType());
        assertEquals("socket.connect", handlers.require("socket.connect").effectType());
        assertEquals("socket.accept", handlers.require("socket.accept").effectType());

        Continuation.PersistedValue command = typed(Map.of("command", List.of("true")));
        assertThrows(SecurityException.class,
                () -> handlers.require("system.exec").execute(command, Optional.empty()));

        Continuation.PersistedValue bound = handlers.require("socket.bind").execute(
                typed(Map.of("arguments", List.of())), Optional.empty());
        Object decoded = codec.valueFromJson(bound.canonicalPayload());
        assertTrue(decoded instanceof Map<?, ?> envelope
                && Boolean.TRUE.equals(envelope.get("ok"))
                && envelope.get("value") instanceof Map<?, ?> endpoint
                && Boolean.TRUE.equals(endpoint.get("oneShot")));
    }

    @Test
    void downloadsBinaryResponsesWithoutUtf8Corruption() throws Exception {
        byte[] packageBytes = new byte[]{0x53, 0x51, 0x4c, (byte) 0xff, 0x00, (byte) 0x80};
        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/editor.db", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.sqlite3");
            exchange.sendResponseHeaders(200, packageBytes.length);
            exchange.getResponseBody().write(packageBytes);
            exchange.close();
        });
        server.start();
        try {
            EffectHandler handler = new EffectHandlerRegistry(BuiltinEffectHandlers.defaults())
                    .require("network.download");
            Continuation.PersistedValue result = handler.execute(typed(Map.of("url",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/editor.db")),
                    Optional.empty());
            Object decoded = codec.valueFromJson(result.canonicalPayload());
            assertTrue(decoded instanceof Map<?, ?> envelope);
            Object value = ((Map<?, ?>) decoded).get("value");
            assertTrue(value instanceof Map<?, ?>);
            Map<?, ?> response = (Map<?, ?>) value;
            assertEquals(200L, response.get("status"));
            assertEquals("application/vnd.sqlite3", response.get("mediaType"));
            assertEquals(Base64.getEncoder().encodeToString(packageBytes),
                    response.get("bodyBase64"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadsBoundedRangesForLargeFileAssembly() throws Exception {
        byte[] file = "abcdefgh".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/large.bin", exchange -> {
            assertEquals("bytes=2-4", exchange.getRequestHeaders().getFirst("Range"));
            byte[] part = java.util.Arrays.copyOfRange(file, 2, 5);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("Content-Range", "bytes 2-4/8");
            exchange.getResponseHeaders().set("ETag", "\"stable\"");
            exchange.sendResponseHeaders(206, part.length);
            exchange.getResponseBody().write(part);
            exchange.close();
        });
        server.start();
        try {
            EffectHandler handler = new EffectHandlerRegistry(BuiltinEffectHandlers.defaults())
                    .require("network.download");
            Continuation.PersistedValue result = handler.execute(typed(Map.of(
                    "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/large.bin",
                    "offset", 2L, "maximumBytes", 3L)), Optional.empty());
            Map<?, ?> envelope = (Map<?, ?>) codec.valueFromJson(result.canonicalPayload());
            Map<?, ?> response = (Map<?, ?>) envelope.get("value");
            assertEquals(206L, response.get("status"));
            assertEquals(2L, response.get("offset"));
            assertEquals(8L, response.get("totalBytes"));
            assertEquals(false, response.get("complete"));
            assertEquals("\"stable\"", response.get("validator"));
            assertEquals(Base64.getEncoder().encodeToString(
                    "cde".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                    response.get("bodyBase64"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forwardsTheIdempotencyKeyHeaderWhenProvided() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> idempotency =
                new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/idempotent", exchange -> {
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            EffectHandler handler = new EffectHandlerRegistry(BuiltinEffectHandlers.defaults())
                    .require("network.http-get");
            handler.execute(typed(Map.of("url", "http://localhost:" + port + "/idempotent")),
                    Optional.of("retry-42"));
            assertEquals("retry-42", idempotency.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsOutputWhenTheRouteHasNoAttachedTerminal() {
        EffectHandlerRegistry handlers = new EffectHandlerRegistry(
                BuiltinEffectHandlers.defaults());
        Continuation.PersistedValue output = typed(Map.of("text", "hello",
                "routeId", java.util.UUID.randomUUID().toString()));
        assertThrows(IllegalStateException.class,
                () -> handlers.require("io.output").execute(output, Optional.empty()));
    }

    @Test
    void rejectsNulInStringFormCommands() {
        EffectHandlerRegistry handlers = new EffectHandlerRegistry(
                BuiltinEffectHandlers.defaults());
        assertThrows(IllegalArgumentException.class,
                () -> handlers.require("system.exec").execute(
                        typed(Map.of("command", "/bin/true\u0000echo hacked")),
                        Optional.empty()));
    }

    @Test
    void validatesNumbersExactlyBeyondDoublePrecision() {
        assertEquals(9007199254740993L, BuiltinEffectHandlers.exactLong(
                new java.math.BigDecimal("9007199254740993"), "offset"));
        assertEquals(9007199254740993L, BuiltinEffectHandlers.exactLong(
                new java.math.BigInteger("9007199254740993"), "offset"));
        assertThrows(IllegalArgumentException.class,
                () -> BuiltinEffectHandlers.exactLong(1.5, "offset"));
        assertThrows(IllegalArgumentException.class,
                () -> BuiltinEffectHandlers.exactLong(Double.POSITIVE_INFINITY, "offset"));
        assertThrows(IllegalArgumentException.class,
                () -> BuiltinEffectHandlers.exactLong(new java.math.BigDecimal("2.5"), "offset"));
    }

    private Continuation.PersistedValue typed(Object value) {
        return new Continuation.PersistedValue(codec.valueType(value), codec.valueToJson(value));
    }
}
