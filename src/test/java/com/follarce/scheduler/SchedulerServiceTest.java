package com.follarce.scheduler;

import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.SchedulerRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.port.TransactionWork;
import com.follarce.domain.scheduler.SchedulerClaim;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerServiceTest {
    @Test
    void reconciliationClaimsDurableWorkWhenEveryWakeHintIsLost() throws Exception {
        UUID processUid = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        AtomicBoolean ready = new AtomicBoolean();
        AtomicBoolean claimed = new AtomicBoolean();
        CountDownLatch initialEmptyRead = new CountDownLatch(1);
        CountDownLatch executed = new CountDownLatch(1);
        SchedulerRepository scheduler = new SchedulerRepository() {
            @Override public void enqueue(SchedulerQueueEntry entry) { }

            @Override public Optional<SchedulerClaim> claimNext(
                    UUID runnerId, UUID bootId, Instant now, Duration leaseDuration) {
                initialEmptyRead.countDown();
                if (!ready.get() || !claimed.compareAndSet(false, true)) {
                    return Optional.empty();
                }
                return Optional.of(new SchedulerClaim(processUid, ownerId, runnerId, bootId, 1,
                        now, now, now.plus(leaseDuration)));
            }

            @Override public boolean heartbeat(SchedulerClaim claim) { return false; }
            @Override public void release(UUID ignoredProcessUid, long executionEpoch) { }
            @Override public int releaseExpired(Instant now) { return 0; }
        };
        TransactionContext context = contextWith(scheduler);
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override public <T> T inTransaction(Isolation isolation,
                                                  TransactionWork<T> work) {
                return work.execute(context);
            }
        };

        try (SchedulerService service = new SchedulerService(transactions, claim -> {
            assertEquals(processUid, claim.processUid());
            executed.countDown();
        }, UUID.randomUUID(), 3, Duration.ofSeconds(5), Duration.ofMillis(1),
                failure -> { })) {
            service.start();
            assertTrue(initialEmptyRead.await(1, TimeUnit.SECONDS));

            // Models a committed READY row whose disposable Java callback and PostgreSQL
            // LISTEN/NOTIFY hint were both missed. The durable queue must still make progress.
            ready.set(true);
            assertTrue(executed.await(1, TimeUnit.SECONDS),
                    "a lost wake hint must not strand durable READY work");
        }
    }

    @Test
    void wakeGateDoesNotLoseSignalBetweenDurableReadAndWait() throws Exception {
        SchedulerService.WakeGate gate = new SchedulerService.WakeGate();
        long observed = gate.version();

        // Models a PostgreSQL commit notification arriving after claimNext started but before
        // the worker has entered its in-memory wait.
        gate.signal();

        CountDownLatch returned = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            gate.awaitChange(observed);
            returned.countDown();
        });
        assertTrue(returned.await(1, TimeUnit.SECONDS),
                "a notification that precedes await must still force an immediate recheck");
        waiter.join(Duration.ofSeconds(1));
    }

    @Test
    void wakeGateBlocksWithoutNotificationAndThenWakesOneWaiter() throws Exception {
        SchedulerService.WakeGate gate = new SchedulerService.WakeGate();
        long observed = gate.version();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            entered.countDown();
            gate.awaitChange(observed);
            returned.countDown();
        });

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertFalse(returned.await(150, TimeUnit.MILLISECONDS));
        gate.signal();
        assertTrue(returned.await(1, TimeUnit.SECONDS));
        waiter.join(Duration.ofSeconds(1));
    }

    @Test
    void boundedReconciliationWakesOnlyOneNormalWorkerPerInterval() throws Exception {
        int workerCount = 3;
        AtomicInteger claims = new AtomicInteger();
        CountDownLatch initialClaims = new CountDownLatch(workerCount);
        SchedulerRepository scheduler = emptyScheduler(() -> {
            claims.incrementAndGet();
            initialClaims.countDown();
        });
        TransactionContext context = contextWith(scheduler);
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T inTransaction(Isolation isolation, TransactionWork<T> work) {
                return work.execute(context);
            }
        };

        try (SchedulerService service = new SchedulerService(transactions, claim -> { },
                UUID.randomUUID(), workerCount, Duration.ofSeconds(5),
                Duration.ofMillis(1), failure -> { })) {
            service.start();
            assertTrue(initialClaims.await(1, TimeUnit.SECONDS));

            Thread.sleep(SchedulerService.DURABLE_RECONCILIATION_INTERVAL.toMillis() * 3);
            int reconciledClaims = claims.get();
            assertTrue(reconciledClaims >= workerCount + 2,
                    "the durable reconciler must periodically recheck PostgreSQL");
            assertTrue(reconciledClaims <= workerCount + 5,
                    "only one worker may poll when every queue is idle: " + reconciledClaims);

            service.wake();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (claims.get() == reconciledClaims && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertTrue(claims.get() > reconciledClaims,
                    "an explicit notification must still wake a worker immediately");
        }
    }

    @Test
    void ctrlCUsesOnlyTheDedicatedInterruptWorker() throws Exception {
        int workerCount = 2;
        AtomicInteger normalClaims = new AtomicInteger();
        AtomicInteger interruptClaims = new AtomicInteger();
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch initialNormalClaims = new CountDownLatch(workerCount);
        CountDownLatch threeInterruptChecks = new CountDownLatch(3);
        UUID processUid = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicBoolean pending =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        SchedulerRepository scheduler = new SchedulerRepository() {
            @Override public void enqueue(SchedulerQueueEntry entry) { }
            @Override public Optional<SchedulerClaim> claimNext(UUID runnerId, UUID bootId,
                    Instant now, Duration leaseDuration) {
                normalClaims.incrementAndGet();
                initialNormalClaims.countDown();
                return Optional.empty();
            }
            @Override public Optional<SchedulerClaim> claimInterrupted(UUID runnerId, UUID bootId,
                    Instant now, Duration leaseDuration) {
                interruptClaims.incrementAndGet();
                threeInterruptChecks.countDown();
                if (!pending.compareAndSet(true, false)) return Optional.empty();
                return Optional.of(new SchedulerClaim(processUid, ownerId, runnerId, bootId, 1,
                        now, now, now.plus(leaseDuration)));
            }
            @Override public boolean heartbeat(SchedulerClaim claim) { return false; }
            @Override public void release(UUID processUid, long executionEpoch) { }
            @Override public int releaseExpired(Instant now) { return 0; }
        };
        TransactionContext context = contextWith(scheduler);
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override public <T> T inTransaction(Isolation isolation,
                                                  TransactionWork<T> work) {
                return work.execute(context);
            }
        };

        try (SchedulerService service = new SchedulerService(transactions, claim -> {
            assertEquals(processUid, claim.processUid());
            cancelled.countDown();
        }, UUID.randomUUID(), workerCount, Duration.ofSeconds(5), Duration.ofMillis(1),
                failure -> { })) {
            service.start();
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
            assertTrue(initialNormalClaims.await(1, TimeUnit.SECONDS));
            assertEquals(workerCount, normalClaims.get());
            assertTrue(interruptClaims.get() >= 1);

            int normalBeforeInterruptWake = normalClaims.get();
            service.wakeInterrupt();
            assertTrue(threeInterruptChecks.await(1, TimeUnit.SECONDS));
            assertEquals(normalBeforeInterruptWake, normalClaims.get(),
                    "interrupt notification must not wake the normal worker pool");
        }
    }

    @Test
    void reportsJvmErrorsAsFatal() throws Exception {
        Error failure = new AssertionError("runtime broken");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch fatal = new CountDownLatch(1);
        SchedulerRepository scheduler = new SchedulerRepository() {
            @Override public void enqueue(SchedulerQueueEntry entry) { }
            @Override public Optional<SchedulerClaim> claimNext(UUID runnerId, UUID bootId,
                    Instant now, Duration leaseDuration) {
                throw failure;
            }
            @Override public boolean heartbeat(SchedulerClaim claim) { return false; }
            @Override public void release(UUID processUid, long executionEpoch) { }
            @Override public int releaseExpired(Instant now) { return 0; }
        };
        TransactionContext context = contextWith(scheduler);
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override public <T> T inTransaction(Isolation isolation,
                                                  TransactionWork<T> work) {
                return work.execute(context);
            }
        };

        SchedulerService service = new SchedulerService(transactions, claim -> { },
                UUID.randomUUID(), 1, Duration.ofSeconds(5), Duration.ofMillis(1), actual -> {
                    reported.set(actual);
                    fatal.countDown();
                });
        service.start();
        assertTrue(fatal.await(1, TimeUnit.SECONDS));
        service.close();
        assertSame(failure, reported.get());
        assertFalse(service.isRunning());
    }

    @Test
    void rejectsInvalidConstructionParameters() {
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override public <T> T inTransaction(Isolation isolation,
                                                  TransactionWork<T> work) {
                return work.execute(contextWith(emptyScheduler(() -> { })));
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new SchedulerService(transactions,
                claim -> { }, UUID.randomUUID(), 0, Duration.ofSeconds(5),
                Duration.ofMillis(1), failure -> { }));
        assertThrows(IllegalArgumentException.class, () -> new SchedulerService(transactions,
                claim -> { }, UUID.randomUUID(), 1, Duration.ZERO,
                Duration.ofMillis(1), failure -> { }));
        assertThrows(IllegalArgumentException.class, () -> new SchedulerService(transactions,
                claim -> { }, UUID.randomUUID(), 1, Duration.ofSeconds(5),
                Duration.ZERO, failure -> { }));
    }

    private static SchedulerRepository emptyScheduler(Runnable onClaim) {
        return new SchedulerRepository() {
            @Override public void enqueue(SchedulerQueueEntry entry) { }
            @Override public Optional<SchedulerClaim> claimNext(UUID runnerId, UUID bootId,
                    Instant now, Duration leaseDuration) {
                onClaim.run();
                return Optional.empty();
            }
            @Override public boolean heartbeat(SchedulerClaim claim) { return false; }
            @Override public void release(UUID processUid, long executionEpoch) { }
            @Override public int releaseExpired(Instant now) { return 0; }
        };
    }

    private static TransactionContext contextWith(SchedulerRepository scheduler) {
        return (TransactionContext) Proxy.newProxyInstance(
                SchedulerServiceTest.class.getClassLoader(),
                new Class<?>[]{TransactionContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "scheduler" -> scheduler;
                    case "toString" -> "scheduler-test-context";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
