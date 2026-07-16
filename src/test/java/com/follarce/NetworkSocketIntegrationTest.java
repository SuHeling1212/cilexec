package com.follarce;

import com.follarce.function.FunctionContext;
import com.follarce.function.NetworkFunctionProvider;
import com.follarce.function.SocketFunctionProvider;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class NetworkSocketIntegrationTest {
    private final FunctionContext context = new FunctionContext(1, 0, "local");

    @Test
    void networkProviderPerformsLocalHttpGetAndPost() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> handled = CompletableFuture.runAsync(() -> serveHttp(server, 2));
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/test";
            NetworkFunctionProvider network = new NetworkFunctionProvider();
            assertTrue(network.call("httpGet", List.of(url), context).toString().endsWith("GET"));
            assertTrue(network.call("httpPost", List.of(url, "payload"), context).toString().endsWith("POST:payload"));
            handled.join();
        }
    }

    @Test
    void socketProviderConnectsSendsReceivesAndCloses() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> handled = CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     OutputStream out = peer.getOutputStream()) {
                    String line = in.readLine();
                    out.write(("reply:" + line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            SocketFunctionProvider socket = new SocketFunctionProvider();
            int id = Integer.parseInt((String) socket.call("connect", List.of("127.0.0.1", server.getLocalPort()), context));
            assertEquals("Sent 4 bytes", socket.call("send", List.of(id, "ping"), context));
            assertEquals("reply:ping", socket.call("receive", List.of(id), context));
            assertEquals("Connection closed", socket.call("close", List.of(id), context));
            handled.join();
        }
    }

    private static void serveHttp(ServerSocket server, int requests) {
        try {
            for (int i = 0; i < requests; i++) {
                try (Socket peer = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     OutputStream out = peer.getOutputStream()) {
                    String request = in.readLine();
                    int contentLength = 0;
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    char[] body = new char[contentLength];
                    if (contentLength > 0) in.read(body);
                    String response = request.startsWith("POST") ? "POST:" + new String(body) : "GET";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(bytes);
                    out.flush();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
