package com.follarce.terminal;

import com.follarce.domain.auth.UserAccount;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight multi-user terminal transport owned by the one active Runtime JVM.
 * Connections authenticate independently; no connection owns or stops the Runtime.
 */
public final class TerminalServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TerminalServer.class);
    private static final int MAX_CONNECTIONS = 128;
    private static final int MAX_BUFFERED_INPUT_BYTES = 64 * 1024;
    private static final int TERMINAL_IDLE_TIMEOUT_MILLIS = 60_000;
    private static final long MIN_ACCEPT_BACKOFF_MILLIS = 250;
    private static final long MAX_ACCEPT_BACKOFF_MILLIS = 5_000;
    private static final long IDLE_WARN_LEAD_NANOS =
            java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
    private final int port;
    private final TerminalAccess access;
    private final Function<UserAccount, TerminalControl> controls;
    private final BiFunction<UserAccount, String, TerminalControl> headlessControls;
    private final String administratorUsername;
    private final long idleDisconnectNanos;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Set<Thread> sessionThreads = ConcurrentHashMap.newKeySet();
    private final Semaphore connectionSlots = new Semaphore(MAX_CONNECTIONS);
    private volatile ServerSocket server;
    private volatile Thread acceptor;

    public TerminalServer(int port, TerminalAccess access,
                          Function<UserAccount, TerminalControl> controls,
                          String administratorUsername) {
        this(port, access, controls, (account, ignored) -> controls.apply(account),
                administratorUsername, TerminalSettings.DEFAULT_IDLE_DISCONNECT);
    }

    public TerminalServer(int port, TerminalAccess access,
                          Function<UserAccount, TerminalControl> controls,
                          BiFunction<UserAccount, String, TerminalControl> headlessControls,
                          String administratorUsername) {
        this(port, access, controls, headlessControls, administratorUsername,
                TerminalSettings.DEFAULT_IDLE_DISCONNECT);
    }

    public TerminalServer(int port, TerminalAccess access,
                          Function<UserAccount, TerminalControl> controls,
                          BiFunction<UserAccount, String, TerminalControl> headlessControls,
                          String administratorUsername,
                          java.time.Duration idleDisconnect) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Terminal port is outside 1..65535");
        }
        if (idleDisconnect == null || idleDisconnect.isNegative() || idleDisconnect.isZero()) {
            throw new IllegalArgumentException("Idle disconnect must be positive");
        }
        this.port = port;
        this.access = java.util.Objects.requireNonNull(access, "access");
        this.controls = java.util.Objects.requireNonNull(controls, "controls");
        this.headlessControls = java.util.Objects.requireNonNull(headlessControls,
                "headlessControls");
        this.administratorUsername = java.util.Objects.requireNonNull(
                administratorUsername, "administratorUsername");
        this.idleDisconnectNanos = idleDisconnect.toNanos();
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        } catch (IOException failure) {
            running.set(false);
            closeServer();
            throw new IllegalStateException("Cannot bind terminal server on port " + port,
                    failure);
        }
        acceptor = Thread.ofVirtual().name("cilexec-terminal-acceptor").start(this::acceptLoop);
    }

    public boolean isRunning() {
        return running.get() && acceptor != null && acceptor.isAlive();
    }

    private void acceptLoop() {
        long backoffMillis = MIN_ACCEPT_BACKOFF_MILLIS;
        while (running.get()) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException failure) {
                if (!running.get()) return;
                LOG.warn("Terminal accept failed; retrying", failure);
                backoffMillis = Math.min(MAX_ACCEPT_BACKOFF_MILLIS, backoffMillis * 2);
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            backoffMillis = MIN_ACCEPT_BACKOFF_MILLIS;
            if (!connectionSlots.tryAcquire()) {
                try {
                    PrintWriter busy = new PrintWriter(new OutputStreamWriter(
                            client.getOutputStream(), StandardCharsets.UTF_8), true);
                    busy.println("CilExec terminal is busy (128 connections); "
                            + "disconnect an existing session and retry");
                    busy.flush();
                } catch (IOException ignored) {
                    // The client is gone anyway; closing without a message is fine.
                }
                closeClient(client);
                continue;
            }
            try {
                client.setTcpNoDelay(true);
                client.setSoTimeout(TERMINAL_IDLE_TIMEOUT_MILLIS);
            } catch (IOException failure) {
                connectionSlots.release();
                closeClient(client);
                continue;
            }
            clients.add(client);
            try {
                Thread.ofVirtual().name("cilexec-terminal-session").start(
                        () -> serve(client));
            } catch (RuntimeException failure) {
                clients.remove(client);
                connectionSlots.release();
                closeClient(client);
                LOG.warn("Terminal session thread could not be started; connection closed",
                        failure);
            }
        }
    }

    private void serve(Socket client) {
        Thread session = Thread.currentThread();
        sessionThreads.add(session);
        PrintWriter output = null;
        try (client) {
            output = new LockedPrintWriter(new OutputStreamWriter(client.getOutputStream(),
                    StandardCharsets.UTF_8));
            PushbackInputStream connection = new PushbackInputStream(client.getInputStream(), 128);
            ConnectionMode mode = readConnectionMode(connection);
            if (mode == ConnectionMode.CLOSED) return;
            if (mode == ConnectionMode.HEADLESS) {
                int status;
                try {
                    status = serveHeadless(connection, output);
                } catch (IOException failure) {
                    output.println("error: " + failure.getMessage());
                    status = 74;
                }
                output.print("\0R " + status + "\n");
                output.flush();
                return;
            }
            DimensionInputStream transported = new DimensionInputStream(connection,
                    idleDisconnectNanos);
            PrintWriter sessionOutput = output;
            transported.onIdleWarning(() -> {
                sessionOutput.println("idle disconnect in 1 minute; press any key to stay "
                        + "connected (CILEXEC_TERMINAL_IDLE_MINUTES configures the "
                        + "timeout)");
                sessionOutput.flush();
            });
            transported.onDisconnect(session::interrupt);
            TerminalInput input = TerminalInput.remoteRaw(transported, transported::width);
            new TerminalAccessConsole(input, output, access, account -> {
                        TerminalControl control = controls.apply(account);
                        transported.bind(account.userId(), control::interruptForeground);
                        control.outputRouteId().ifPresent(
                                routeId -> TerminalOutputRouter.attach(routeId, sessionOutput));
                        return control;
                    },
                    administratorUsername).run();
        } catch (IOException ignored) {
            // Disconnecting a host terminal ends only this authenticated connection.
        } finally {
            sessionThreads.remove(Thread.currentThread());
            clients.remove(client);
            connectionSlots.release();
            if (output != null) TerminalOutputRouter.detachAll(output);
        }
    }

    private int serveHeadless(InputStream input, PrintWriter output) throws IOException {
        byte[] contextBytes = readField(input, 128, "context");
        byte[] usernameBytes = readField(input, 128, "username");
        byte[] passwordBytes = readField(input, 4_096, "password");
        byte[] sourceBytes = readField(input, 4 * 1024 * 1024, "source");
        char[] password = null;
        try {
            password = decodePassword(passwordBytes);
            String context = new String(contextBytes, StandardCharsets.UTF_8);
            String username = new String(usernameBytes, StandardCharsets.UTF_8);
            String source = new String(sourceBytes, StandardCharsets.UTF_8);
            java.util.Optional<UserAccount> authenticated = access.login(username, password);
            if (authenticated.isEmpty()) {
                output.println("error: invalid username or password");
                return 77;
            }
            TerminalControl control = headlessControls.apply(authenticated.orElseThrow(), context);
            control.outputRouteId().ifPresent(route -> TerminalOutputRouter.attach(route, output));
            String result = source.isBlank() ? "" : control.evaluate(source);
            if (result != null && !result.isEmpty()) output.println(result);
            return result != null && result.startsWith("error") ? 1 : 0;
        } catch (RuntimeException failure) {
            LOG.warn("Headless terminal submission failed", failure);
            output.println("error: " + describe(failure));
            return 1;
        } finally {
            if (password != null) java.util.Arrays.fill(password, '\0');
            java.util.Arrays.fill(passwordBytes, (byte) 0);
            java.util.Arrays.fill(contextBytes, (byte) 0);
            java.util.Arrays.fill(usernameBytes, (byte) 0);
            java.util.Arrays.fill(sourceBytes, (byte) 0);
            TerminalOutputRouter.detachAll(output);
        }
    }

    /** Decodes without constructing an immutable String containing the credential. */
    private static char[] decodePassword(byte[] encoded) throws IOException {
        char[] workspace = new char[encoded.length];
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer output = CharBuffer.wrap(workspace);
            var decoded = decoder.decode(ByteBuffer.wrap(encoded), output, true);
            if (decoded.isError()) decoded.throwException();
            var flushed = decoder.flush(output);
            if (flushed.isError()) flushed.throwException();
            return java.util.Arrays.copyOf(workspace, output.position());
        } catch (CharacterCodingException invalid) {
            throw new IOException("Headless password is not valid UTF-8", invalid);
        } finally {
            java.util.Arrays.fill(workspace, '\0');
        }
    }

    private static ConnectionMode readConnectionMode(PushbackInputStream input)
            throws IOException {
        int first = input.read();
        if (first < 0) return ConnectionMode.CLOSED;
        if (first != 0) {
            input.unread(first);
            return ConnectionMode.INTERACTIVE;
        }
        byte[] frame = new byte[96];
        frame[0] = 0;
        int length = 1;
        while (length < frame.length) {
            int value = input.read();
            if (value < 0) return ConnectionMode.CLOSED;
            frame[length++] = (byte) value;
            if (value == '\n') break;
        }
        String payload = new String(frame, 1, length - 1, StandardCharsets.US_ASCII).trim();
        if (payload.equals("M HEADLESS")) return ConnectionMode.HEADLESS;
        // New terminal clients identify the transport explicitly. Consume that marker so its
        // NUL-prefixed bytes can never leak into the access prompt as user input.
        if (payload.equals("M INTERACTIVE")) return ConnectionMode.INTERACTIVE;
        input.unread(frame, 0, length);
        return ConnectionMode.INTERACTIVE;
    }

    private static byte[] readField(InputStream input, int maximum, String name)
            throws IOException {
        StringBuilder length = new StringBuilder();
        while (length.length() <= 10) {
            int value = input.read();
            if (value < 0) throw new IOException("Incomplete headless " + name + " length");
            if (value == '\n') break;
            if (value < '0' || value > '9') {
                throw new IOException("Invalid headless " + name + " length");
            }
            length.append((char) value);
        }
        if (length.isEmpty() || length.length() > 10) {
            throw new IOException("Invalid headless " + name + " length");
        }
        int count;
        try {
            count = Integer.parseInt(length.toString());
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid headless " + name + " length", invalid);
        }
        if (count < 0 || count > maximum) {
            throw new IOException("Headless " + name + " exceeds " + maximum + " bytes");
        }
        byte[] value = input.readNBytes(count);
        if (value.length != count) throw new IOException("Incomplete headless " + name);
        return value;
    }

    private static String describe(RuntimeException failure) {
        // Language-level errors carry an actionable message for the FCL author; internal
        // failures stay opaque so implementation details never leak to the terminal.
        if (failure instanceof com.follarce.fcl.FclRuntimeException
                || failure instanceof com.follarce.fcl.FclCompileException) {
            String message = failure.getMessage();
            return message == null || message.isBlank()
                    ? failure.getClass().getSimpleName() : message;
        }
        return "Command failed: " + failure.getClass().getSimpleName();
    }

    private enum ConnectionMode { INTERACTIVE, HEADLESS, CLOSED }

    /** Keeps asynchronous FCL output atomic with prompts written by the terminal session. */
    private static final class LockedPrintWriter extends PrintWriter {
        private LockedPrintWriter(Writer writer) {
            super(writer, true);
        }

        @Override public synchronized void write(int value) {
            super.write(value);
        }

        @Override public synchronized void write(char[] value, int offset, int length) {
            super.write(value, offset, length);
        }

        @Override public synchronized void write(String value, int offset, int length) {
            super.write(value, offset, length);
        }

        @Override public synchronized void flush() {
            super.flush();
        }
    }

    @Override
    public synchronized void close() {
        if (!running.getAndSet(false)) return;
        closeServer();
        sessionThreads.forEach(Thread::interrupt);
        clients.forEach(this::closeClient);
        clients.clear();
        if (acceptor != null) {
            acceptor.interrupt();
            try {
                acceptor.join(java.time.Duration.ofSeconds(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            acceptor = null;
        }
    }

    private void closeServer() {
        if (server == null) return;
        try {
            server.close();
        } catch (IOException ignored) {
        } finally {
            server = null;
        }
    }

    private void closeClient(Socket client) {
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }

    /** Receives control frames independently, so Ctrl-C works while FCL is executing. */
    private static final class DimensionInputStream extends FilterInputStream {
        private static final int END_OF_STREAM = -1;
        private volatile java.util.UUID ownerId;
        private volatile TerminalDimensions.Size size = new TerminalDimensions.Size(80, 24);
        private volatile BooleanSupplier interrupt = () -> false;
        private final AtomicBoolean receivedAnyByte = new AtomicBoolean();
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final AtomicBoolean idleWarningSent = new AtomicBoolean();
        private final long idleDisconnectNanos;
        private final java.util.concurrent.atomic.AtomicLong lastActivityNanos =
                new java.util.concurrent.atomic.AtomicLong();
        private volatile Runnable disconnectListener = () -> { };
        private volatile Runnable idleWarning = () -> { };
        private final ArrayBlockingQueue<Integer> input =
                new ArrayBlockingQueue<>(MAX_BUFFERED_INPUT_BYTES);

        private DimensionInputStream(InputStream input, long idleDisconnectNanos) {
            super(input);
            this.idleDisconnectNanos = idleDisconnectNanos;
            Thread.ofVirtual().name("cilexec-terminal-input").start(this::pump);
        }

        private void bind(java.util.UUID ownerId, BooleanSupplier interrupt) {
            this.ownerId = java.util.Objects.requireNonNull(ownerId, "ownerId");
            this.interrupt = java.util.Objects.requireNonNull(interrupt, "interrupt");
            TerminalDimensions.update(ownerId, size);
        }

        /** Wakes the owning session thread once the socket has ended, so a blocked await()
         *  polling loop cannot keep its connection slot and transaction rate alive. */
        void onDisconnect(Runnable listener) {
            disconnectListener = java.util.Objects.requireNonNull(listener, "listener");
        }

        /** Warns the user shortly before an idle disconnect, so the session is not dropped silently. */
        void onIdleWarning(Runnable warning) {
            idleWarning = java.util.Objects.requireNonNull(warning, "warning");
        }

        private int width() {
            return size.width();
        }

        @Override
        public int read() throws IOException {
            while (true) {
                try {
                    int value = input.take();
                    if (value == END_OF_STREAM && !input.offer(END_OF_STREAM)) {
                        throw new IOException("Terminal end-of-stream marker was lost");
                    }
                    return value;
                } catch (InterruptedException interrupted) {
                    if (!disconnected.get()) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Terminal input interrupted", interrupted);
                    }
                    // A disconnect wake-up interrupted this read. The end-of-stream marker
                    // queued by the pump is the authoritative signal, so clear the interrupt
                    // flag and keep consuming buffered input instead of aborting mid-line.
                    Thread.interrupted();
                }
            }
        }

        private void pump() {
            try {
                while (true) {
                    int value;
                    try {
                        value = in.read();
                    } catch (SocketTimeoutException idle) {
                        // Never-disconnecting idle sessions must still yield their slot: a
                        // local process that sent a single byte could otherwise pin a
                        // connection forever. Bytes keep the session alive; total inactivity
                        // beyond the disconnect threshold closes it, with a warning sent one
                        // minute before the drop so an idle session is never cut silently.
                        long idleNanos = System.nanoTime() - lastActivityNanos.get();
                        if (!receivedAnyByte.get() || idleNanos > idleDisconnectNanos) {
                            break;
                        }
                        if (idleWarningSent.compareAndSet(false, true)
                                && idleNanos > idleDisconnectNanos - IDLE_WARN_LEAD_NANOS) {
                            idleWarning.run();
                        }
                        continue;
                    }
                    if (value < 0) break;
                    receivedAnyByte.set(true);
                    lastActivityNanos.set(System.nanoTime());
                    if (value == 0) readFrame();
                    else input.put(value);
                }
            } catch (IOException ignored) {
                // Socket closure ends the terminal input stream.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                disconnected.set(true);
                try {
                    input.put(END_OF_STREAM);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                Runnable listener = disconnectListener;
                if (listener != null) {
                    try {
                        listener.run();
                    } catch (RuntimeException ignored) {
                        // The session thread may already be gone; the disconnect is final.
                    }
                }
            }
        }

        private void readFrame() throws IOException, InterruptedException {
            StringBuilder frame = new StringBuilder();
            for (int count = 0; count < 64; count++) {
                int value = in.read();
                if (value < 0 || value == '\n') break;
                frame.append((char) value);
            }
            String[] fields = frame.toString().trim().split("\\s+");
            if (fields.length == 1 && fields[0].equals("I")) {
                try {
                    interrupt.getAsBoolean();
                } catch (RuntimeException ignored) {
                    // A transient control-plane failure must not kill the only socket reader.
                }
                // Always wake whichever console read is active. If FCL was running, byte 3 is
                // only an in-band acknowledgement of the already-persisted cancellation; if no
                // process was running it cancels the editable prompt.
                input.put(3);
                return;
            }
            if (fields.length != 3 || !fields[0].equals("S")) return;
            try {
                int height = Integer.parseInt(fields[1]);
                int width = Integer.parseInt(fields[2]);
                if (height > 4_096 || width > 4_096) return;
                TerminalDimensions.Size replacement = new TerminalDimensions.Size(width, height);
                size = replacement;
                java.util.UUID bound = ownerId;
                if (bound != null) TerminalDimensions.update(bound, replacement);
            } catch (IllegalArgumentException ignored) {
                // Malformed resize frames are ignored without exposing them to FCL input.
            }
        }

        @Override
        public void close() throws IOException {
            super.close();
            input.clear();
            if (!input.offer(END_OF_STREAM)) {
                throw new IllegalStateException("Cannot close terminal input queue");
            }
        }
    }
}
