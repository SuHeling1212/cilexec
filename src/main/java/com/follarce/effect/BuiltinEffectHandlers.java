package com.follarce.effect;

import com.follarce.domain.process.Continuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.terminal.TerminalOutputRouter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.InetAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Production handlers for the external effects exposed by the built-in FCL namespaces. */
public final class BuiltinEffectHandlers {
    private static final int MAX_HTTP_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DOWNLOAD_CHUNK_BYTES = 4 * 1024 * 1024;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 1024 * 1024;

    private BuiltinEffectHandlers() {}

    /** Exact long conversion: doubles lose precision above 2^53, so never compare via double. */
    static long exactLong(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        if (number instanceof Long || number instanceof Integer || number instanceof Short
                || number instanceof Byte) {
            return number.longValue();
        }
        if (number instanceof java.math.BigInteger bigInteger) {
            try {
                return bigInteger.longValueExact();
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(field + " must be an integer in long range");
            }
        }
        if (number instanceof java.math.BigDecimal decimal) {
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException(field + " must be an integer in long range");
            }
        }
        double asDouble = number.doubleValue();
        // (double) Long.MAX_VALUE rounds up to 2^63, so "> Long.MAX_VALUE" would let 2^63
        // through and saturate it to Long.MAX_VALUE; 2^63 is not representable as a long.
        if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble)
                || asDouble < -0x1p63 || asDouble >= 0x1p63) {
            throw new IllegalArgumentException(field + " must be an integer in long range");
        }
        return (long) asDouble;
    }

    public static List<EffectHandler> defaults() {
        return defaults(commandAllowlist());
    }

    public static List<EffectHandler> defaults(Set<String> allowedExecutables) {
        List<EffectHandler> handlers = new ArrayList<>();
        handlers.add(new OutputHandler());
        handlers.add(new HttpHandler("network.http-get", "GET"));
        handlers.add(new HttpHandler("network.http-post", "POST"));
        handlers.add(new DownloadHandler());
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
            Object route = map.get("routeId");
            if (route != null) {
                if (!(route instanceof String routeText)) {
                    throw new IllegalArgumentException("Effect field must be a string: routeId");
                }
                java.util.UUID routeId;
                try {
                    routeId = java.util.UUID.fromString(routeText);
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("Effect field must be a UUID string: routeId",
                            invalid);
                }
                if (!TerminalOutputRouter.attached(routeId)) {
                    // The terminal session is gone: drop the output instead of failing the
                    // process. A background process must survive its terminal disconnecting.
                    return null;
                }
                if (!TerminalOutputRouter.publish(routeId, text, newline)) {
                    throw new IllegalStateException("Output could not be delivered to terminal "
                            + routeId + " (delivery timed out)");
                }
            }
            // Detached process output remains in the durable effect result; never leak user
            // content into a different session or the container log.
            return null;
        }
    }

    private static final class HttpHandler extends TypedHandler {
        private final String type;
        private final String method;
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
            Optional<String> body = method.equals("POST")
                    ? Optional.of(text(map, "body")) : Optional.empty();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "CilExec-FCL/1");
            idempotencyKey.filter(key -> !key.isBlank())
                    .ifPresent(key -> headers.put("Idempotency-Key", key));
            PinnedHttpClient.Response response = PinnedHttpClient.send(uri, method, body,
                    headers);
            requireNoRedirect(response.statusCode());
            byte[] responseBody;
            try (InputStream input = response.body()) {
                responseBody = input.readNBytes(MAX_HTTP_BODY_BYTES + 1);
                if (responseBody.length > MAX_HTTP_BODY_BYTES) {
                    throw new IOException("HTTP response exceeds 4 MiB");
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", (long) response.statusCode());
            result.put("body", new String(responseBody, StandardCharsets.UTF_8));
            result.put("headers", response.headers());
            return Map.copyOf(result);
        }
    }

    /** Downloads one bounded binary range without materializing a complete large file. */
    private static final class DownloadHandler extends TypedHandler {
        @Override
        public String effectType() {
            return "network.download";
        }

        @Override
        protected Object executeValue(Object request, Optional<String> idempotencyKey)
                throws Exception {
            Map<?, ?> map = requestMap(request);
            URI uri = URI.create(text(map, "url"));
            long offset = map.containsKey("offset")
                    ? nonNegativeLong(map.get("offset"), "download offset") : 0L;
            int maximum = map.containsKey("maximumBytes")
                    ? positiveInt(map.get("maximumBytes"), "download maximum bytes")
                    : MAX_DOWNLOAD_CHUNK_BYTES;
            if (maximum > MAX_DOWNLOAD_CHUNK_BYTES) {
                throw new IllegalArgumentException("Download chunks cannot exceed 4 MiB");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "CilExec-FCL/1");
            idempotencyKey.filter(key -> !key.isBlank())
                    .ifPresent(key -> headers.put("Idempotency-Key", key));
            headers.put("Range", "bytes=" + offset + "-"
                    + Math.addExact(offset, maximum - 1L));
            Object validator = map.get("validator");
            if (validator instanceof String value && !value.isBlank()) {
                headers.put("If-Range", value);
            }
            PinnedHttpClient.Response response = PinnedHttpClient.send(uri, "GET",
                    Optional.empty(), headers);
            requireNoRedirect(response.statusCode());
            requireDownloadStatus(response.statusCode());
            byte[] body;
            try (InputStream input = response.body()) {
                body = readBounded(input, maximum, response.statusCode());
            }
            Range range = range(response, offset, body.length);
            // A 416 at the exact end of the object is a successful EOF probe, not file data.
            if (response.statusCode() == 416) body = new byte[0];
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", (long) response.statusCode());
            result.put("bodyBase64", Base64.getEncoder().encodeToString(body));
            result.put("bytes", (long) body.length);
            result.put("offset", range.offset());
            result.put("complete", range.complete());
            if (range.totalBytes() >= 0) result.put("totalBytes", range.totalBytes());
            result.put("mediaType", response.firstHeader("Content-Type")
                    .map(value -> value.split(";", 2)[0].trim())
                    .filter(value -> !value.isBlank())
                    .orElse("application/octet-stream"));
            response.firstHeader("ETag")
                    .filter(value -> !value.startsWith("W/"))
                    .or(() -> response.firstHeader("Last-Modified"))
                    .ifPresent(value -> result.put("validator", value));
            return Map.copyOf(result);
        }

        private static byte[] readBounded(InputStream input, int maximum, int status)
                throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.min(maximum, 64 * 1024));
            byte[] buffer = new byte[Math.min(8192, maximum + 1)];
            while (output.size() <= maximum) {
                int count = input.read(buffer, 0,
                        Math.min(buffer.length, maximum + 1 - output.size()));
                if (count < 0) break;
                if (count == 0) {
                    int single = input.read();
                    if (single < 0) break;
                    output.write(single);
                    continue;
                }
                output.write(buffer, 0, count);
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length > maximum) {
                throw new IOException(status == 200
                        ? "Server ignored byte-range request for a large download"
                        : "Downloaded range exceeds 4 MiB");
            }
            return bytes;
        }

        private static Range range(PinnedHttpClient.Response response, long requestedOffset,
                                   int bodyLength) throws IOException {
            int status = response.statusCode();
            if (status == 200) {
                if (requestedOffset != 0) {
                    throw new IOException("Remote file changed or server stopped honoring ranges");
                }
                return new Range(0, bodyLength, true);
            }
            String contentRange = response.firstHeader("Content-Range").orElse("");
            if (status == 416) {
                java.util.regex.Matcher end = java.util.regex.Pattern
                        .compile("bytes \\*/([0-9]+)").matcher(contentRange);
                if (!end.matches()) throw new IOException("Invalid Content-Range response");
                long total = parseRangeLong(end.group(1));
                if (total != requestedOffset) {
                    throw new IOException("Remote range is inconsistent with the download offset");
                }
                return new Range(requestedOffset, total, true);
            }
            java.util.regex.Matcher partial = java.util.regex.Pattern
                    .compile("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)")
                    .matcher(contentRange);
            if (!partial.matches()) throw new IOException("Invalid Content-Range response");
            long start = parseRangeLong(partial.group(1));
            long end = parseRangeLong(partial.group(2));
            long rangeLength;
            try {
                rangeLength = Math.addExact(Math.subtractExact(end, start), 1L);
            } catch (ArithmeticException invalid) {
                throw new IOException("Invalid Content-Range response", invalid);
            }
            if (end < start || start != requestedOffset || rangeLength != bodyLength) {
                throw new IOException("Downloaded range does not match the request");
            }
            long total = partial.group(3).equals("*")
                    ? -1L : parseRangeLong(partial.group(3));
            if (total >= 0 && end >= total) {
                throw new IOException("Content-Range exceeds the reported object size");
            }
            return new Range(start, total, total >= 0 && end == total - 1L);
        }

        private static long parseRangeLong(String value) throws IOException {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException invalid) {
                throw new IOException("Invalid Content-Range response", invalid);
            }
        }

        private static int positiveInt(Object value, String field) {
            long exact = exactLong(value, field);
            if (exact < 1 || exact > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(field + " must be a positive integer");
            }
            return (int) exact;
        }

        private static long nonNegativeLong(Object value, String field) {
            long exact = exactLong(value, field);
            if (exact < 0) {
                throw new IllegalArgumentException(field + " must be a non-negative integer");
            }
            return exact;
        }

        private record Range(long offset, long totalBytes, boolean complete) { }
    }

    private static final class CommandHandler extends TypedHandler {
        private final Set<String> allowedExecutables;

        private CommandHandler(Set<String> allowedExecutables) {
            java.util.LinkedHashSet<String> canonical = new java.util.LinkedHashSet<>();
            for (String executable : allowedExecutables) {
                try {
                    java.nio.file.Path path = java.nio.file.Path.of(executable);
                    if (!path.isAbsolute()) {
                        throw new IllegalArgumentException(
                                "Command allowlist entries must be absolute paths: " + executable);
                    }
                    java.nio.file.Path real = path.toRealPath();
                    if (!java.nio.file.Files.isRegularFile(real)
                            || !java.nio.file.Files.isExecutable(real)) {
                        throw new IllegalArgumentException(
                                "Command allowlist entry is not executable: " + executable);
                    }
                    canonical.add(real.toString());
                } catch (IOException invalid) {
                    throw new IllegalArgumentException(
                            "Cannot resolve command allowlist entry: " + executable, invalid);
                }
            }
            this.allowedExecutables = Set.copyOf(canonical);
        }

        @Override
        public String effectType() {
            return "system.exec";
        }

        @Override
        protected Object executeValue(Object request, Optional<String> idempotencyKey)
                throws Exception {
            Map<?, ?> map = requestMap(request);
            List<String> supplied = command(map.get("command"));
            java.nio.file.Path executablePath = java.nio.file.Path.of(supplied.getFirst());
            if (!executablePath.isAbsolute()) {
                throw new SecurityException("Command executable must be an absolute path");
            }
            String executable = executablePath.toRealPath().toString();
            if (!allowedExecutables.contains(executable)) {
                throw new SecurityException(
                        "Executable is not in CILEXEC_FCL_EXEC_ALLOWLIST: " + executable);
            }
            List<String> command = new ArrayList<>(supplied);
            command.set(0, executable);
            Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
            java.util.concurrent.FutureTask<byte[]> reader = new java.util.concurrent.FutureTask<>(
                    () -> readCommandOutput(child));
            Thread.ofVirtual().name("cilexec-command-output").start(reader);
            try {
                if (!child.waitFor(30, TimeUnit.SECONDS)) {
                    destroyProcessTree(child);
                    throw new IOException("Command timed out after 30 seconds");
                }
                byte[] output = reader.get(2, TimeUnit.SECONDS);
                return Map.of("exitCode", (long) child.exitValue(), "output",
                        new String(output, StandardCharsets.UTF_8));
            } catch (java.util.concurrent.ExecutionException failedRead) {
                destroyProcessTree(child);
                Throwable cause = failedRead.getCause();
                if (cause instanceof Exception exception) throw exception;
                throw new IOException("Command output reader failed", cause);
            } catch (java.util.concurrent.TimeoutException failedRead) {
                destroyProcessTree(child);
                throw new IOException("Command output reader did not finish", failedRead);
            } finally {
                if (child.isAlive()) destroyProcessTree(child);
                reader.cancel(true);
            }
        }

        private static byte[] readCommandOutput(Process child) throws IOException {
            try (InputStream input = child.getInputStream()) {
                byte[] output = input.readNBytes(MAX_COMMAND_OUTPUT_BYTES + 1);
                if (output.length > MAX_COMMAND_OUTPUT_BYTES) {
                    destroyProcessTree(child);
                    throw new IOException("Command output exceeds 1 MiB");
                }
                return output;
            }
        }

        private static void destroyProcessTree(Process child) {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
        }

        private static List<String> command(Object value) {
            if (value instanceof String text) {
                String trimmed = text.trim();
                if (trimmed.isEmpty()) throw new IllegalArgumentException("Command is empty");
                if (trimmed.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("Command string must not contain NUL");
                }
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
            InetAddress address = NetworkTargetPolicy.requirePublicAddress(endpoint.host());
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, endpoint.port()), 10_000);
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
            Object rawData = arguments.get(dataIndex);
            if (!(rawData instanceof String text)) {
                throw new IllegalArgumentException("socket.send data must be a string");
            }
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_COMMAND_OUTPUT_BYTES) {
                throw new IllegalArgumentException("Socket payload cannot exceed 1 MiB");
            }
            InetAddress address = NetworkTargetPolicy.requirePublicAddress(endpoint.host());
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, endpoint.port()), 10_000);
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
            requireBoundedPayload(maximum);
            InetAddress address = NetworkTargetPolicy.requirePublicAddress(endpoint.host());
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, endpoint.port()), 10_000);
                socket.setSoTimeout(30_000);
                return read(socket.getInputStream(), maximum);
            }
        }

        private static Object bind(List<?> arguments) throws IOException {
            int port = arguments.isEmpty() ? 0 : port(arguments.getFirst());
            try (ServerSocket server = new ServerSocket()) {
                server.bind(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
                return Map.of("host", "127.0.0.1", "port", (long) server.getLocalPort(),
                        "oneShot", true);
            }
        }

        private static Object accept(List<?> arguments) throws IOException {
            if (arguments.isEmpty()) throw new IllegalArgumentException(
                    "socket.accept requires a port");
            int port = port(arguments.getFirst());
            int maximum = arguments.size() > 1
                    ? positiveInt(arguments.get(1), "maximum bytes") : MAX_COMMAND_OUTPUT_BYTES;
            requireBoundedPayload(maximum);
            try (ServerSocket server = new ServerSocket()) {
                server.setSoTimeout(30_000);
                server.bind(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(30_000);
                    return Map.of("remote", socket.getRemoteSocketAddress().toString(),
                            "data", read(socket.getInputStream(), maximum));
                }
            }
        }

        private static String read(java.io.InputStream input, int maximum) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 64 * 1024));
            byte[] buffer = new byte[8192];
            while (output.size() <= maximum) {
                int count = input.read(buffer, 0,
                        Math.min(buffer.length, maximum + 1 - output.size()));
                if (count < 0) break;
                if (count == 0) {
                    int single = input.read();
                    if (single < 0) break;
                    output.write(single);
                    continue;
                }
                output.write(buffer, 0, count);
            }
            if (output.size() > maximum) throw new IOException("Socket payload exceeds limit");
            return output.toString(StandardCharsets.UTF_8);
        }

        private static Endpoint endpoint(List<?> arguments, int offset) {
            if (arguments.size() <= offset + 1) throw new IllegalArgumentException(
                    "Socket endpoint requires host and port");
            Object rawHost = arguments.get(offset);
            if (!(rawHost instanceof String host) || host.isBlank()) {
                throw new IllegalArgumentException("Socket endpoint host must be a string");
            }
            return new Endpoint(host, port(arguments.get(offset + 1)));
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
            long exact = exactLong(value, field);
            if (exact < 1 || exact > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(field + " must be a positive integer");
            }
            return (int) exact;
        }

        private static void requireBoundedPayload(int maximum) {
            if (maximum > MAX_COMMAND_OUTPUT_BYTES) {
                throw new IllegalArgumentException("Socket payload cannot exceed 1 MiB");
            }
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

    private static void requireNoRedirect(int status) throws IOException {
        if (status >= 300 && status < 400) {
            throw new IOException("HTTP redirects are blocked; use the validated final URL");
        }
    }

    private static void requireDownloadStatus(int status) throws IOException {
        if (status != 200 && status != 206 && status != 416) {
            throw new IOException("Download failed with HTTP status " + status);
        }
    }
}
