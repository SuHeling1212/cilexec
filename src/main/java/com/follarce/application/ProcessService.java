package com.follarce.application;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.auth.Authorization;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.program.Program;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Process lifecycle use cases, each completed in one explicit database transaction. */
public final class ProcessService {
    private final UserTransactionExecutor transactions;

    public ProcessService(UserTransactionExecutor transactions) {
        this.transactions = transactions;
    }

    public CilProcess create(UUID ownerId, Program program, Optional<UUID> parentProcessUid) {
        Instant now = Instant.now();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.PROCESS_CREATE);
            Program stored = transaction.programs().findById(program.programId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown program"));
            long pid = transaction.processes().allocatePid();
            Continuation continuation = initialContinuation(stored);
            CilProcess process = new CilProcess(new ProcessIdentity(UUID.randomUUID(), pid), ownerId,
                    CilProcess.Status.READY, 0, 0, continuation, parentProcessUid, now, now);
            transaction.processes().insert(process);
            transaction.scheduler().enqueue(new SchedulerQueueEntry(process.identity().processUid(),
                    now, now, SchedulerQueueEntry.Status.READY));
            transaction.audit().append(audit(ownerId, "process.create", process, now));
            return process;
        });
    }

    public CilProcess fork(UUID ownerId, long parentPid) {
        Instant now = Instant.now();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.PROCESS_CREATE);
            CilProcess parent = transaction.processes().findByPid(parentPid)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown parent PID " + parentPid));
            long pid = transaction.processes().allocatePid();
            CilProcess child = new CilProcess(new ProcessIdentity(UUID.randomUUID(), pid), ownerId,
                    CilProcess.Status.READY, 0, 0, forkContinuation(parent.continuation()),
                    Optional.of(parent.identity().processUid()), now, now);
            transaction.processes().insert(child);
            transaction.scheduler().enqueue(new SchedulerQueueEntry(child.identity().processUid(),
                    now, now, SchedulerQueueEntry.Status.READY));
            transaction.audit().append(audit(ownerId, "process.fork", child, now));
            return child;
        });
    }

    public CilProcess pause(UUID ownerId, long pid) {
        Instant requestedAt = Instant.now();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.PROCESS_CONTROL_OWN);
            CilProcess current = transaction.processes().findByPid(pid)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown PID " + pid));
            Instant now = notBefore(requestedAt, current.updatedAt());
            CilProcess paused = current.transitionTo(CilProcess.Status.PAUSED, now);
            requireUpdated(transaction.processes().update(paused,
                    current.stateVersion(), current.executionEpoch()));
            transaction.scheduler().release(current.identity().processUid(),
                    current.executionEpoch());
            transaction.audit().append(audit(ownerId, "process.pause", paused, now));
            return paused;
        });
    }

    public CilProcess resume(UUID ownerId, long pid) {
        Instant requestedAt = Instant.now();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.PROCESS_CONTROL_OWN);
            CilProcess current = transaction.processes().findByPid(pid)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown PID " + pid));
            Instant now = notBefore(requestedAt, current.updatedAt());
            CilProcess.Status target = CilProcess.statusFor(current.continuation().waitState());
            CilProcess resumed = current.transitionTo(target, now);
            requireUpdated(transaction.processes().update(resumed,
                    current.stateVersion(), current.executionEpoch()));
            if (target == CilProcess.Status.READY) {
                transaction.scheduler().enqueue(new SchedulerQueueEntry(
                        resumed.identity().processUid(), now, now,
                        SchedulerQueueEntry.Status.READY));
            }
            transaction.audit().append(audit(ownerId, "process.resume", resumed, now));
            return resumed;
        });
    }

    public CilProcess terminate(UUID ownerId, long pid) {
        Instant requestedAt = Instant.now();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.PROCESS_CONTROL_OWN);
            CilProcess current = transaction.processes().findByPid(pid)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown PID " + pid));
            Instant now = notBefore(requestedAt, current.updatedAt());
            Continuation stoppedContinuation = current.continuation()
                    .withoutWait().withoutTransientInbox();
            CilProcess terminating = current.commitStatement(stoppedContinuation,
                    CilProcess.Status.TERMINATING, current.stateVersion(),
                    current.executionEpoch(), now);
            requireUpdated(transaction.processes().update(terminating,
                    current.stateVersion(), current.executionEpoch()));
            CilProcess terminated = terminating.transitionTo(CilProcess.Status.TERMINATED, now);
            requireUpdated(transaction.processes().update(terminated,
                    terminating.stateVersion(), terminating.executionEpoch()));
            transaction.scheduler().release(current.identity().processUid(), current.executionEpoch());
            transaction.audit().append(audit(ownerId, "process.terminate", terminated, now));
            return terminated;
        });
    }

    private static Continuation initialContinuation(Program program) {
        return new Continuation(program.programId(), program.programHash(), 0,
                List.of(), List.of(), List.of(), List.of(), Optional.empty(), Map.of(), Map.of(),
                program.languageVersion(), Integer.toString(program.runtimeFormatVersion()));
    }

    private static Continuation forkContinuation(Continuation source) {
        Map<String, Continuation.PersistedValue> globals =
                new java.util.LinkedHashMap<>(source.globalVariables());
        ProcessInbox.keys().forEach(globals::remove);
        return new Continuation(source.programId(), source.programHash(), source.programCounter(),
                source.callStack(), source.scopeStack(), source.exceptionStack(),
                source.controlStack(), Optional.empty(), Map.copyOf(globals),
                source.packageBindings(), source.languageVersion(), source.runtimeFormatVersion());
    }

    private static AuditEvent audit(UUID owner, String action, CilProcess process, Instant at) {
        return new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER, owner.toString(),
                action, "process", process.identity().processUid().toString(),
                AuditEvent.Result.SUCCEEDED,
                Map.of("pid", Long.toString(process.identity().pid()),
                        "status", process.status().name()), at);
    }

    private static void requireUpdated(ProcessRepository.UpdateResult result) {
        if (result != ProcessRepository.UpdateResult.UPDATED) {
            throw new IllegalStateException("Concurrent process update rejected: " + result);
        }
    }

    private static Instant notBefore(Instant requested, Instant current) {
        return requested.isBefore(current) ? current : requested;
    }
}
