package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalOutputRouterTest {
    @Test
    void publishesAsynchronouslyToTheAttachedSession() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8));
        UUID route = UUID.randomUUID();
        TerminalOutputRouter.attach(route, output);
        try {
            assertTrue(TerminalOutputRouter.publish(route, "hello", false));
            assertTrue(TerminalOutputRouter.publish(route, " world", true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            String written = "";
            while (System.nanoTime() < deadline) {
                written = buffer.toString(StandardCharsets.UTF_8);
                if (written.contains("hello world")) break;
                Thread.sleep(5);
            }
            assertEquals("hello world\n", written);
        } finally {
            TerminalOutputRouter.detachAll(output);
        }
    }

    @Test
    void publishFailsFastWhenTheWriterIsStalled() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        PrintWriter output = new BlockingPrintWriter(blocked);
        UUID route = UUID.randomUUID();
        TerminalOutputRouter.attach(route, output);
        try {
            assertTrue(TerminalOutputRouter.publish(route, "first", true));
            assertTrue(blocked.await(1, TimeUnit.SECONDS));
            int pushed = 0;
            for (int index = 0; index < 4200; index++) {
                if (!TerminalOutputRouter.publish(route, "x", true)) break;
                pushed++;
            }
            assertTrue(pushed >= 4096, "bounded queue must accept its capacity: " + pushed);
            long start = System.nanoTime();
            boolean delivered = TerminalOutputRouter.publish(route, "overflow", true);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertFalse(delivered, "a full queue must fail fast");
            assertTrue(elapsed < 2000, "publish must not block on a stalled writer: " + elapsed);
        } finally {
            TerminalOutputRouter.detachAll(output);
        }
    }

    @Test
    void publishStopsReportingDeliveryAfterTheSocketBreaks() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Socket client = new Socket("127.0.0.1", server.getLocalPort());
            Socket accepted = server.accept();
            PrintWriter output = new PrintWriter(accepted.getOutputStream(), true);
            UUID route = UUID.randomUUID();
            TerminalOutputRouter.attach(route, output);
            try {
                assertTrue(TerminalOutputRouter.publish(route, "first", true));
                // The peer disappears; the writer's next flush must surface checkError()
                // and stop reporting delivery instead of silently succeeding.
                client.close();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (TerminalOutputRouter.publish(route, "retry", true)
                        && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertFalse(TerminalOutputRouter.publish(route, "after-break", true),
                        "publish must report failure once the writer detects the broken socket");
            } finally {
                TerminalOutputRouter.detachAll(output);
                accepted.close();
                if (!client.isClosed()) {
                    client.close();
                }
            }
        }
    }

    @Test
    void publishReturnsFalseWithoutAnAttachedSession() {
        assertFalse(TerminalOutputRouter.publish(UUID.randomUUID(), "x", true));
    }

    @Test
    void detachStopsDelivery() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8));
        UUID route = UUID.randomUUID();
        TerminalOutputRouter.attach(route, output);
        TerminalOutputRouter.detachAll(output);
        assertFalse(TerminalOutputRouter.publish(route, "x", true));
    }

    /** A writer whose first flush blocks, simulating a client that stopped reading. */
    private static final class BlockingPrintWriter extends PrintWriter {
        private final CountDownLatch blocked;
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean firstFlush = true;

        private BlockingPrintWriter(CountDownLatch blocked) {
            super(new OutputStream() {
                @Override public void write(int value) { }
            }, true);
            this.blocked = blocked;
        }

        @Override
        public void flush() {
            if (firstFlush) {
                firstFlush = false;
                blocked.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            super.flush();
        }
    }
}
