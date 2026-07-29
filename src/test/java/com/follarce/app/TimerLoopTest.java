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
        }, () -> java.util.Optional.empty(), failure -> { });

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
        }, () -> java.util.Optional.empty(), actual -> {
            reported.set(actual);
            fatal.countDown();
        });

        loop.start();
        assertTrue(fatal.await(1, TimeUnit.SECONDS));
        loop.close();

        assertSame(failure, reported.get());
        assertFalse(loop.isRunning());
    }

    @Test
    void staysBlockedWithoutADeadlineUntilExplicitlyWoken() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstCall = new CountDownLatch(1);
        CountDownLatch secondCall = new CountDownLatch(2);
        TimerLoop loop = new TimerLoop(() -> {
            calls.incrementAndGet();
            firstCall.countDown();
            secondCall.countDown();
            return 0;
        }, java.util.Optional::empty, failure -> { });

        loop.start();
        assertTrue(firstCall.await(1, TimeUnit.SECONDS));
        assertFalse(secondCall.await(150, TimeUnit.MILLISECONDS));
        assertEquals(1, calls.get());

        loop.wake();
        assertTrue(secondCall.await(1, TimeUnit.SECONDS));
        loop.close();
        assertEquals(2, calls.get());
    }
}
