package com.follarce.terminal;

import java.io.PrintWriter;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Delivers durable FCL output only to the terminal session that submitted the process.
 *
 * <p>Writes are asynchronous and bounded: publishing never blocks an effect worker on a
 * stalled client. When the socket write stalls, the dedicated writer thread waits while
 * later publishes fail fast after a short offer timeout, so the effect fails visibly
 * instead of freezing the whole worker pool.
 */
public final class TerminalOutputRouter {
    private static final ConcurrentHashMap<UUID, Set<SessionOutput>> OUTPUTS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PrintWriter, SessionOutput> SESSIONS =
            new ConcurrentHashMap<>();
    private static final int CAPACITY = 4096;
    private static final long OFFER_TIMEOUT_MILLIS = 250;

    private TerminalOutputRouter() {}

    public static void attach(UUID routeId, PrintWriter output) {
        SessionOutput session = SESSIONS.computeIfAbsent(output, SessionOutput::new);
        OUTPUTS.computeIfAbsent(routeId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public static void detachAll(PrintWriter output) {
        SessionOutput session = SESSIONS.remove(output);
        if (session != null) session.close();
        OUTPUTS.forEach((ownerId, ignored) -> OUTPUTS.computeIfPresent(ownerId,
                (unused, outputs) -> {
                    outputs.remove(session);
                    return outputs.isEmpty() ? null : outputs;
                }));
        TerminalOutputTracker.discard(output);
    }

    /** Returns whether at least one terminal is currently attached to the route. */
    public static boolean attached(UUID routeId) {
        Set<SessionOutput> outputs = OUTPUTS.get(routeId);
        return outputs != null && !outputs.isEmpty();
    }

    /** Returns whether at least one authenticated terminal accepted the output. */
    public static boolean publish(UUID routeId, String text, boolean newline) {
        Set<SessionOutput> outputs = OUTPUTS.get(routeId);
        if (outputs == null || outputs.isEmpty()) return false;
        boolean delivered = false;
        for (SessionOutput output : outputs) {
            if (output.offer(text, newline)) delivered = true;
        }
        return delivered;
    }

    private record Entry(String text, boolean newline) {}

    /** Bounded asynchronous writer; a stalled socket stalls only this virtual thread. */
    private static final class SessionOutput implements AutoCloseable {
        private final PrintWriter output;
        private final ArrayBlockingQueue<Entry> queue = new ArrayBlockingQueue<>(CAPACITY);
        private final Thread writer;
        private volatile boolean closed;

        private SessionOutput(PrintWriter output) {
            this.output = output;
            this.writer = Thread.ofVirtual().name("cilexec-terminal-writer")
                    .start(this::writeLoop);
        }

        private boolean offer(String text, boolean newline) {
            if (closed) return false;
            try {
                return queue.offer(new Entry(text, newline), OFFER_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void writeLoop() {
            while (true) {
                Entry entry = queue.poll();
                if (entry == null) {
                    if (closed && queue.isEmpty()) return;
                    try {
                        entry = queue.take();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                synchronized (output) {
                    if (entry.newline()) output.println(entry.text());
                    else output.print(entry.text());
                    output.flush();
                    TerminalOutputTracker.printed(output, entry.text(), entry.newline());
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            writer.interrupt();
            try {
                writer.join(TimeUnit.SECONDS.toMillis(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
