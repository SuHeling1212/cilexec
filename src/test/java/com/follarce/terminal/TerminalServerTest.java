package com.follarce.terminal;

import com.follarce.domain.auth.UserAccount;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalServerTest {
    @Test
    void servesIndependentAuthenticatedConnectionsWithoutStoppingTheServer() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        UserAccount alice = UserAccount.active(UUID.randomUUID(), "alice", Instant.now());
        UserAccount bob = UserAccount.active(UUID.randomUUID(), "bob", Instant.now());
        TerminalAccess access = new TerminalAccess() {
            @Override public Optional<UserAccount> login(String username, char[] password) {
                return switch (username) {
                    case "alice" -> Optional.of(alice);
                    case "bob" -> Optional.of(bob);
                    default -> Optional.empty();
                };
            }
            @Override public UserAccount register(String username, char[] password) {
                throw new UnsupportedOperationException();
            }
            @Override public UserAccount register(String username, char[] password,
                                                  char[] adminPassword) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean isFirstUse() { return false; }
            @Override public UserAccount bootstrap(String username, char[] password) {
                throw new UnsupportedOperationException();
            }
        };

        try (TerminalServer server = new TerminalServer(port, access,
                account -> command -> "", "local")) {
            server.start();
            CompletableFuture<String> first = CompletableFuture.supplyAsync(
                    () -> session(port, "alice", 40, 120));
            CompletableFuture<String> second = CompletableFuture.supplyAsync(
                    () -> session(port, "bob", 24, 80));

            String aliceTranscript = first.get(5, TimeUnit.SECONDS);
            String bobTranscript = second.get(5, TimeUnit.SECONDS);
            assertTrue(aliceTranscript.contains("authenticated as alice"), aliceTranscript);
            assertTrue(bobTranscript.contains("authenticated as bob"), bobTranscript);
            assertTrue(TerminalDimensions.current(alice.userId()).equals(
                    new TerminalDimensions.Size(120, 40)));
            assertTrue(TerminalDimensions.current(bob.userId()).equals(
                    new TerminalDimensions.Size(80, 24)));
            assertTrue(server.isRunning(), "disconnecting both clients must not stop Runtime");
        }
    }

    private static String session(int port, String username, int height, int width) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write((("\0S " + height + " " + width + "\n")
                    + "login\n" + username + "\npassword123\n:exit\n")
                    .getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            ByteArrayOutputStream transcript = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(transcript);
            return transcript.toString(StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
