package com.follarce.effect;

import com.follarce.domain.effect.EffectAttempt;
import com.follarce.domain.effect.EffectPayload;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.domain.port.DurableStorageFailure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** Bounded workers that execute journaled effects strictly outside database transactions. */
public final class EffectWorkerService implements AutoCloseable {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(EffectWorkerService.class);
    private static final Duration STALL_CLAIM_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private static final int WAKE_RETRY_LIMIT = 3;
    private static final long WAKE_RETRY_BACKOFF_MILLIS = 100;

    private final TransactionExecutor effectTransactions;
    private final TransactionExecutor runtimeTransactions;
    private final EffectHandlerRegistry handlers;
    private final int workerCount;
    private final Duration errorBackoff;
    private final Clock clock;
    private final Consumer<Throwable> fatalFailure;
    private final Optional<UUID> bootId;
    private final Runnable schedulerWake;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Semaphore workAvailable = new Semaphore(0);
    private final List<UUID> workerIds = new ArrayList<>();
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicBoolean[] busy;
    private volatile Thread heartbeat;

    public EffectWorkerService(TransactionExecutor transactions,
                               EffectHandlerRegistry handlers,
                               int workerCount,
                               Duration errorBackoff,
                               Clock clock,
                               Consumer<Throwable> fatalFailure) {
        this(transactions, transactions, handlers, workerCount, errorBackoff, clock, fatalFailure,
                Optional.empty(), () -> { });
    }

    public EffectWorkerService(TransactionExecutor effectTransactions,
                               TransactionExecutor runtimeTransactions,
                               EffectHandlerRegistry handlers,
                               int workerCount,
                               Duration errorBackoff,
                               Clock clock,
                               Consumer<Throwable> fatalFailure) {
        this(effectTransactions, runtimeTransactions, handlers, workerCount, errorBackoff, clock,
                fatalFailure, Optional.empty(), () -> { });
    }

    /** Production constructor: attempts reference workers registered under this boot. */
    public EffectWorkerService(TransactionExecutor effectTransactions,
                               TransactionExecutor runtimeTransactions,
                               UUID bootId,
                               EffectHandlerRegistry handlers,
                               int workerCount,
                               Duration errorBackoff,
                               Clock clock,
                               Consumer<Throwable> fatalFailure) {
        this(effectTransactions, runtimeTransactions, handlers, workerCount, errorBackoff, clock,
                fatalFailure, Optional.of(java.util.Objects.requireNonNull(bootId, "bootId")),
                () -> { });
    }

    public EffectWorkerService(TransactionExecutor effectTransactions,
                               TransactionExecutor runtimeTransactions,
                               UUID bootId,
                               EffectHandlerRegistry handlers,
                               int workerCount,
                               Duration errorBackoff,
                               Clock clock,
                               Consumer<Throwable> fatalFailure,
                               Runnable schedulerWake) {
        this(effectTransactions, runtimeTransactions, handlers, workerCount, errorBackoff, clock,
                fatalFailure, Optional.of(java.util.Objects.requireNonNull(bootId, "bootId")),
                schedulerWake);
    }

    private EffectWorkerService(TransactionExecutor effectTransactions,
                                TransactionExecutor runtimeTransactions,
                                EffectHandlerRegistry handlers,
                                int workerCount,
                                Duration errorBackoff,
                                Clock clock,
                                Consumer<Throwable> fatalFailure,
                                Optional<UUID> bootId,
                                Runnable schedulerWake) {
        this.effectTransactions = java.util.Objects.requireNonNull(effectTransactions,
                "effectTransactions");
        this.runtimeTransactions = java.util.Objects.requireNonNull(runtimeTransactions,
                "runtimeTransactions");
        this.handlers = java.util.Objects.requireNonNull(handlers, "handlers");
        if (workerCount < 1) throw new IllegalArgumentException("workerCount must be positive");
        this.workerCount = workerCount;
        this.errorBackoff = java.util.Objects.requireNonNull(errorBackoff, "errorBackoff");
        if (errorBackoff.isZero() || errorBackoff.isNegative()) {
            throw new IllegalArgumentException("errorBackoff must be positive");
        }
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.fatalFailure = java.util.Objects.requireNonNull(fatalFailure, "fatalFailure");
        this.bootId = java.util.Objects.requireNonNull(bootId, "bootId");
        this.schedulerWake = java.util.Objects.requireNonNull(schedulerWake, "schedulerWake");
        this.busy = new AtomicBoolean[workerCount];
        for (int index = 0; index < workerCount; index++) {
            busy[index] = new AtomicBoolean();
        }
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Effect workers already started");
        }
        List<UUID> workerIds = new ArrayList<>(workerCount);
        for (int index = 0; index < workerCount; index++) {
            workerIds.add(UUID.randomUUID());
        }
        try {
            registerWorkers(workerIds);
            this.workerIds.addAll(workerIds);
            for (int index = 0; index < workerCount; index++) {
                int workerIndex = index;
                UUID workerId = workerIds.get(index);
                workers.add(Thread.ofVirtual().name("cilexec-effect-" + index)
                        .start(() -> workerLoop(workerIndex, workerId)));
            }
            heartbeat = Thread.ofVirtual().name("cilexec-effect-heartbeat")
                    .start(this::heartbeatLoop);
        } catch (RuntimeException failure) {
            running.set(false);
            throw failure;
        }
    }

    /**
     * Refreshes only idle workers' runner heartbeats. A worker executing an effect is not
     * refreshed, so {@code claimStalled} can reclaim an execution that is genuinely stuck
     * (for example blocked forever on a terminal socket write) once its runner heartbeat
     * expires. Every journaled effect is bounded well below the stall timeout, so healthy
     * long-running effects are never mistaken for dead ones.
     */
    private void heartbeatLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (workerIds.isEmpty()) continue;
            Instant now = clock.instant();
            try {
                runtimeTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
                    for (int index = 0; index < workerIds.size(); index++) {
                        if (!busy[index].get()) {
                            transaction.effects().heartbeatWorker(workerIds.get(index), now);
                        }
                    }
                    return null;
                });
            } catch (RuntimeException failure) {
                if (!isFatal(failure)) {
                    continue;
                }
                if (!running.get()) return;
                running.set(false);
                fatalFailure.accept(failure);
                return;
            }
        }
    }

    private void registerWorkers(List<UUID> workerIds) {
        if (bootId.isEmpty()) return;
        Instant now = clock.instant();
        runtimeTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            for (UUID workerId : workerIds) {
                transaction.effects().registerWorker(workerId, bootId.orElseThrow(), now);
            }
            return null;
        });
    }

    public boolean isRunning() {
        Thread currentHeartbeat = heartbeat;
        return running.get() && currentHeartbeat != null && currentHeartbeat.isAlive()
                && workers.stream().allMatch(Thread::isAlive);
    }

    private void workerLoop(int index, UUID workerId) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<ClaimedWork> claimed = claimOne(workerId);
                if (claimed.isPresent()) {
                    busy[index].set(true);
                    try {
                        executeOutsideTransaction(claimed.orElseThrow());
                    } finally {
                        busy[index].set(false);
                    }
                    continue;
                }
                Optional<EffectRequest> stalled = claimStalled(workerId);
                if (stalled.isPresent()) {
                    resolveStalled(stalled.orElseThrow());
                    continue;
                }
                Optional<EffectRequest> recovered = claimRecoverableUnknown(workerId);
                if (recovered.isEmpty()) {
                    awaitWork();
                    continue;
                }
                resolveRecoveredUnknown(recovered.orElseThrow());
            } catch (Throwable failure) {
                if (!running.get()) return;
                if (isFatal(failure)) {
                    running.set(false);
                    fatalFailure.accept(failure);
                    return;
                }
                LockSupport.parkNanos(errorBackoff.toNanos());
            }
        }
    }

    /** Wakes one worker; it continues claiming until both durable effect queues are empty. */
    public void wake() {
        if (running.get() && workAvailable.availablePermits() < workerCount) {
            workAvailable.release();
        }
    }

    private void awaitWork() {
        try {
            workAvailable.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<ClaimedWork> claimOne(UUID workerId) {
        Instant now = clock.instant();
        return effectTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            List<EffectRequest> claimed = transaction.effects().claimPending(workerId, now, 1);
            if (claimed.isEmpty()) return Optional.empty();
            EffectRequest executing = claimed.getFirst().start(now);
            if (!transaction.effects().update(executing, EffectRequest.Status.CLAIMED)) {
                throw new IllegalStateException("Effect claim was fenced before execution");
            }
            int attemptNumber = transaction.effects().nextAttemptNumber(executing.effectId());
            EffectAttempt attempt = EffectAttempt.claim(executing.effectId(), attemptNumber,
                    workerId, now).start();
            transaction.effects().saveAttempt(attempt);
            return Optional.of(new ClaimedWork(executing, attempt));
        });
    }

    private Optional<EffectRequest> claimRecoverableUnknown(UUID workerId) {
        Instant now = clock.instant();
        return effectTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            List<EffectRequest> claimed = transaction.effects()
                    .claimRecoverableUnknown(workerId, now, 1);
            return claimed.isEmpty() ? Optional.empty() : Optional.of(claimed.getFirst());
        });
    }

    private Optional<EffectRequest> claimStalled(UUID workerId) {
        Instant now = clock.instant();
        return effectTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            List<EffectRequest> claimed = transaction.effects().claimStalled(workerId, now,
                    STALL_CLAIM_TIMEOUT.toMillis(), 1);
            return claimed.isEmpty() ? Optional.empty() : Optional.of(claimed.getFirst());
        });
    }

    /**
     * A stalled effect may have produced an external side effect, so it resolves as UNKNOWN.
     * Manual policies keep the durable row UNKNOWN for human inspection, but the waiting
     * process must still be woken with a visible failure — otherwise the process freezes
     * forever on an effect that will never execute again.
     */
    private void resolveStalled(EffectRequest effect) {
        if (effect.policy().unknownAction() == EffectRequest.UnknownAction.MANUAL) {
            Instant now = clock.instant();
            wakeProcessAfterPersist(effect,
                    new Continuation.PersistedValue("error",
                            "Effect execution stalled; outcome unknown"), now);
            schedulerWake.run();
            return;
        }
        resolveRecoveredUnknown(effect);
    }

    private void resolveRecoveredUnknown(EffectRequest effect) {
        Optional<String> denial = authorizationFailure(effect);
        if (denial.isPresent()) {
            retainRecoveredUnknown(effect, denial.orElseThrow());
            return;
        }
        EffectHandler handler;
        try {
            handler = handlers.require(effect.effectType());
        } catch (RuntimeException unsupported) {
            retainRecoveredUnknown(effect, "Unsupported effect recovery handler: "
                    + safeMessage(unsupported));
            return;
        }
        switch (effect.policy().unknownAction()) {
            case QUERY_REMOTE -> queryRecoveredOutcome(handler, effect);
            case RETRY_IDEMPOTENT -> retryRecoveredEffect(handler, effect);
            case MANUAL -> throw new IllegalStateException(
                    "Manual UNKNOWN effect was claimed for automatic recovery");
        }
    }

    private void queryRecoveredOutcome(EffectHandler handler, EffectRequest effect) {
        try {
            Optional<EffectPayload> outcome = handler.queryOutcomePayload(effect);
            if (outcome.isPresent()) {
                persistRecoveredSuccess(effect, outcome.orElseThrow());
            } else {
                retainRecoveredUnknown(effect, "Remote outcome is still unknown");
            }
        } catch (Exception failure) {
            retainRecoveredUnknown(effect, "Remote outcome query failed: "
                    + safeMessage(failure));
        }
    }

    private void retryRecoveredEffect(EffectHandler handler, EffectRequest unknown) {
        ClaimedWork retry = beginRecoveredRetry(unknown);
        try {
            EffectPayload result = handler.executePayload(retry.effect().requestPayload(),
                    retry.effect().policy().idempotencyKey());
            persistSuccess(retry, result);
        } catch (EffectOutcomeUnknownException failure) {
            persistFailure(retry, "OUTCOME_UNKNOWN", safeMessage(failure), true);
        } catch (Exception failure) {
            persistFailure(retry, "EXECUTION_FAILED", safeMessage(failure), false);
        }
    }

    private ClaimedWork beginRecoveredRetry(EffectRequest unknown) {
        Instant now = clock.instant();
        return effectTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            EffectRequest executing = unknown.beginRecoveredRetry(now);
            if (!transaction.effects().update(executing, EffectRequest.Status.UNKNOWN)) {
                throw new IllegalStateException("Recovered UNKNOWN effect was fenced");
            }
            int attemptNumber = transaction.effects().nextAttemptNumber(unknown.effectId());
            EffectAttempt attempt = EffectAttempt.claim(unknown.effectId(), attemptNumber,
                    unknown.claimedBy().orElseThrow(), now).start();
            transaction.effects().saveAttempt(attempt);
            return new ClaimedWork(executing, attempt);
        });
    }

    private void persistRecoveredSuccess(EffectRequest unknown, EffectPayload result) {
        Instant now = clock.instant();
        EffectRequest completed = unknown.resolveRecoveredSuccess(result, now);
        runtimeTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            if (!transaction.effects().update(completed, EffectRequest.Status.UNKNOWN)) {
                throw new IllegalStateException("Recovered effect completion was fenced");
            }
            return null;
        });
        wakeProcessAfterPersist(completed, result.deliveryValue(), now);
        schedulerWake.run();
    }

    private void retainRecoveredUnknown(EffectRequest unknown, String reason) {
        Instant now = clock.instant();
        EffectRequest retained = unknown.retainRecoveredUnknown(reason, now);
        runtimeTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            if (!transaction.effects().update(retained, EffectRequest.Status.UNKNOWN)) {
                throw new IllegalStateException("Recovered UNKNOWN effect was fenced");
            }
            return null;
        });
    }

    private void executeOutsideTransaction(ClaimedWork work) {
        EffectRequest effect = work.effect();
        Optional<String> denial = authorizationFailure(effect);
        if (denial.isPresent()) {
            persistFailure(work, "EFFECT_NOT_AUTHORIZED", denial.orElseThrow(), false);
            return;
        }
        EffectHandler handler;
        try {
            handler = handlers.require(effect.effectType());
        } catch (RuntimeException unsupported) {
            persistFailure(work, "UNSUPPORTED_EFFECT", safeMessage(unsupported), false);
            return;
        }
        try {
            EffectPayload result = handler.executePayload(effect.requestPayload(),
                    effect.policy().idempotencyKey());
            persistSuccess(work, result);
        } catch (EffectOutcomeUnknownException unknown) {
            resolveUnknown(handler, work, unknown);
        } catch (Exception failure) {
            persistFailure(work, "EXECUTION_FAILED", safeMessage(failure), false);
        }
    }

    /**
     * Re-authorizes every durable request immediately before external execution. This is a
     * deliberate second boundary: a forged or stale effect row must never inherit the authority
     * of the worker service role.
     */
    private Optional<String> authorizationFailure(EffectRequest effect) {
        return runtimeTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            Optional<CilProcess> process = transaction.processes().findByUid(effect.processUid());
            if (process.isEmpty()) return Optional.of("Owning process no longer exists");
            UUID ownerId = process.orElseThrow().ownerId();
            if (!transaction.auth().hasCapabilityByAdministrator(
                    ownerId, Capability.EFFECT_REQUEST)) {
                return Optional.of("Owner is not allowed to request external effects");
            }
            if ((effect.effectType().equals("system.exec")
                    || effect.effectType().equals("socket.bind")
                    || effect.effectType().equals("socket.accept"))
                    && !transaction.auth().hasCapabilityByAdministrator(
                    ownerId, Capability.SYSTEM_ADMIN)) {
                return Optional.of("Effect requires administrator authority");
            }
            return Optional.empty();
        });
    }

    private void resolveUnknown(EffectHandler handler, ClaimedWork work,
                                EffectOutcomeUnknownException failure) {
        EffectRequest effect = work.effect();
        switch (effect.policy().unknownAction()) {
            case QUERY_REMOTE -> {
                Optional<EffectPayload> remote;
                try {
                    remote = handler.queryOutcomePayload(effect);
                } catch (Exception resolutionFailure) {
                    persistFailure(work, "OUTCOME_QUERY_FAILED",
                            safeMessage(resolutionFailure), true);
                    return;
                }
                if (remote.isPresent()) {
                    persistSuccess(work, remote.get());
                } else {
                    persistFailure(work, "OUTCOME_UNKNOWN", safeMessage(failure), true);
                }
            }
            case RETRY_IDEMPOTENT -> retryIdempotent(handler, work, failure);
            case MANUAL -> persistFailure(work, "OUTCOME_UNKNOWN",
                    safeMessage(failure), true);
        }
    }

    private void retryIdempotent(EffectHandler handler, ClaimedWork first,
                                 EffectOutcomeUnknownException originalFailure) {
        ClaimedWork retry = beginRetry(first, safeMessage(originalFailure));
        try {
            EffectPayload result = handler.executePayload(retry.effect().requestPayload(),
                    retry.effect().policy().idempotencyKey());
            persistSuccess(retry, result);
        } catch (EffectOutcomeUnknownException unknown) {
            persistFailure(retry, "OUTCOME_UNKNOWN", safeMessage(unknown), true);
        } catch (Exception failure) {
            persistFailure(retry, "EXECUTION_FAILED", safeMessage(failure), false);
        }
    }

    private ClaimedWork beginRetry(ClaimedWork first, String reason) {
        Instant now = clock.instant();
        return effectTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            EffectAttempt unknown = first.attempt().unknown("OUTCOME_UNKNOWN", reason, now);
            requireAttemptUpdated(transaction.effects().updateAttempt(unknown,
                    EffectAttempt.Status.EXECUTING), "Initial effect attempt was fenced");
            int attemptNumber = transaction.effects().nextAttemptNumber(first.effect().effectId());
            EffectAttempt retry = EffectAttempt.claim(first.effect().effectId(), attemptNumber,
                    first.effect().claimedBy().orElseThrow(), now).start();
            transaction.effects().saveAttempt(retry);
            return new ClaimedWork(first.effect(), retry);
        });
    }

    private void persistSuccess(ClaimedWork work, EffectPayload result) {
        Instant now = clock.instant();
        EffectRequest executing = work.effect();
        EffectRequest completed = executing.complete(result, now);
        EffectAttempt succeeded = work.attempt().succeed(result.deliveryValue(), now);
        runtimeTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            if (!transaction.effects().update(completed, EffectRequest.Status.EXECUTING)) {
                throw new IllegalStateException("Effect completion was fenced");
            }
            requireAttemptUpdated(transaction.effects().updateAttempt(succeeded,
                    EffectAttempt.Status.EXECUTING), "Effect attempt completion was fenced");
            return null;
        });
        wakeProcessAfterPersist(completed, result.deliveryValue(), now);
        schedulerWake.run();
    }

    private void persistFailure(ClaimedWork work, String code, String reason, boolean unknown) {
        Instant now = clock.instant();
        EffectRequest executing = work.effect();
        EffectRequest failed = unknown ? executing.unknown(reason, now) : executing.fail(reason, now);
        EffectAttempt failedAttempt = unknown
                ? work.attempt().unknown(code, reason, now)
                : work.attempt().fail(code, reason, now);
        runtimeTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            if (!transaction.effects().update(failed, EffectRequest.Status.EXECUTING)) {
                throw new IllegalStateException("Effect failure was fenced");
            }
            requireAttemptUpdated(transaction.effects().updateAttempt(failedAttempt,
                    EffectAttempt.Status.EXECUTING), "Effect attempt failure was fenced");
            return null;
        });
        if (!unknown) {
            wakeProcessAfterPersist(failed,
                    new Continuation.PersistedValue("error", reason), now);
            schedulerWake.run();
        }
    }

    /**
     * Delivers the durable result after the effect row committed, so a process wake conflict
     * can never roll the result back. Non-fatal wake conflicts (deadlock, serialization, CAS)
     * are retried with backoff; if they still fail, the delivery sweeper redelivers the
     * completed effect, so a lost wake can never freeze the process permanently.
     */
    private void wakeProcessAfterPersist(EffectRequest effect,
                                         Continuation.PersistedValue result,
                                         Instant now) {
        int failures = 0;
        while (true) {
            try {
                runtimeTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
                    wakeProcess(transaction, effect, result, now);
                    return null;
                });
                return;
            } catch (RuntimeException failure) {
                if (isFatal(failure)) {
                    throw failure;
                }
                if (++failures >= WAKE_RETRY_LIMIT) {
                    LOG.error("Effect {} completed but its process {} could not be woken after "
                                    + "{} attempts: {}; the delivery sweeper will redeliver it",
                            effect.effectId(), effect.processUid(), failures, safeMessage(failure));
                    return;
                }
                LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                        WAKE_RETRY_BACKOFF_MILLIS * failures));
            }
        }
    }

    /** Wakes the process waiting on this effect; returns whether a wake was applied. */
    public static boolean wakeProcess(com.follarce.domain.port.TransactionContext transaction,
                                      EffectRequest effect,
                                      Continuation.PersistedValue result,
                                      Instant now) {
        Optional<CilProcess> loaded = transaction.processes().findByUid(effect.processUid());
        if (loaded.isEmpty() || !isWaitingFor(loaded.get(), effect.effectId())) return false;
        CilProcess current = loaded.get();
        Map<String, Continuation.PersistedValue> variables =
                new LinkedHashMap<>(current.continuation().globalVariables());
        variables.put(ProcessInbox.EFFECT_RESULT, result);
        Continuation source = current.continuation();
        Continuation resumed = new Continuation(source.programId(), source.programHash(),
                source.programCounter(), source.callStack(), source.scopeStack(),
                source.exceptionStack(), source.controlStack(), Optional.empty(),
                Map.copyOf(variables), source.packageBindings(), source.languageVersion(),
                source.runtimeFormatVersion());
        CilProcess.Status target = current.status() == CilProcess.Status.PAUSED
                ? CilProcess.Status.PAUSED : CilProcess.Status.READY;
        CilProcess ready = current.commitStatement(resumed, target,
                current.stateVersion(), current.executionEpoch(), now);
        requireUpdated(transaction.processes().update(ready, current.stateVersion(),
                current.executionEpoch()));
        if (ready.status() == CilProcess.Status.READY) {
            transaction.scheduler().enqueue(new SchedulerQueueEntry(effect.processUid(), now, now,
                    SchedulerQueueEntry.Status.READY));
        }
        return true;
    }

    public static boolean isWaitingFor(CilProcess process, UUID effectId) {
        return (process.status() == CilProcess.Status.WAITING_EFFECT
                || process.status() == CilProcess.Status.PAUSED)
                && process.continuation().waitState().map(wait ->
                        wait.kind() == Continuation.WaitKind.EFFECT
                                && wait.targetId().equals(Optional.of(effectId))).orElse(false);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof Error
                || failure instanceof DurableStorageFailure storage && storage.stopsRuntime();
    }

    private static void requireUpdated(ProcessRepository.UpdateResult result) {
        if (result != ProcessRepository.UpdateResult.UPDATED) {
            throw new IllegalStateException("Concurrent process update rejected: " + result);
        }
    }

    private static void requireAttemptUpdated(boolean updated, String message) {
        if (!updated) throw new IllegalStateException(message);
    }

    private record ClaimedWork(EffectRequest effect, EffectAttempt attempt) {
        private ClaimedWork {
            java.util.Objects.requireNonNull(effect, "effect");
            java.util.Objects.requireNonNull(attempt, "attempt");
            if (!effect.effectId().equals(attempt.effectId())) {
                throw new IllegalArgumentException("Effect attempt belongs to another request");
            }
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        // Wake idle workers without interrupting an in-flight JDBC operation. Interrupting a
        // PostgreSQL query closes its socket and makes an otherwise clean shutdown look like a
        // broken database connection.
        workAvailable.release(workerCount);
        Instant gracefulDeadline = Instant.now().plus(Duration.ofSeconds(5));
        for (Thread worker : workers) {
            try {
                Duration remaining = Duration.between(Instant.now(), gracefulDeadline);
                if (remaining.isPositive()) worker.join(remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // A genuinely stuck database or external call must not block shutdown forever.
        workers.stream().filter(Thread::isAlive).forEach(Thread::interrupt);
        for (Thread worker : workers) {
            if (!worker.isAlive()) continue;
            try {
                worker.join(Duration.ofSeconds(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        workers.clear();
        Thread heartbeat = this.heartbeat;
        this.heartbeat = null;
        if (heartbeat != null) {
            heartbeat.interrupt();
            try {
                heartbeat.join(Duration.ofSeconds(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        workerIds.clear();
    }
}
