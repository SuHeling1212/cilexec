package com.follarce.effect;

import com.follarce.domain.process.Continuation;
import com.follarce.fcl.FclContinuationCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Production handlers for the external effects exposed by the built-in FCL namespaces. */
public final class BuiltinEffectHandlers {
    private static final int MAX_HTTP_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 1024 * 1024;

    private BuiltinEffectHandlers() {}

    public static List<EffectHandler> defaults() {
        return defaults(commandAllowlist());
    }

    public static List<EffectHandler> defaults(Set<String> allowedExecutables) {
        List<EffectHandler> handlers = new ArrayList<>();
        handlers.add(new OutputHandler());
        handlers.add(new HttpHandler("network.http-get", "GET"));
        handlers.add(new HttpHandler("network.http-post", "POST"));
        handlers.add(new CommandHandler(allowedExecutables));
        for (String operation : List.of("connect", "send", "receive", "close", "bind",
                "accept")) {
            handlers.add(new SocketHandler(operation));
        }
        return List.copyOf(handlers);
    }

    private abstract static class TypedHandler implements EffectHandler {
        private final FclContinuationCodec codec = new FclContinuationCodec();

        @Override
        public final Continuation.PersistedValue execute(Continuation.PersistedValue request,
                                                         Optional<String> idempotencyKey)
                throws Exception {
            Object value = codec.valueFromJson(request.canonicalPayload());
            Object result = executeValue(value, idempotencyKey);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("ok", true);
            envelope.put("value", result);
            return new Continuation.PersistedValue(codec.valueType(envelope),
                    codec.valueToJson(envelope));
        }

        protected abstract Object executeValue(Object request, Optional<String> idempotencyKey)
                throws Exception;

        protected static Map<?, ?> requestMap(Object request) {
            if (!(request instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Effect request must be a map");
            }
            return map;
        }

        protected static String text(Map<?, ?> map, String name) {
            Object value = map.get(name);
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("Effect field must be a string: " + name);
            }
            return text;
        }
    }

    private static final class OutputHandler extends TypedHandler {
        @Override
        public String effectType() {
            return "io.output";
        }

        @Override
        protected Object executeValue(Object request, Optional<String> idempotencyKey) {
            Map<?, ?> map = requestMap(request);
            String text = text(map, "text");
            boolean newline = Boolean.TRUE.equals(map.get("newline"));
            synchronized (System.out) {
                if (newline) System.out.println(text);
                else System.out.print(text);
                System.out.flush();
            }
            return null;
        }
    }

    private static final class HttpHandler extends TypedHandler {
        private final String type;
        private final String method;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        private HttpHandler(String type, String method) {
            this.type = type;
            this.method = method;
        }

        @Override
        public String effectType() {
            return type;
        }

        @Override
        protected Object executeValue(Object request, Optional<String> idempotencyKey)
                throws Exception {
            Map<?, ?> map = requestMap(request);
            URI uri = URI.create(text(map, "url"));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("Only HTTP and HTTPS URLs are supported");
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "CilExec-FCL/1");
            if (method.equals("GET")) builder.GET();
            else builder.POST(HttpRequest.BodyPublishers.ofString(text(map, "body"),
                    StandardCharsets.UTF_8));
            HttpResponse<byte[]> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.body().length > MAX_HTTP_BODY_BYTES) {
                throw new IOException("HTTP response exceeds 4 MiB");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", (long) response.statusCode());
            result.put("body", new String(response.body(), StandardCharsets.UTF_8));
            result.put("headers", response.headers().map());
            return Map.copyOf(result);
        }
    }

    private static final class CommandHandler extends TypedHandler {
        private final Set<String> allowedExecutables;

        private CommandHandler(Set<String> allowedExecutables) {
            this.allowedExecutables = Set.copyOf(allowedExecutables);
        }

        @Override
        public String effectType() {
            return "system.exec";
        }

        @Override
        protected Object executeValue(Object request, Optional<String> idempotencyKey)
                throws Exception {
            Map<?, ?> map = requestMap(request);
            List<String> command = command(map.get("command"));
            String executable = command.getFirst();
            String fileName = java.nio.file.Path.of(executable).getFileName().toString();
            if (!allowedExecutables.contains(executable)
                    && !allowedExecutables.contains(fileName)) {
                throw new SecurityException("Executable is not in CILEXEC_FCL_EXEC_ALLOWLIST: "
                        + fileName);
            }
            Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (var input = child.getInputStream()) {
                byte[] buffer = new byte[8192];
                while (output.size() <= MAX_COMMAND_OUTPUT_BYTES) {
                    int count = input.read(buffer, 0,
                            Math.min(buffer.length, MAX_COMMAND_OUTPUT_BYTES + 1 - output.size()));
                    if (count < 0) break;
                    output.write(buffer, 0, count);
                }
            }
            if (!child.waitFor(30, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new IOException("Command timed out after 30 seconds");
            }
            if (output.size() > MAX_COMMAND_OUTPUT_BYTES) {
                throw new IOException("Command output exceeds 1 MiB");
            }
            return Map.of("exitCode", (long) child.exitValue(), "output",
                    output.toString(StandardCharsets.UTF_8));
        }

        private static List<String> command(Object value) {
            if (value instanceof String text) {
                String trimmed = text.trim();
                if (trimmed.isEmpty()) throw new IllegalArgumentException("Command is empty");
                // No shell is involved: string form deliberately supports only whitespace tokens.
                return List.of(trimmed.split("\\s+"));
            }
            if (value instanceof List<?> source && !source.isEmpty()) {
                List<String> arguments = new ArrayList<>();
                for (Object item : source) {
                    if (!(item instanceof String argument) || argument.indexOf('\0') >= 0) {
                        throw new IllegalArgumentException(
                                "Command argument array must contain safe strings");
                    }
                    arguments.add(argument);
                }
                return List.copyOf(arguments);
            }
            throw new IllegalArgumentException("Command must be a string or non-empty array");
        }
    }

    /** Crash-safe socket operations are deliberately one-shot; no live host handle is persisted. */
    private static final class SocketHandler extends TypedHandler {
        private final String operation;

        private SocketHandler(String operation) {
            this.operation = operation;
        }

        @Override
        public String effectType() {
            return "socket." + operation;
        }

        @Override
        protected Object executeValue(Object request, Optional<String> idempotencyKey)
                throws Exception {
            Object raw = requestMap(request).get("arguments");
            if (!(raw instanceof List<?> arguments)) {
                throw new IllegalArgumentException("Socket arguments must be an array");
            }
            return switch (operation) {
                case "connect" -> connect(arguments);
                case "send" -> send(arguments);
                case "receive" -> receive(arguments);
                case "close" -> true; // One-shot sockets are already closed after every effect.
                case "bind" -> bind(arguments);
                case "accept" -> accept(arguments);
                default -> throw new IllegalStateException("Unknown socket operation");
            };
        }

        private static Object connect(List<?> arguments) throws IOException {
            Endpoint endpoint = endpoint(arguments, 0);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 10_000);
            }
            return endpointMap(endpoint);
        }

        private static Object send(List<?> arguments) throws IOException {
            Endpoint endpoint;
            int dataIndex;
            if (!arguments.isEmpty() && arguments.getFirst() instanceof Map<?, ?> map) {
                endpoint = endpoint(map);
                dataIndex = 1;
            } else {
                endpoint = endpoint(arguments, 0);
                dataIndex = 2;
            }
            if (arguments.size() <= dataIndex) throw new IllegalArgumentException(
                    "socket.send requires endpoint and data");
            byte[] data = String.valueOf(arguments.get(dataIndex)).getBytes(StandardCharsets.UTF_8);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 10_000);
                socket.setSoTimeout(30_000);
                socket.getOutputStream().write(data);
                socket.getOutputStream().flush();
            }
            return (long) data.length;
        }

        private static Object receive(List<?> arguments) throws IOException {
            Endpoint endpoint = endpoint(arguments, 0);
            int maximum = arguments.size() > 2 ? positiveInt(arguments.get(2), "maximum bytes")
                    : MAX_COMMAND_OUTPUT_BYTES;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 10_000);
                socket.setSoTimeout(30_000);
                return read(socket.getInputStream(), maximum);
            }
        }

        private static Object bind(List<?> arguments) throws IOException {
            int port = arguments.isEmpty() ? 0 : port(arguments.getFirst());
            try (ServerSocket server = new ServerSocket()) {
                server.bind(new InetSocketAddress(port));
                return Map.of("host", "0.0.0.0", "port", (long) server.getLocalPort(),
                        "oneShot", true);
            }
        }

        private static Object accept(List<?> arguments) throws IOException {
            if (arguments.isEmpty()) throw new IllegalArgumentException(
                    "socket.accept requires a port");
            int port = port(arguments.getFirst());
            int maximum = arguments.size() > 1
                    ? positiveInt(arguments.get(1), "maximum bytes") : MAX_COMMAND_OUTPUT_BYTES;
            try (ServerSocket server = new ServerSocket()) {
                server.setSoTimeout(30_000);
                server.bind(new InetSocketAddress(port));
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(30_000);
                    return Map.of("remote", socket.getRemoteSocketAddress().toString(),
                            "data", read(socket.getInputStream(), maximum));
                }
            }
        }

        private static String read(java.io.InputStream input, int maximum) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (output.size() <= maximum) {
                int count = input.read(buffer, 0,
                        Math.min(buffer.length, maximum + 1 - output.size()));
                if (count < 0) break;
                output.write(buffer, 0, count);
            }
            if (output.size() > maximum) throw new IOException("Socket payload exceeds limit");
            return output.toString(StandardCharsets.UTF_8);
        }

        private static Endpoint endpoint(List<?> arguments, int offset) {
            if (arguments.size() <= offset + 1) throw new IllegalArgumentException(
                    "Socket endpoint requires host and port");
            return new Endpoint(String.valueOf(arguments.get(offset)),
                    port(arguments.get(offset + 1)));
        }

        private static Endpoint endpoint(Map<?, ?> map) {
            Object host = map.get("host");
            Object port = map.get("port");
            if (!(host instanceof String text)) throw new IllegalArgumentException(
                    "Socket endpoint host is missing");
            return new Endpoint(text, port(port));
        }

        private static int port(Object value) {
            int port = positiveInt(value, "port");
            if (port > 65_535) throw new IllegalArgumentException("Port exceeds 65535");
            return port;
        }

        private static int positiveInt(Object value, String field) {
            if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                    || number.intValue() < 1) {
                throw new IllegalArgumentException(field + " must be a positive integer");
            }
            return number.intValue();
        }

        private static Map<String, Object> endpointMap(Endpoint endpoint) {
            return Map.of("host", endpoint.host(), "port", (long) endpoint.port(),
                    "oneShot", true);
        }

        private record Endpoint(String host, int port) {}
    }

    private static Set<String> commandAllowlist() {
        String configured = System.getenv("CILEXEC_FCL_EXEC_ALLOWLIST");
        if (configured == null || configured.isBlank()) return Set.of();
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (String value : configured.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) values.add(trimmed);
        }
        return Set.copyOf(values);
    }
}
