package com.follarce.effect;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Sends HTTP to the exact address approved by {@link NetworkTargetPolicy}. */
final class PinnedHttpClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int REQUEST_TIMEOUT_MILLIS = 30_000;
    /** Total cap on one effect HTTP exchange; per-read timeouts alone allow slowloris trickles. */
    private static final long TOTAL_EXCHANGE_DEADLINE_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(120);

    static {
        // This switch is required for DNS pinning: the request URL carries the validated IP
        // while the original authority must still reach the server as the Host header (and
        // name-based virtual hosts). The switch is JVM-global, so every URLConnection on
        // this JVM may set "restricted" headers (Host, Connection, etc.); that is the accepted
        // price of pinning without a custom protocol handler.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    private PinnedHttpClient() { }

    static Response send(URI uri, String method, Optional<String> body,
                         Map<String, String> headers) throws IOException {
        long startedAtNanos = System.nanoTime();
        NetworkTargetPolicy.ResolvedHttpTarget target =
                NetworkTargetPolicy.resolveHttpTarget(uri);
        boolean proxied = target.throughTrustedProxy();
        URL requestUrl = proxied ? uri.toURL() : target.pinnedUri().toURL();
        Proxy proxy = proxied
                ? NetworkTargetPolicy.trustedProxy().orElseThrow()
                : Proxy.NO_PROXY;
        HttpURLConnection connection = (HttpURLConnection) requestUrl.openConnection(proxy);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(REQUEST_TIMEOUT_MILLIS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Host", target.hostHeader());
            headers.forEach(connection::setRequestProperty);
            if (!proxied && connection instanceof HttpsURLConnection https) {
                // No custom HostnameVerifier: with the default verifier, HttpsClient
                // enables standard TLS endpoint identification, which checks the
                // ORIGINAL host name against the certificate. The socket factory layers
                // TLS over the validated address while setting the TLS peer host (and
                // SNI) to that original name.
                https.setSSLSocketFactory(new PinnedSslSocketFactory(
                        (SSLSocketFactory) SSLSocketFactory.getDefault(),
                        target.addresses(), uri.getHost()));
            }
            if ("POST".equals(method)) {
                byte[] content = body.orElse("").getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(content.length);
                try (var output = connection.getOutputStream()) {
                    output.write(content);
                }
            } else if (!"GET".equals(method)) {
                throw new IllegalArgumentException("Unsupported pinned HTTP method: " + method);
            }
            enforceDeadline(startedAtNanos);
            int status = connection.getResponseCode();
            enforceDeadline(startedAtNanos);
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            connection.getHeaderFields().forEach((name, values) -> {
                if (name != null && values != null) {
                    responseHeaders.put(name, List.copyOf(values));
                }
            });
            InputStream stream;
            try {
                stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            } catch (IOException failedBody) {
                stream = connection.getErrorStream();
                if (stream == null) {
                    throw failedBody;
                }
            }
            if (stream == null) stream = new ByteArrayInputStream(new byte[0]);
            return new Response(status, Map.copyOf(responseHeaders),
                    new DeadlineInputStream(new DisconnectingInputStream(stream, connection),
                            startedAtNanos));
        } catch (IOException | RuntimeException failure) {
            // A response was never produced: release the connection so the effect
            // worker does not hold it until GC.
            connection.disconnect();
            throw failure;
        }
    }

    /**
     * Response whose body stream owns the connection: closing it releases the connection,
     * and the whole response can be used in try-with-resources so error paths cannot leak.
     */
    record Response(int statusCode, Map<String, List<String>> headers, InputStream body)
            implements AutoCloseable {
        Optional<String> firstHeader(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst();
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    private static final class DisconnectingInputStream extends FilterInputStream {
        private final HttpURLConnection connection;

        private DisconnectingInputStream(InputStream input, HttpURLConnection connection) {
            super(input);
            this.connection = connection;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                connection.disconnect();
            }
        }
    }

    /** Fails fast once the overall exchange deadline has passed, before/after header reads. */
    private static void enforceDeadline(long startedAtNanos) throws IOException {
        if (System.nanoTime() - startedAtNanos > TOTAL_EXCHANGE_DEADLINE_NANOS) {
            throw new IOException("HTTP exchange exceeded the 120-second total deadline");
        }
    }

    /** Bounds the whole body read to the overall exchange deadline, not just one read. */
    private static final class DeadlineInputStream extends FilterInputStream {
        private final long startedAtNanos;

        private DeadlineInputStream(InputStream input, long startedAtNanos) {
            super(input);
            this.startedAtNanos = startedAtNanos;
        }

        @Override
        public int read() throws IOException {
            checkDeadline();
            return super.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            checkDeadline();
            return super.read(bytes, offset, length);
        }

        private void checkDeadline() throws IOException {
            if (System.nanoTime() - startedAtNanos > TOTAL_EXCHANGE_DEADLINE_NANOS) {
                throw new IOException("HTTP response exceeded the 120-second total deadline");
            }
        }
    }

    /**
     * Creates TLS over a socket connected to an already checked address, with the original
     * host name as the TLS peer host. sun.net's HttpsClient first connects its own socket
     * (to the pinned IP carried by the URL) and then wraps it through
     * {@link #createSocket(Socket, String, int, boolean)}; that wrapper layers TLS over the
     * already-connected socket, so the standard endpoint-identification check runs against
     * the original host name and SNI uses it too. {@link #createSocket()} therefore returns
     * a plain socket on purpose, forcing the wrapping path.
     */
    private static final class PinnedSslSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final List<InetAddress> addresses;
        private final String tlsHost;

        private PinnedSslSocketFactory(SSLSocketFactory delegate, List<InetAddress> addresses,
                                       String tlsHost) {
            this.delegate = delegate;
            this.addresses = addresses;
            this.tlsHost = tlsHost;
        }

        @Override public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override public Socket createSocket() throws IOException {
            return new Socket();
        }

        @Override public Socket createSocket(String ignoredHost, int port) throws IOException {
            return tlsSocket(port, null, -1);
        }

        @Override public Socket createSocket(String ignoredHost, int port,
                                             InetAddress localAddress, int localPort)
                throws IOException {
            return tlsSocket(port, localAddress, localPort);
        }

        @Override public Socket createSocket(InetAddress ignoredAddress, int port)
                throws IOException {
            return tlsSocket(port, null, -1);
        }

        @Override public Socket createSocket(InetAddress ignoredAddress, int port,
                                             InetAddress localAddress, int localPort)
                throws IOException {
            return tlsSocket(port, localAddress, localPort);
        }

        @Override public Socket createSocket(Socket supplied, String ignoredHost, int port,
                                             boolean autoClose) throws IOException {
            if (supplied != null && supplied.isConnected() && !supplied.isClosed()
                    && validated(supplied.getInetAddress())) {
                // The caller already connected to one of the policy-validated addresses;
                // layer TLS over it with the original host name as the peer host.
                return delegate.createSocket(supplied, tlsHost, port, true);
            }
            if (supplied != null && autoClose) closeQuietly(supplied);
            return tlsSocket(port, null, -1);
        }

        private boolean validated(InetAddress candidate) {
            return candidate != null && addresses.stream().anyMatch(candidate::equals);
        }

        private Socket tlsSocket(int port, InetAddress localAddress, int localPort)
                throws IOException {
            IOException lastFailure = null;
            for (InetAddress address : addresses) {
                Socket plain = new Socket();
                try {
                    if (localAddress != null) {
                        plain.bind(new InetSocketAddress(localAddress, localPort));
                    }
                    plain.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
                    return delegate.createSocket(plain, tlsHost, port, true);
                } catch (IOException failure) {
                    lastFailure = failure;
                    closeQuietly(plain);
                }
            }
            if (lastFailure == null) {
                throw new IOException("No validated address for " + tlsHost);
            }
            throw lastFailure;
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The original failure remains the useful signal.
            }
        }
    }
}
