package com.follarce.scheduler;

import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.scheduler.SchedulerClaim;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded virtual-thread scheduler; its queues and leases remain PostgreSQL rows. */
public final class SchedulerService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SchedulerService.class);
    private final TransactionExecutor transactions;
    private final ClaimedProcessHandler handler;
    private final UUID bootId;
    private final int workerCount;
    private final Duration leaseDuration;
    private final Duration errorBackoff;
    private final Consumer<Throwable> fatalFailure;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Semaphore workAvailable = new Semaphore(0);
    private final Semaphore interruptAvailable = new Semaphore(0);
    private final List<Thread> workers = new ArrayList<>();
    private Thread interruptWorker;

    public SchedulerService(TransactionExecutor transactions, ClaimedProcessHandler handler,
                            UUID bootId, int workerCount, Duration leaseDuration,
                            Duration errorBackoff, Consumer<Throwable> fatalFailure) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.handler = java.util.Objects.requireNonNull(handler, "handler");
        this.bootId = java.util.Objects.requireNonNull(bootId, "bootId");
        if (workerCount < 1) throw new IllegalArgumentException("workerCount must be positive");
        this.workerCount = workerCount;
        this.leaseDuration = java.util.Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.errorBackoff = java.util.Objects.requireNonNull(errorBackoff, "errorBackoff");
        if (errorBackoff.isZero() || errorBackoff.isNegative()) {
            throw new IllegalArgumentException("errorBackoff must be positive");
        }
        this.fatalFailure = java.util.Objects.requireNonNull(fatalFailure, "fatalFailure");
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Scheduler already started");
        }
        for (int index = 0; index < workerCount; index++) {
            UUID runnerId = UUID.randomUUID();
            workers.add(Thread.ofVirtual().name("cilexec-scheduler-" + index)
                    .start(() -> workerLoop(runnerId)));
        }
        UUID interruptRunnerId = UUID.randomUUID();
        interruptWorker = Thread.ofVirtual().name("cilexec-interrupt-worker")
                .start(() -> interruptLoop(interruptRunnerId));
    }

    public boolean isRunning() {
        Thread interrupt = interruptWorker;
        return running.get() && interrupt != null && interrupt.isAlive()
                && workers.stream().allMatch(Thread::isAlive);
    }

    /** A dedicated worker consumes Ctrl+C claims; normal workers never claim those rows. */
    private void interruptLoop(UUID runnerId) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Instant now = Instant.now();
                Optional<SchedulerClaim> claim = transactions.inTransaction(
                        Isolation.READ_COMMITTED,
                        transaction -> transaction.scheduler().claimInterrupted(
                                runnerId, bootId, now, leaseDuration));
                if (claim.isEmpty()) {
                    await(interruptAvailable);
                    continue;
                }
                handler.executeSlice(claim.orElseThrow());
            } catch (Throwable failure) {
                if (!running.get()) return;
                if (isFatal(failure)) {
                    running.set(false);
                    fatalFailure.accept(failure);
                    return;
                }
                LOG.warn("Interrupt worker {} rejected a cancellation cycle", runnerId,
                        failure);
                LockSupport.parkNanos(errorBackoff.toNanos());
            }
        }
    }

    private void workerLoop(UUID runnerId) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Instant now = Instant.now();
                Optional<SchedulerClaim> claim = transactions.inTransaction(Isolation.READ_COMMITTED,
                        transaction -> transaction.scheduler().claimNext(
                                runnerId, bootId, now, leaseDuration));
                if (claim.isEmpty()) {
                    awaitWork();
                    continue;
                }
                handler.executeSlice(claim.get());
            } catch (Throwable failure) {
                if (!running.get()) return;
                if (isFatal(failure)) {
                    running.set(false);
                    fatalFailure.accept(failure);
                    return;
                }
                LOG.warn("Scheduler worker {} rejected a claim cycle", runnerId, failure);
                LockSupport.parkNanos(errorBackoff.toNanos());
            }
        }
    }

    /** Wakes one worker; that worker drains claims until the durable queue is empty. */
    public void wake() {
        if (running.get() && workAvailable.availablePermits() < workerCount) {
            workAvailable.release();
        }
    }

    /** Wakes the cancellation worker without waking the normal execution pool. */
    public void wakeInterrupt() {
        if (running.get() && interruptAvailable.availablePermits() == 0) {
            interruptAvailable.release();
        }
    }

    private void awaitWork() {
        await(workAvailable);
    }

    private static void await(Semaphore signal) {
        try {
            signal.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof Error
                || failure instanceof com.follarce.persistence.postgres.error.PersistenceFailure persistence
                && (persistence.kind() == com.follarce.persistence.postgres.error.PersistenceFailure.Kind.DATABASE_UNAVAILABLE
                || persistence.kind() == com.follarce.persistence.postgres.error.PersistenceFailure.Kind.RUNTIME_FENCED);
    }

    @Override
    public synchronized void close() {
        running.set(false);
        workAvailable.release(workerCount);
        interruptAvailable.release();
        // Give workers a brief window to finish their current transaction cleanly.
        long gracefulWindowMillis = Math.min(Math.min(errorBackoff.toMillis(), 5_000) * 2,
                10_000);
        for (Thread worker : workers) {
            try {
                worker.join(gracefulWindowMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Interrupt any workers that didn't stop yet, then wait.
        for (Thread worker : workers) {
            if (worker.isAlive()) worker.interrupt();
        }
        Thread interrupt = interruptWorker;
        if (interrupt != null && interrupt.isAlive()) interrupt.interrupt();
        for (Thread worker : workers) {
            try {
                worker.join(Duration.ofSeconds(5));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (interrupt != null) {
            try {
                interrupt.join(Duration.ofSeconds(5));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        workers.clear();
        interruptWorker = null;
    }
}
