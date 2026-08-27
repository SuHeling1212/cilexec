package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalServerInputPumpTest {
    @Test
    void resizeFramesWakeRawKeyModeOnceAndAreCoalesced() throws Exception {
        java.io.PipedInputStream socket = new java.io.PipedInputStream();
        java.io.PipedOutputStream writer = new java.io.PipedOutputStream(socket);
        TerminalServer.DimensionInputStream input = new TerminalServer.DimensionInputStream(
                socket, Duration.ofMinutes(5).toNanos());
        try {
            input.beginKeyMode();
            writer.write(new byte[]{0, 'S', ' ', '4', '0', ' ', '1', '2', '0', '\n'});
            writer.write(new byte[]{0, 'S', ' ', '4', '1', ' ', '1', '2', '1', '\n'});
            writer.write('x');
            writer.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (input.available() < 2 && System.nanoTime() < deadline) Thread.yield();

            assertTrue(input.available() >= 2);
            assertEquals(0, input.read());
            assertEquals('x', input.read());
            assertEquals(0, input.available(), "pending resize frames must collapse to one event");
        } finally {
            input.close();
            writer.close();
            socket.close();
        }
    }

    @Test
    void closingAConnectionWithAFullInputQueueStopsThePumpThread() throws Exception {
        // The source never ends and never blocks, so the pump fills the 64 KiB queue
        // and then parks on ArrayBlockingQueue.put() — exactly the leaked-thread state
        // from the bug report. Closing the transport must interrupt that thread.
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 'x';
            }
        };
        TerminalServer.DimensionInputStream input =
                new TerminalServer.DimensionInputStream(endless,
                        Duration.ofMinutes(5).toNanos());
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (input.available() < 64 * 1024 && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(64 * 1024, input.available(),
                    "the input queue must be full before close() is exercised");

            Thread pump = input.pumpThread();
            assertNotNull(pump, "the pump thread must be tracked");
            input.close();
            pump.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(pump.isAlive(),
                    "the pump thread must terminate after close() with a full queue");
        } finally {
            input.close();
        }
    }
}
