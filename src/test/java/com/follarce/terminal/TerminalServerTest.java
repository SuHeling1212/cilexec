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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalServerTest {
    @Test
    void headlessProtocolAuthenticatesAndSeparatesDurableTerminalContexts() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        UserAccount local = UserAccount.active(UUID.randomUUID(), "local", Instant.now());
        TerminalAccess access = access(local);
        ConcurrentHashMap<String, AtomicInteger> contexts = new ConcurrentHashMap<>();

        try (TerminalServer server = new TerminalServer(port, access,
                account -> command -> "",
                (account, context) -> new TerminalControl() {
                    @Override public String execute(ShellCommand command) { return ""; }
                    @Override public String evaluate(String source) {
                        return Integer.toString(contexts.computeIfAbsent(context,
                                ignored -> new AtomicInteger()).incrementAndGet());
                    }
                }, "local")) {
            server.start();
            assertEquals("1\n\0R 0\n", headlessSession(port, "tty-a", "local",
                    "password123", "next()"));
            assertEquals("2\n\0R 0\n", headlessSession(port, "tty-a", "local",
                    "password123", "next()"));
            assertEquals("1\n\0R 0\n", headlessSession(port, "tty-b", "local",
                    "password123", "next()"));
            assertEquals("3\n\0R 0\n", headlessSession(port, "tty-a", "local",
                    "密碼123456", "next()"));
            assertEquals("error: invalid username or password\n\0R 77\n",
                    headlessSession(port, "tty-a", "local", "wrong", "next()"));
        }
    }

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

    @Test
    void passesInteractiveContextToTheAuthenticatedTerminalControl() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        UserAccount local = UserAccount.active(UUID.randomUUID(), "local", Instant.now());
        AtomicReference<String> context = new AtomicReference<>();

        try (TerminalServer server = new TerminalServer(port, access(local),
                (account, interactiveContext) -> {
                    context.set(interactiveContext);
                    return command -> "";
                }, (account, ignored) -> command -> "", "local")) {
            server.start();

            String transcript = session(port, "local", 24, 80, "host-project-tty");

            assertTrue(transcript.contains("authenticated as local"), transcript);
            assertEquals("host-project-tty", context.get());
        }
    }

    private static String session(int port, String username, int height, int width) {
        return session(port, username, height, width, "");
    }

    private static String session(int port, String username, int height, int width, String context) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3_000);
            String marker = context.isEmpty() ? "\0M INTERACTIVE\n"
                    : "\0M INTERACTIVE " + context + "\n";
            socket.getOutputStream().write(((marker + "\0S " + height + " " + width + "\n")
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

    private static String headlessSession(int port, String context, String username,
                                          String password, String source) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3_000);
            var output = socket.getOutputStream();
            output.write("\0M HEADLESS\n".getBytes(StandardCharsets.US_ASCII));
            field(output, context);
            field(output, username);
            field(output, password);
            field(output, source);
            socket.shutdownOutput();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(response);
            return response.toString(StandardCharsets.UTF_8);
        }
    }

    private static void field(java.io.OutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write((bytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
    }

    private static TerminalAccess access(UserAccount account) {
        return new TerminalAccess() {
            @Override public Optional<UserAccount> login(String username, char[] password) {
                return username.equals(account.username())
                        && (java.util.Arrays.equals(password, "password123".toCharArray())
                        || java.util.Arrays.equals(password, "密碼123456".toCharArray()))
                        ? Optional.of(account) : Optional.empty();
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
    }
}
