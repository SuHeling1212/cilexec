package com.follarce.terminal;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.auth.Authorization;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.domain.terminal.TerminalSession;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persists complete terminal inputs, attachments, and process interrupts. */
public final class TerminalService {
    private final UserTransactionExecutor transactions;
    private final Clock clock;

    public TerminalService(UserTransactionExecutor transactions, Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public TerminalSession open(UUID ownerId) {
        Instant now = clock.instant();
        TerminalSession session = new TerminalSession(UUID.randomUUID(), ownerId,
                TerminalSession.Status.OPEN, 1, now, now, Optional.empty());
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.TERMINAL_ATTACH);
            transaction.terminal().saveSession(session);
            transaction.audit().append(audit(ownerId, "terminal.open", session.sessionId(), now));
            return session;
        });
    }

    /** Reuses the durable REPL session so login and Runtime restarts retain context. */
    public TerminalSession openOrResume(UUID ownerId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.TERMINAL_ATTACH);
            return transaction.terminal().findOpenSession(ownerId).orElseGet(() -> {
                Instant now = clock.instant();
                TerminalSession session = new TerminalSession(UUID.randomUUID(), ownerId,
                        TerminalSession.Status.OPEN, 1, now, now, Optional.empty());
                transaction.terminal().saveSession(session);
                transaction.audit().append(audit(ownerId, "terminal.open", session.sessionId(), now));
                return session;
            });
        });
    }

    public TerminalSession.Input submit(UUID ownerId, UUID sessionId, String completeInput) {
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.SERIALIZABLE, transaction -> {
            TerminalSession current = transaction.terminal().findSession(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown terminal session"));
            TerminalSession.Input input = current.commitInput(completeInput, now);
            Optional<TerminalSession.Attachment> attachment =
                    transaction.terminal().findActiveAttachment(sessionId);
            if (attachment.isPresent()) {
                UUID processUid = attachment.orElseThrow().processUid();
                Optional<CilProcess> target = transaction.processes().findByUid(processUid);
                if (target.isPresent() && waitingForInput(target.orElseThrow())) {
                    input = input.accept(processUid, now);
                    CilProcess waiting = target.orElseThrow();
                    Continuation resumed = resumeWithInput(waiting.continuation(), completeInput);
                    CilProcess.Status targetStatus = waiting.status() == CilProcess.Status.PAUSED
                            ? CilProcess.Status.PAUSED : CilProcess.Status.READY;
                    CilProcess ready = waiting.commitStatement(resumed, targetStatus,
                            waiting.stateVersion(), waiting.executionEpoch(), now);
                    requireUpdated(transaction.processes().update(ready, waiting.stateVersion(),
                            waiting.executionEpoch()));
                    if (ready.status() == CilProcess.Status.READY) {
                        transaction.scheduler().enqueue(new SchedulerQueueEntry(processUid, now, now,
                                SchedulerQueueEntry.Status.READY));
                    }
                }
            }
            transaction.terminal().appendInput(input);
            transaction.terminal().saveSession(current.advanceAfter(input));
            return input;
        });
    }

    public TerminalSession.Attachment attach(UUID ownerId, UUID sessionId, long pid) {
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.TERMINAL_ATTACH);
            TerminalSession session = transaction.terminal().findSession(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown terminal session"));
            if (session.status() != TerminalSession.Status.OPEN) {
                throw new IllegalStateException("Terminal is closed");
            }
            CilProcess process = transaction.processes().findByPid(pid)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown PID"));
            TerminalSession.Attachment attachment = new TerminalSession.Attachment(
                    UUID.randomUUID(), sessionId, process.identity().processUid(), now,
                    Optional.empty());
            transaction.terminal().saveAttachment(attachment);
            transaction.audit().append(audit(ownerId, "terminal.attach", sessionId, now));
            return attachment;
        });
    }

    public boolean interrupt(UUID ownerId, long pid) {
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.TERMINAL_ATTACH);
            Optional<CilProcess> process = transaction.processes().findByPid(pid);
            if (process.isEmpty() || process.get().isTerminal()) return false;
            CilProcess current = process.orElseThrow();
            transaction.terminal().requestInterrupt(new TerminalSession.Interrupt(
                    current.identity().processUid(), now, Optional.empty()));
            if (current.status() == CilProcess.Status.READY || isBlocked(current.status())) {
                CilProcess runnable = current;
                if (isBlocked(current.status())) {
                    runnable = current.commitStatement(current.continuation().withoutWait(),
                            CilProcess.Status.READY, current.stateVersion(),
                            current.executionEpoch(), now);
                    requireUpdated(transaction.processes().update(runnable,
                            current.stateVersion(), current.executionEpoch()));
                }
                transaction.scheduler().enqueue(new SchedulerQueueEntry(
                        runnable.identity().processUid(), now, now,
                        SchedulerQueueEntry.Status.READY));
            }
            transaction.audit().append(new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                    ownerId.toString(), "process.interrupt", "process",
                    current.identity().processUid().toString(), AuditEvent.Result.SUCCEEDED,
                    Map.of("pid", Long.toString(pid)), now));
            return true;
        });
    }

    public TerminalSession close(UUID ownerId, UUID sessionId) {
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            TerminalSession current = transaction.terminal().findSession(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown terminal session"));
            TerminalSession closed = current.close(now);
            transaction.terminal().saveSession(closed);
            return closed;
        });
    }

    private static AuditEvent audit(UUID ownerId, String action, UUID sessionId, Instant now) {
        return new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER, ownerId.toString(),
                action, "terminal.session", sessionId.toString(), AuditEvent.Result.SUCCEEDED,
                Map.of(), now);
    }

    private static boolean waitingForInput(CilProcess process) {
        return (process.status() == CilProcess.Status.WAITING_INPUT
                || process.status() == CilProcess.Status.PAUSED)
                && process.continuation().waitState()
                .map(wait -> wait.kind() == Continuation.WaitKind.INPUT)
                .orElse(false);
    }

    private static boolean isBlocked(CilProcess.Status status) {
        return status == CilProcess.Status.WAITING_IPC
                || status == CilProcess.Status.WAITING_TIMER
                || status == CilProcess.Status.WAITING_EFFECT
                || status == CilProcess.Status.WAITING_INPUT
                || status == CilProcess.Status.PAUSED;
    }

    private static Continuation resumeWithInput(Continuation source, String input) {
        Map<String, Continuation.PersistedValue> variables =
                new java.util.LinkedHashMap<>(source.globalVariables());
        variables.put(ProcessInbox.TERMINAL_INPUT, new Continuation.PersistedValue(
                "text/plain;charset=utf-8", input));
        return new Continuation(source.programId(), source.programHash(), source.programCounter(),
                source.callStack(), source.scopeStack(), source.exceptionStack(),
                source.controlStack(), Optional.empty(), Map.copyOf(variables),
                source.packageBindings(), source.languageVersion(), source.runtimeFormatVersion());
    }

    private static void requireUpdated(ProcessRepository.UpdateResult result) {
        if (result != ProcessRepository.UpdateResult.UPDATED) {
            throw new IllegalStateException("Concurrent terminal wake rejected: " + result);
        }
    }
}
