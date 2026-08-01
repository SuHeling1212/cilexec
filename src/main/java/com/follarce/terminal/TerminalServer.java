package com.follarce.terminal;

import com.follarce.domain.auth.UserAccount;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Lightweight multi-user terminal transport owned by the one active Runtime JVM.
 * Connections authenticate independently; no connection owns or stops the Runtime.
 */
public final class TerminalServer implements AutoCloseable {
    private static final int MAX_CONNECTIONS = 128;
    private static final int MAX_BUFFERED_INPUT_BYTES = 64 * 1024;
    private final int port;
    private final TerminalAccess access;
    private final Function<UserAccount, TerminalControl> controls;
    private final String administratorUsername;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Semaphore connectionSlots = new Semaphore(MAX_CONNECTIONS);
    private volatile ServerSocket server;
    private volatile Thread acceptor;

    public TerminalServer(int port, TerminalAccess access,
                          Function<UserAccount, TerminalControl> controls,
                          String administratorUsername) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Terminal port is outside 1..65535");
        }
        this.port = port;
        this.access = java.util.Objects.requireNonNull(access, "access");
        this.controls = java.util.Objects.requireNonNull(controls, "controls");
        this.administratorUsername = java.util.Objects.requireNonNull(
                administratorUsername, "administratorUsername");
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
        try {
            while (running.get()) {
                Socket client = server.accept();
                if (!connectionSlots.tryAcquire()) {
                    client.close();
                    continue;
                }
                client.setTcpNoDelay(true);
                clients.add(client);
                try {
                    Thread.ofVirtual().name("cilexec-terminal-session").start(
                            () -> serve(client));
                } catch (RuntimeException failure) {
                    clients.remove(client);
                    connectionSlots.release();
                    client.close();
                    throw failure;
                }
            }
        } catch (IOException failure) {
            if (running.get()) running.set(false);
        }
    }

    private void serve(Socket client) {
        PrintWriter output = null;
        try (client) {
            output = new LockedPrintWriter(new OutputStreamWriter(client.getOutputStream(),
                    StandardCharsets.UTF_8));
            DimensionInputStream transported = new DimensionInputStream(client.getInputStream());
            TerminalInput input = TerminalInput.remoteRaw(transported, transported::width);
            PrintWriter sessionOutput = output;
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
            clients.remove(client);
            connectionSlots.release();
            if (output != null) TerminalOutputRouter.detachAll(output);
        }
    }

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
        private final ArrayBlockingQueue<Integer> input =
                new ArrayBlockingQueue<>(MAX_BUFFERED_INPUT_BYTES);

        private DimensionInputStream(InputStream input) {
            super(input);
            Thread.ofVirtual().name("cilexec-terminal-input").start(this::pump);
        }

        private void bind(java.util.UUID ownerId, BooleanSupplier interrupt) {
            this.ownerId = java.util.Objects.requireNonNull(ownerId, "ownerId");
            this.interrupt = java.util.Objects.requireNonNull(interrupt, "interrupt");
            TerminalDimensions.update(ownerId, size);
        }

        private int width() {
            return size.width();
        }

        @Override
        public int read() throws IOException {
            try {
                int value = input.take();
                if (value == END_OF_STREAM && !input.offer(END_OF_STREAM)) {
                    throw new IOException("Terminal end-of-stream marker was lost");
                }
                return value;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Terminal input interrupted", interrupted);
            }
        }

        private void pump() {
            try {
                while (true) {
                    int value = in.read();
                    if (value < 0) break;
                    if (value == 0) readFrame();
                    else input.put(value);
                }
            } catch (IOException ignored) {
                // Socket closure ends the terminal input stream.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    input.put(END_OF_STREAM);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
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
