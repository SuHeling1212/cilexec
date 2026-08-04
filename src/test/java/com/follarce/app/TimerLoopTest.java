package com.follarce.app;

import com.follarce.persistence.postgres.error.PersistenceFailure;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void reportsErrorsAndDatabaseOutagesAsFatal() throws Exception {
        Throwable[] fatalFailures = {
                new AssertionError("jvm broken"),
                new PersistenceFailure(PersistenceFailure.Kind.DATABASE_UNAVAILABLE, true,
                        "database unreachable", new Exception())
        };
        for (Throwable fatal : fatalFailures) {
            AtomicReference<Throwable> reported = new AtomicReference<>();
            CountDownLatch fatalReported = new CountDownLatch(1);
            TimerLoop loop = new TimerLoop(() -> {
                throwUnchecked(fatal);
                return 0;
            }, () -> Optional.empty(), actual -> {
                reported.set(actual);
                fatalReported.countDown();
            });

            loop.start();
            assertTrue(fatalReported.await(1, TimeUnit.SECONDS));
            loop.close();

            assertSame(fatal, reported.get());
            assertFalse(loop.isRunning());
        }
    }

    @Test
    void transientContentionIsRetriedWithoutFencingTheRuntime() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch thirdAttempt = new CountDownLatch(3);
        TimerLoop loop = new TimerLoop(() -> {
            attempts.incrementAndGet();
            thirdAttempt.countDown();
            throw new IllegalStateException("optimistic-lock CAS lost");
        }, () -> Optional.empty(), reported::set);

        loop.start();
        assertTrue(thirdAttempt.await(1, TimeUnit.SECONDS));
        loop.close();

        assertTrue(attempts.get() >= 3, "transient failures must be retried");
        assertNull(reported.get());
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

    @Test
    void sleepsBrieflyWhenTheDeadlineIsAlreadyPastAndNoWorkIsAvailable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        TimerLoop loop = new TimerLoop(() -> {
            calls.incrementAndGet();
            started.countDown();
            return 0;
        }, () -> Optional.of(Instant.now().minusSeconds(1)), failure -> { });

        loop.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(300);
        loop.close();

        assertTrue(calls.get() < 20, "loop must not spin on an empty past deadline: "
                + calls.get());
    }

    @Test
    void runsCleanupInTheSameMaintenanceBatch() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        AtomicInteger cleaned = new AtomicInteger();
        CountDownLatch batch = new CountDownLatch(2);
        TimerLoop loop = new TimerLoop(() -> {
            fired.incrementAndGet();
            batch.countDown();
            return 1;
        }, () -> {
            cleaned.incrementAndGet();
            batch.countDown();
            return 2;
        }, () -> Optional.empty(), failure -> { });

        loop.start();
        assertTrue(batch.await(1, TimeUnit.SECONDS));
        loop.close();

        assertEquals(1, fired.get());
        assertEquals(1, cleaned.get());
        assertFalse(loop.isRunning());
    }

    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
