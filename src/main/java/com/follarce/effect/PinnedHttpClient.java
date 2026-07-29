package com.follarce.effect;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Sends HTTP to the exact address approved by {@link NetworkTargetPolicy}. */
final class PinnedHttpClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int REQUEST_TIMEOUT_MILLIS = 30_000;

    static {
        // The request URL contains the pinned IP. This URLConnection switch permits the
        // original authority to remain in Host, including for name-based virtual hosting.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    private PinnedHttpClient() { }

    static Response send(URI uri, String method, Optional<String> body,
                         Map<String, String> headers) throws IOException {
        NetworkTargetPolicy.ResolvedHttpTarget target =
                NetworkTargetPolicy.resolveHttpTarget(uri);
        HttpURLConnection connection = (HttpURLConnection) target.pinnedUri().toURL()
                .openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(REQUEST_TIMEOUT_MILLIS);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Host", target.hostHeader());
        headers.forEach(connection::setRequestProperty);
        if (connection instanceof HttpsURLConnection https) {
            var defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
            https.setHostnameVerifier((ignoredPinnedAddress, session) ->
                    defaultVerifier.verify(uri.getHost(), session));
            https.setSSLSocketFactory(new PinnedSslSocketFactory(
                    (SSLSocketFactory) SSLSocketFactory.getDefault(), target.address(),
                    uri.getHost()));
        }
        if ("POST".equals(method)) {
            byte[] content = body.orElse("").getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(content.length);
            try (var output = connection.getOutputStream()) {
                output.write(content);
            }
        } else if (!"GET".equals(method)) {
            connection.disconnect();
            throw new IllegalArgumentException("Unsupported pinned HTTP method: " + method);
        }
        int status = connection.getResponseCode();
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
                connection.disconnect();
                throw failedBody;
            }
        }
        if (stream == null) stream = new ByteArrayInputStream(new byte[0]);
        return new Response(status, Map.copyOf(responseHeaders),
                new DisconnectingInputStream(stream, connection));
    }

    record Response(int statusCode, Map<String, List<String>> headers, InputStream body) {
        Optional<String> firstHeader(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst();
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

    /** Creates TLS over a socket connected to the already checked IP, with original-name SNI. */
    private static final class PinnedSslSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final InetAddress address;
        private final String tlsHost;

        private PinnedSslSocketFactory(SSLSocketFactory delegate, InetAddress address,
                                       String tlsHost) {
            this.delegate = delegate;
            this.address = address;
            this.tlsHost = tlsHost;
        }

        @Override public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override public Socket createSocket() throws IOException {
            Socket socket = delegate.createSocket();
            if (socket instanceof SSLSocket ssl) {
                var parameters = ssl.getSSLParameters();
                try {
                    parameters.setServerNames(List.of(new SNIHostName(tlsHost)));
                } catch (IllegalArgumentException numericAddress) {
                    // Literal IP HTTPS origins do not use SNI; certificate IP matching still runs.
                }
                ssl.setSSLParameters(parameters);
            }
            return socket;
        }

        @Override public Socket createSocket(String ignoredHost, int port) throws IOException {
            return tlsSocket(new Socket(), port);
        }

        @Override public Socket createSocket(String ignoredHost, int port,
                                             InetAddress localAddress, int localPort)
                throws IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            return tlsSocket(socket, port);
        }

        @Override public Socket createSocket(InetAddress ignoredAddress, int port)
                throws IOException {
            return tlsSocket(new Socket(), port);
        }

        @Override public Socket createSocket(InetAddress ignoredAddress, int port,
                                             InetAddress localAddress, int localPort)
                throws IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            return tlsSocket(socket, port);
        }

        @Override public Socket createSocket(Socket supplied, String ignoredHost, int port,
                                             boolean autoClose) throws IOException {
            if (autoClose) supplied.close();
            return tlsSocket(new Socket(), port);
        }

        private Socket tlsSocket(Socket plain, int port) throws IOException {
            try {
                plain.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
                return delegate.createSocket(plain, tlsHost, port, true);
            } catch (IOException failure) {
                try {
                    plain.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }
    }
}
