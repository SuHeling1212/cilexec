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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerServiceTest {
    @Test
    void idleWorkersDoNotRepeatedlyClaimUntilNotified() throws Exception {
        int workerCount = 3;
        AtomicInteger claims = new AtomicInteger();
        CountDownLatch initialClaims = new CountDownLatch(workerCount);
        CountDownLatch notifiedClaim = new CountDownLatch(workerCount + 1);
        SchedulerRepository scheduler = emptyScheduler(() -> {
            claims.incrementAndGet();
            initialClaims.countDown();
            notifiedClaim.countDown();
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
            assertFalse(notifiedClaim.await(150, TimeUnit.MILLISECONDS));
            assertEquals(workerCount, claims.get());

            service.wake();
            assertTrue(notifiedClaim.await(1, TimeUnit.SECONDS));
            assertEquals(workerCount + 1, claims.get());
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
