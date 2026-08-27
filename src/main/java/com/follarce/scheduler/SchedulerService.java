package com.follarce.scheduler;

import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.DurableStorageFailure;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.scheduler.SchedulerClaim;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded virtual-thread scheduler; its queues and leases remain PostgreSQL rows. */
public final class SchedulerService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SchedulerService.class);
    /**
     * LISTEN/NOTIFY and local callbacks are disposable wake hints, not durable queue state.
     * A stateless reconciliation thread therefore wakes one normal worker and the sole
     * interrupt worker at a bounded interval so losing every hint cannot strand committed
     * READY work.
     */
    static final Duration DURABLE_RECONCILIATION_INTERVAL = Duration.ofMillis(100);
    private final TransactionExecutor transactions;
    private final ClaimedProcessHandler handler;
    private final UUID bootId;
    private final int workerCount;
    private final Duration leaseDuration;
    private final Duration errorBackoff;
    private final Consumer<Throwable> fatalFailure;
    private final AtomicBoolean running = new AtomicBoolean();
    private final WakeGate workAvailable = new WakeGate();
    private final WakeGate interruptAvailable = new WakeGate();
    private final List<Thread> workers = new ArrayList<>();
    private Thread interruptWorker;
    private Thread reconciliationWorker;

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
        reconciliationWorker = Thread.ofVirtual().name("cilexec-scheduler-reconciler")
                .start(this::reconciliationLoop);
    }

    public boolean isRunning() {
        Thread interrupt = interruptWorker;
        Thread reconciliation = reconciliationWorker;
        return running.get() && interrupt != null && interrupt.isAlive()
                && reconciliation != null && reconciliation.isAlive()
                && workers.stream().allMatch(Thread::isAlive);
    }

    /**
     * Periodically turns durable queue state back into a disposable wake hint. This thread
     * never reads PostgreSQL and never handles a process, so a long execution slice cannot
     * disable reconciliation.
     */
    private void reconciliationLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(DURABLE_RECONCILIATION_INTERVAL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running.get()) {
                workAvailable.signal();
                interruptAvailable.signal();
            }
        }
    }

    /** A dedicated worker consumes Ctrl+C claims; normal workers never claim those rows. */
    private void interruptLoop(UUID runnerId) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                long observedWake = interruptAvailable.version();
                Instant now = Instant.now();
                Optional<SchedulerClaim> claim = transactions.inTransaction(
                        Isolation.READ_COMMITTED,
                        transaction -> transaction.scheduler().claimInterrupted(
                                runnerId, bootId, now, leaseDuration));
                if (claim.isEmpty()) {
                    interruptAvailable.awaitChange(observedWake);
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
                // Observe before querying PostgreSQL. If a commit notification arrives while
                // the query is running or before this worker starts waiting, awaitChange sees
                // the newer version and returns immediately instead of losing that wake-up.
                long observedWake = workAvailable.version();
                Instant now = Instant.now();
                Optional<SchedulerClaim> claim = transactions.inTransaction(Isolation.READ_COMMITTED,
                        transaction -> transaction.scheduler().claimNext(
                                runnerId, bootId, now, leaseDuration));
                if (claim.isEmpty()) {
                    workAvailable.awaitChange(observedWake);
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

    /** Wakes one worker; the monotonic version prevents a query/wait lost-wake race. */
    public void wake() {
        if (running.get()) workAvailable.signal();
    }

    /** Wakes the cancellation worker without waking the normal execution pool. */
    public void wakeInterrupt() {
        if (running.get()) interruptAvailable.signal();
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof Error
                || failure instanceof DurableStorageFailure storage && storage.stopsRuntime();
    }

    @Override
    public synchronized void close() {
        running.set(false);
        workAvailable.signalAll();
        interruptAvailable.signalAll();
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
        Thread reconciliation = reconciliationWorker;
        if (interrupt != null && interrupt.isAlive()) interrupt.interrupt();
        if (reconciliation != null && reconciliation.isAlive()) reconciliation.interrupt();
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
        if (reconciliation != null) {
            try {
                reconciliation.join(Duration.ofSeconds(5));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        workers.clear();
        interruptWorker = null;
        reconciliationWorker = null;
    }

    /**
     * Disposable, in-memory notification gate. PostgreSQL remains the queue authority; the
     * version only makes the transition from a durable empty read to an in-memory wait atomic
     * with respect to notifications.
     */
    static final class WakeGate {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private long version;

        long version() {
            lock.lock();
            try {
                return version;
            } finally {
                lock.unlock();
            }
        }

        void signal() {
            lock.lock();
            try {
                version++;
                changed.signal();
            } finally {
                lock.unlock();
            }
        }

        void signalAll() {
            lock.lock();
            try {
                version++;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void awaitChange(long observedVersion) {
            lock.lock();
            try {
                while (version == observedVersion) {
                    try {
                        changed.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                lock.unlock();
            }
        }

    }
}
