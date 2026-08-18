package com.follarce.effect;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that the pinned HTTPS client layers TLS over the policy-approved address
 * while checking the certificate against the originally requested host name. The test
 * JVM's default SSL context is temporarily replaced with one trusting only the generated
 * self-signed SAN certificate, and restored afterwards, so no other test sees the change.
 */
class PinnedHttpClientSslTest {
    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final String TRUST_STORE_TYPE = "PKCS12";
    private static final String ALIAS = "loopback-test";

    @TempDir
    static Path temporary;

    private static SSLContext originalDefault;
    private static KeyStore signedKeyStore;

    @BeforeAll
    static void trustOnlyTheGeneratedSanCertificate() throws Exception {
        System.setProperty("cilexec.networkAllowPrivateHosts", "127.0.0.1,localhost");
        Path keyStorePath = temporary.resolve("loopback.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", ALIAS, "-keyalg",
                "RSA", "-keysize", "2048", "-validity", "30", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-keystore", keyStorePath.toString(),
                "-storetype", TRUST_STORE_TYPE, "-storepass", "changeit", "-noprompt")
                .inheritIO()
                .start()
                .waitFor();
        signedKeyStore = KeyStore.getInstance(TRUST_STORE_TYPE);
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            signedKeyStore.load(input, PASSWORD);
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trust.init(signedKeyStore);
        SSLContext trusting = SSLContext.getInstance("TLS");
        trusting.init(null, trust.getTrustManagers(), null);
        originalDefault = SSLContext.getDefault();
        SSLContext.setDefault(trusting);
    }

    @AfterAll
    static void restoreTheDefaultSslContext() {
        if (originalDefault != null) {
            SSLContext.setDefault(originalDefault);
        }
        System.clearProperty("cilexec.networkAllowPrivateHosts");
    }

    @Test
    void directHttpsAcceptsTheValidatedHostOnTheCertificate() throws Exception {
        HttpsServer server = httpsServer();
        try {
            assertEquals("ok", body(PinnedHttpClient.send(java.net.URI.create(
                    "https://localhost:" + server.getAddress().getPort() + "/tls"),
                    "GET", Optional.empty(), Map.of())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directHttpsRejectsAPinnedHostThatTheCertificateDoesNotCover() throws Exception {
        HttpsServer server = httpsServer();
        try {
            // The certificate only carries SAN dns:localhost: the pinned TLS connection
            // must fail TLS endpoint identification for the IP literal even though the
            // connection itself reached the exact validated address.
            assertThrows(IOException.class, () -> PinnedHttpClient.send(java.net.URI.create(
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/tls"),
                    "GET", Optional.empty(), Map.of()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpsThroughTheTrustedProxyTunnelsToThePinnedVirtualHost() throws Exception {
        HttpsServer target = httpsServer();
        TestProxy proxy = new TestProxy();
        try {
            System.setProperty("cilexec.networkTrustProxy", "true");
            System.setProperty("cilexec.networkProxy",
                    "127.0.0.1:" + proxy.port());
            try {
                assertEquals("ok", body(PinnedHttpClient.send(java.net.URI.create(
                        "https://localhost:" + target.getAddress().getPort() + "/tls"),
                        "GET", Optional.empty(), Map.of())));
            } finally {
                System.clearProperty("cilexec.networkTrustProxy");
                System.clearProperty("cilexec.networkProxy");
            }
            proxy.close();
            target.stop(0);
        } catch (Throwable failure) {
            proxy.close();
            target.stop(0);
            throw failure;
        }
    }

    private static HttpsServer httpsServer() throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keys.init(signedKeyStore, PASSWORD);
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        server.createContext("/tls", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String body(PinnedHttpClient.Response response) throws IOException {
        try (PinnedHttpClient.Response ignored = response) {
            return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Minimal CONNECT proxy: answers with {@code 200 Connection established} and then
     * pipes raw bytes in both directions so the pinned TLS handshake flows untouched.
     */
    private static final class TestProxy implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();

        private TestProxy() throws IOException {
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            Thread.ofVirtual().name("test-connect-proxy").start(() -> {
                while (running.get()) {
                    try {
                        Socket client = server.accept();
                        Thread.ofVirtual().start(() -> tunnel(client));
                    } catch (IOException closed) {
                        running.set(false);
                    }
                }
            });
        }

        private int port() {
            return server.getLocalPort();
        }

        private void tunnel(Socket client) {
            sockets.add(client);
            try {
                client.setSoTimeout(10_000);
                InputStream input = client.getInputStream();
                java.io.BufferedReader headers = new java.io.BufferedReader(
                        new java.io.InputStreamReader(input, StandardCharsets.US_ASCII));
                String requestLine = headers.readLine();
                String line;
                while ((line = headers.readLine()) != null && !line.isEmpty()) {
                    // Consume the CONNECT headers; nothing to extract for the tunnel.
                }
                if (requestLine == null || !requestLine.startsWith("CONNECT ")) {
                    client.close();
                    return;
                }
                String hostPort = requestLine.split(" ")[1];
                int separator = hostPort.lastIndexOf(':');
                Socket upstream = new Socket(hostPort.substring(0, separator),
                        Integer.parseInt(hostPort.substring(separator + 1)));
                sockets.add(upstream);
                OutputStream output = client.getOutputStream();
                output.write("HTTP/1.1 200 Connection established\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                OutputStream upstreamOutput = upstream.getOutputStream();
                Thread.ofVirtual().start(() -> relay(input, upstreamOutput));
                relay(upstream.getInputStream(), output);
            } catch (IOException finished) {
                // Either direction reached EOF or the test closed the sockets.
            } finally {
                sockets.forEach(socket -> {
                    try {
                        socket.close();
                    } catch (IOException ignoredClose) {
                        // Tunnel teardown is best effort.
                    }
                });
                sockets.clear();
            }
        }

        private static void relay(InputStream input, OutputStream output) {
            try {
                input.transferTo(output);
            } catch (IOException endOfTunnel) {
                // The peer closed its side; the other direction owns the final close.
            }
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            server.close();
        }
    }
}