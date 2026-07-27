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
    private final Duration idlePoll;
    private final Consumer<Throwable> fatalFailure;
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<Thread> workers = new ArrayList<>();

    public SchedulerService(TransactionExecutor transactions, ClaimedProcessHandler handler,
                            UUID bootId, int workerCount, Duration leaseDuration,
                            Duration idlePoll, Consumer<Throwable> fatalFailure) {
        this.transactions = transactions;
        this.handler = handler;
        this.bootId = bootId;
        this.workerCount = workerCount;
        this.leaseDuration = leaseDuration;
        this.idlePoll = idlePoll;
        this.fatalFailure = fatalFailure;
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
    }

    public boolean isRunning() {
        return running.get() && workers.stream().allMatch(Thread::isAlive);
    }

    private void workerLoop(UUID runnerId) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Instant now = Instant.now();
                Optional<SchedulerClaim> claim = transactions.inTransaction(Isolation.READ_COMMITTED,
                        transaction -> transaction.scheduler().claimNext(
                                runnerId, bootId, now, leaseDuration));
                if (claim.isEmpty()) {
                    LockSupport.parkNanos(idlePoll.toNanos());
                    continue;
                }
                handler.executeOne(claim.get());
            } catch (Throwable failure) {
                if (!running.get()) return;
                if (isFatal(failure)) {
                    running.set(false);
                    fatalFailure.accept(failure);
                    return;
                }
                LOG.warn("Scheduler worker {} rejected a claim cycle", runnerId, failure);
                LockSupport.parkNanos(idlePoll.toNanos());
            }
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof com.follarce.persistence.postgres.error.PersistenceFailure persistence
                && (persistence.kind() == com.follarce.persistence.postgres.error.PersistenceFailure.Kind.DATABASE_UNAVAILABLE
                || persistence.kind() == com.follarce.persistence.postgres.error.PersistenceFailure.Kind.RUNTIME_FENCED);
    }

    @Override
    public synchronized void close() {
        running.set(false);
        // Give workers a brief window to finish their current transaction cleanly.
        for (Thread worker : workers) {
            try {
                worker.join(idlePoll.toMillis() * 2);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Interrupt any workers that didn't stop yet, then wait.
        for (Thread worker : workers) {
            if (worker.isAlive()) worker.interrupt();
        }
        for (Thread worker : workers) {
            try {
                worker.join(Duration.ofSeconds(5));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        workers.clear();
    }
}
