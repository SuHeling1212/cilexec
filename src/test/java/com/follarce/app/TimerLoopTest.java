package com.follarce.app;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimerLoopTest {
    @Test
    void firesDueTimersAndStopsCleanly() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        TimerLoop loop = new TimerLoop(() -> {
            calls.incrementAndGet();
            fired.countDown();
            return 1;
        }, Duration.ofSeconds(30), failure -> { });

        loop.start();
        assertTrue(fired.await(1, TimeUnit.SECONDS));
        loop.close();

        assertEquals(1, calls.get());
        assertFalse(loop.isRunning());
    }

    @Test
    void reportsAnyTimerFailureAsFatal() throws Exception {
        IllegalStateException failure = new IllegalStateException("database fenced");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch fatal = new CountDownLatch(1);
        TimerLoop loop = new TimerLoop(() -> {
            throw failure;
        }, Duration.ofSeconds(1), actual -> {
            reported.set(actual);
            fatal.countDown();
        });

        loop.start();
        assertTrue(fatal.await(1, TimeUnit.SECONDS));
        loop.close();

        assertSame(failure, reported.get());
        assertFalse(loop.isRunning());
    }
}
