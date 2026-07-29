package com.follarce.timer;

import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.domain.timer.ProcessTimer;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persists timers and wakes their processes without relying on JVM sleep state. */
public final class TimerService {
    private final TransactionExecutor runtimeTransactions;
    private final UserTransactionExecutor userTransactions;
    private final Clock clock;

    public TimerService(TransactionExecutor runtimeTransactions,
                        UserTransactionExecutor userTransactions,
                        Clock clock) {
        this.runtimeTransactions = java.util.Objects.requireNonNull(runtimeTransactions,
                "runtimeTransactions");
        this.userTransactions = java.util.Objects.requireNonNull(userTransactions,
                "userTransactions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** Creates the timer and moves the process to WAITING_TIMER in one statement transaction. */
    public ProcessTimer schedule(UUID ownerId, UUID processUid, Instant wakeAt,
                                 Optional<Continuation.PersistedValue> payload) {
        Instant now = clock.instant();
        if (wakeAt.isBefore(now)) {
            wakeAt = now;
        }
        Instant effectiveWakeAt = wakeAt;
        return userTransactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            CilProcess current = transaction.processes().findByUid(processUid)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown process"));
            if (current.status() != CilProcess.Status.RUNNING) {
                throw new IllegalStateException("Only a RUNNING process may schedule its wait timer");
            }
            UUID timerId = UUID.randomUUID();
            ProcessTimer timer = new ProcessTimer(timerId, processUid, effectiveWakeAt,
                    ProcessTimer.Status.SCHEDULED, now, Optional.empty(), Optional.empty(),
                    Optional.empty(), payload);
            Continuation waitingContinuation = withWait(current.continuation(),
                    new Continuation.WaitState(Continuation.WaitKind.TIMER,
                            Optional.of(timerId), Optional.empty()));
            CilProcess waiting = current.commitStatement(waitingContinuation,
                    CilProcess.Status.WAITING_TIMER, current.stateVersion(),
                    current.executionEpoch(), now);
            transaction.timers().save(timer);
            requireUpdated(transaction.processes().update(waiting, current.stateVersion(),
                    current.executionEpoch()));
            transaction.scheduler().release(processUid, current.executionEpoch());
            return timer;
        });
    }

    /** Claims and fires due rows atomically; safe to call repeatedly after a restart. */
    public int fireDue(UUID runnerId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Instant now = clock.instant();
        return runtimeTransactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            List<ProcessTimer> claimed = transaction.timers().claimDue(runnerId, now, limit);
            int fired = 0;
            for (ProcessTimer timer : claimed) {
                ProcessTimer completed = timer.fire(now);
                if (!transaction.timers().update(completed, ProcessTimer.Status.CLAIMED)) {
                    throw new IllegalStateException("Claimed timer lost its ownership");
                }
                Optional<CilProcess> process = transaction.processes().findByUid(timer.processUid());
                if (process.isPresent() && isWaitingFor(process.get(), timer.timerId())) {
                    CilProcess current = process.get();
                    Continuation resumedContinuation = resume(current.continuation(), timer.payload());
                    CilProcess.Status target = wakeTarget(current);
                    CilProcess ready = current.commitStatement(resumedContinuation,
                            target, current.stateVersion(),
                            current.executionEpoch(), now);
                    requireUpdated(transaction.processes().update(ready, current.stateVersion(),
                            current.executionEpoch()));
                    enqueueIfReady(transaction, ready, now);
                }
                fired++;
            }
            return fired;
        });
    }

    public Optional<Instant> nextWakeAt() {
        return runtimeTransactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.timers().nextScheduledWakeAt());
    }

    public boolean cancel(UUID ownerId, UUID timerId) {
        Instant now = clock.instant();
        return userTransactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Optional<ProcessTimer> timer = transaction.timers().findById(timerId);
            if (timer.isEmpty() || timer.get().status() == ProcessTimer.Status.FIRED
                    || timer.get().status() == ProcessTimer.Status.CANCELLED) {
                return false;
            }
            ProcessTimer cancelled = timer.get().cancel();
            if (!transaction.timers().update(cancelled, timer.get().status())) {
                return false;
            }
            Optional<CilProcess> process = transaction.processes()
                    .findByUid(timer.orElseThrow().processUid());
            if (process.isPresent() && isWaitingFor(process.orElseThrow(), timerId)) {
                CilProcess waiting = process.orElseThrow();
                Continuation resumed = resume(waiting.continuation(), Optional.of(
                        new Continuation.PersistedValue("bool", "true")));
                CilProcess.Status target = wakeTarget(waiting);
                CilProcess ready = waiting.commitStatement(resumed, target,
                        waiting.stateVersion(), waiting.executionEpoch(), now);
                requireUpdated(transaction.processes().update(ready, waiting.stateVersion(),
                        waiting.executionEpoch()));
                enqueueIfReady(transaction, ready, now);
            }
            return true;
        });
    }

    private static boolean isWaitingFor(CilProcess process, UUID timerId) {
        return (process.status() == CilProcess.Status.WAITING_TIMER
                || process.status() == CilProcess.Status.PAUSED)
                && process.continuation().waitState().map(wait ->
                        wait.kind() == Continuation.WaitKind.TIMER
                                && wait.targetId().equals(Optional.of(timerId))).orElse(false);
    }

    private static CilProcess.Status wakeTarget(CilProcess process) {
        return process.status() == CilProcess.Status.PAUSED
                ? CilProcess.Status.PAUSED : CilProcess.Status.READY;
    }

    private static void enqueueIfReady(
            com.follarce.domain.port.TransactionContext transaction,
            CilProcess process,
            Instant now
    ) {
        if (process.status() == CilProcess.Status.READY) {
            transaction.scheduler().enqueue(new SchedulerQueueEntry(
                    process.identity().processUid(), now, now,
                    SchedulerQueueEntry.Status.READY));
        }
    }

    private static Continuation withWait(Continuation continuation, Continuation.WaitState wait) {
        return copy(continuation, Optional.of(wait), continuation.globalVariables());
    }

    private static Continuation resume(
            Continuation continuation,
            Optional<Continuation.PersistedValue> payload
    ) {
        Map<String, Continuation.PersistedValue> variables =
                new LinkedHashMap<>(continuation.globalVariables());
        payload.ifPresent(value -> variables.put(ProcessInbox.TIMER_RESULT, value));
        return copy(continuation, Optional.empty(), Map.copyOf(variables));
    }

    private static Continuation copy(
            Continuation source,
            Optional<Continuation.WaitState> wait,
            Map<String, Continuation.PersistedValue> globals
    ) {
        return new Continuation(source.programId(), source.programHash(), source.programCounter(),
                source.callStack(), source.scopeStack(), source.exceptionStack(),
                source.controlStack(), wait, globals, source.packageBindings(),
                source.languageVersion(), source.runtimeFormatVersion());
    }

    private static void requireUpdated(ProcessRepository.UpdateResult result) {
        if (result != ProcessRepository.UpdateResult.UPDATED) {
            throw new IllegalStateException("Concurrent process update rejected: " + result);
        }
    }
}
