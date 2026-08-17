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
import java.util.function.LongSupplier;
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
    private final BiFunction<UserAccount, String, TerminalControl> interactiveControls;
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
        this(port, access, (account, ignored) -> controls.apply(account),
                (account, ignored) -> controls.apply(account),
                administratorUsername, TerminalSettings.DEFAULT_IDLE_DISCONNECT);
    }

    public TerminalServer(int port, TerminalAccess access,
                           Function<UserAccount, TerminalControl> controls,
                           BiFunction<UserAccount, String, TerminalControl> headlessControls,
                           String administratorUsername) {
        this(port, access, (account, ignored) -> controls.apply(account), headlessControls,
                administratorUsername,
                TerminalSettings.DEFAULT_IDLE_DISCONNECT);
    }

    public TerminalServer(int port, TerminalAccess access,
                           BiFunction<UserAccount, String, TerminalControl> interactiveControls,
                           BiFunction<UserAccount, String, TerminalControl> headlessControls,
                           String administratorUsername) {
        this(port, access, interactiveControls, headlessControls, administratorUsername,
                TerminalSettings.DEFAULT_IDLE_DISCONNECT);
    }

    public TerminalServer(int port, TerminalAccess access,
                           BiFunction<UserAccount, String, TerminalControl> interactiveControls,
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
        this.interactiveControls = java.util.Objects.requireNonNull(interactiveControls,
                "interactiveControls");
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
        sessionThreads.add(Thread.currentThread());
        PrintWriter output = null;
        try (client) {
            output = new LockedPrintWriter(new OutputStreamWriter(client.getOutputStream(),
                    StandardCharsets.UTF_8));
            PushbackInputStream connection = new PushbackInputStream(client.getInputStream(), 128);
            ConnectionHandshake handshake = readConnectionMode(connection);
            if (handshake.mode() == ConnectionMode.CLOSED) return;
            if (handshake.mode() == ConnectionMode.HEADLESS) {
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
            java.util.concurrent.atomic.AtomicReference<TerminalControl> attached =
                    new java.util.concurrent.atomic.AtomicReference<>();
            transported.onIdleWarning(() -> {
                sessionOutput.println("session idle; this terminal will close in 1 minute if "
                        + "the attached process stays suspended (input or activity resets the "
                        + "timer)");
                sessionOutput.flush();
            });
            transported.onIdleCheck(() -> {
                TerminalControl control = attached.get();
                return control == null ? Long.MAX_VALUE
                        : control.idleRemainingNanos(idleDisconnectNanos);
            });
            transported.onDisconnect(transported::interruptForeground);
            TerminalInput input = TerminalInput.remoteRaw(transported, transported::width);
            new TerminalAccessConsole(input, output, access, account -> {
                        TerminalControl control = interactiveControls.apply(account,
                                handshake.interactiveContext());
                        attached.set(control);
                        transported.bind(account.userId(), control::interruptForeground);
                        control.outputRouteId().ifPresent(
                                routeId -> TerminalOutputRouter.attach(routeId, sessionOutput));
                        String restore = control.terminalRestoreSequence();
                        if (!restore.isEmpty()) {
                            sessionOutput.print(restore);
                            sessionOutput.flush();
                        }
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

    private static ConnectionHandshake readConnectionMode(PushbackInputStream input)
            throws IOException {
        int first = input.read();
        if (first < 0) return new ConnectionHandshake(ConnectionMode.CLOSED, "");
        if (first != 0) {
            input.unread(first);
            return new ConnectionHandshake(ConnectionMode.INTERACTIVE, "");
        }
        byte[] frame = new byte[96];
        frame[0] = 0;
        int length = 1;
        while (length < frame.length) {
            int value = input.read();
            if (value < 0) return new ConnectionHandshake(ConnectionMode.CLOSED, "");
            frame[length++] = (byte) value;
            if (value == '\n') break;
        }
        String payload = new String(frame, 1, length - 1, StandardCharsets.US_ASCII).trim();
        if (payload.equals("M HEADLESS")) return new ConnectionHandshake(ConnectionMode.HEADLESS, "");
        // New terminal clients identify the transport explicitly. Consume that marker so its
        // NUL-prefixed bytes can never leak into the access prompt as user input.
        if (payload.equals("M INTERACTIVE")) {
            return new ConnectionHandshake(ConnectionMode.INTERACTIVE, "");
        }
        String prefix = "M INTERACTIVE ";
        if (payload.startsWith(prefix)) {
            String context = payload.substring(prefix.length());
            if (!context.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new IOException("Invalid interactive terminal context");
            }
            return new ConnectionHandshake(ConnectionMode.INTERACTIVE, context);
        }
        input.unread(frame, 0, length);
        return new ConnectionHandshake(ConnectionMode.INTERACTIVE, "");
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

    private record ConnectionHandshake(ConnectionMode mode, String interactiveContext) { }

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
    static final class DimensionInputStream extends FilterInputStream {
        private static final int END_OF_STREAM = -1;
        private volatile java.util.UUID ownerId;
        private volatile TerminalDimensions.Size size = new TerminalDimensions.Size(80, 24);
        private volatile BooleanSupplier interrupt = () -> false;
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final long idleDisconnectNanos;
        private volatile LongSupplier idleCheck = () -> Long.MAX_VALUE;
        private volatile Runnable disconnectListener = () -> { };
        private volatile Runnable idleWarning = () -> { };
        private final ArrayBlockingQueue<Integer> input =
                new ArrayBlockingQueue<>(MAX_BUFFERED_INPUT_BYTES);

        DimensionInputStream(InputStream input, long idleDisconnectNanos) {
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

        /** Returns nanos until the suspended-process threshold, 0 to close, MAX for active. */
        void onIdleCheck(LongSupplier check) {
            idleCheck = java.util.Objects.requireNonNull(check, "check");
        }

        private int width() {
            return size.width();
        }

        private void interruptForeground() {
            if (ownerId == null) return;
            try {
                interrupt.getAsBoolean();
            } catch (RuntimeException ignored) {
                // Disconnect cleanup must not prevent the queued end-of-stream from being read.
            }
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

        /**
         * Reports buffered bytes actually available to {@code read()}. The default
         * FilterInputStream implementation delegates to the raw socket, which the pump
         * thread drains as fast as it can; by the time an escape-sequence parser checks
         * {@code available()}, the socket may already be empty even though the queue still
         * holds the sequence's continuation bytes. That made CSI sequences such as
         * {@code ESC [ A} split into a standalone ESCAPE key plus literal {@code [} and
         * {@code A} text events whenever the pump won the race.
         */
        @Override
        public int available() {
            return input.size();
        }

        private void pump() {
            boolean warnedRecently = false;
            try {
                while (true) {
                    int value;
                    try {
                        value = in.read();
                    } catch (SocketTimeoutException idle) {
                        // The socket timeout is only a periodic wake-up for the suspension
                        // check. A session closes for idleness only when its attached process
                        // has been suspended (PAUSED) for the configured threshold; active
                        // processes and full-screen programs waiting on input never close.
                        long remaining;
                        try {
                            remaining = idleCheck.getAsLong();
                        } catch (RuntimeException failure) {
                            remaining = Long.MAX_VALUE;
                        }
                        if (remaining <= 0) break;
                        if (remaining <= IDLE_WARN_LEAD_NANOS) {
                            if (!warnedRecently) {
                                warnedRecently = true;
                                idleWarning.run();
                            }
                        } else {
                            warnedRecently = false;
                        }
                        continue;
                    }
                    if (value < 0) break;
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
