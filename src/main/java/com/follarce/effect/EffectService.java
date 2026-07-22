package com.follarce.effect;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.effect.EffectPayload;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.auth.Authorization;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;
import com.google.gson.JsonObject;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Creates an effect journal row and process wait state in one statement transaction. */
public final class EffectService {
    private final UserTransactionExecutor transactions;
    private final EffectHandlerRegistry handlers;
    private final Clock clock;

    public EffectService(UserTransactionExecutor transactions,
                         EffectHandlerRegistry handlers,
                         Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.handlers = java.util.Objects.requireNonNull(handlers, "handlers");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public EffectRequest request(UUID ownerId, UUID processUid, String effectType,
                                 Continuation.PersistedValue request,
                                 EffectRequest.Policy policy) {
        return request(ownerId, processUid, effectType, EffectPayload.json(request), policy);
    }

    public EffectRequest requestObject(UUID ownerId, UUID processUid, String effectType,
                                       ObjectHash requestObjectHash,
                                       EffectRequest.Policy policy) {
        return request(ownerId, processUid, effectType, EffectPayload.object(requestObjectHash),
                policy);
    }

    public EffectRequest request(UUID ownerId, UUID processUid, String effectType,
                                 EffectPayload request, EffectRequest.Policy policy) {
        handlers.require(effectType);
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.EFFECT_REQUEST);
            CilProcess current = transaction.processes().findByUid(processUid)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown process"));
            if (current.status() != CilProcess.Status.RUNNING) {
                throw new IllegalStateException("Only a RUNNING process may request an effect");
            }
            EffectRequest effect = EffectRequest.prepare(UUID.randomUUID(), processUid,
                    effectType, request, policy, now);
            Continuation waitingContinuation = withWait(current.continuation(), effect.effectId());
            CilProcess waiting = current.commitStatement(waitingContinuation,
                    CilProcess.Status.WAITING_EFFECT, current.stateVersion(),
                    current.executionEpoch(), now);
            transaction.effects().save(effect);
            requireUpdated(transaction.processes().update(waiting, current.stateVersion(),
                    current.executionEpoch()));
            transaction.scheduler().release(processUid, current.executionEpoch());
            return effect;
        });
    }

    public EffectRequest resolveUnknownSuccess(
            UUID ownerId,
            UUID effectId,
            Continuation.PersistedValue result
    ) {
        return resolveUnknownSuccess(ownerId, effectId, EffectPayload.json(result));
    }

    public EffectRequest resolveUnknownSuccessObject(
            UUID ownerId,
            UUID effectId,
            ObjectHash resultObjectHash
    ) {
        return resolveUnknownSuccess(ownerId, effectId, EffectPayload.object(resultObjectHash));
    }

    public EffectRequest resolveUnknownSuccess(
            UUID ownerId,
            UUID effectId,
            EffectPayload result
    ) {
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.EFFECT_REQUEST);
            EffectRequest unknown = requireEffect(transaction, effectId);
            EffectRequest completed = unknown.resolveUnknownSuccess(result, now);
            requireEffectUpdated(transaction.effects().resolveUnknownManually(completed));
            settleWaitingProcess(transaction, completed, result.deliveryValue(), now);
            transaction.audit().append(manualAudit(ownerId, completed, "effect.resolve.success",
                    now));
            return completed;
        });
    }

    public EffectRequest resolveUnknownFailure(UUID ownerId, UUID effectId, String reason) {
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.EFFECT_REQUEST);
            EffectRequest unknown = requireEffect(transaction, effectId);
            EffectRequest failed = unknown.resolveUnknownFailure(reason, now);
            requireEffectUpdated(transaction.effects().resolveUnknownManually(failed));
            settleWaitingProcess(transaction, failed, manualFailure(failed, reason), now);
            transaction.audit().append(manualAudit(ownerId, failed, "effect.resolve.failure",
                    now));
            return failed;
        });
    }

    private static Continuation withWait(Continuation source, UUID effectId) {
        return new Continuation(source.programId(), source.programHash(), source.programCounter(),
                source.callStack(), source.scopeStack(), source.exceptionStack(),
                source.controlStack(), Optional.of(new Continuation.WaitState(
                        Continuation.WaitKind.EFFECT, Optional.of(effectId), Optional.empty())),
                source.globalVariables(), source.packageBindings(), source.languageVersion(),
                source.runtimeFormatVersion());
    }

    private static EffectRequest requireEffect(
            com.follarce.domain.port.TransactionContext transaction,
            UUID effectId
    ) {
        return transaction.effects().findById(effectId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown effect " + effectId));
    }

    private static void settleWaitingProcess(
            com.follarce.domain.port.TransactionContext transaction,
            EffectRequest effect,
            Continuation.PersistedValue delivery,
            Instant now
    ) {
        Optional<CilProcess> loaded = transaction.processes().findByUid(effect.processUid());
        if (loaded.isEmpty() || !isWaitingFor(loaded.orElseThrow(), effect.effectId())) return;

        CilProcess current = loaded.orElseThrow();
        Map<String, Continuation.PersistedValue> variables =
                new LinkedHashMap<>(current.continuation().globalVariables());
        variables.put(ProcessInbox.EFFECT_RESULT, delivery);
        Continuation resumed = current.continuation().withoutWait()
                .withGlobalVariables(Map.copyOf(variables));
        CilProcess.Status target = current.status() == CilProcess.Status.PAUSED
                ? CilProcess.Status.PAUSED : CilProcess.Status.READY;
        CilProcess settled = current.commitStatement(resumed, target, current.stateVersion(),
                current.executionEpoch(), now);
        requireUpdated(transaction.processes().update(settled, current.stateVersion(),
                current.executionEpoch()));
        if (settled.status() == CilProcess.Status.READY) {
            transaction.scheduler().enqueue(new SchedulerQueueEntry(effect.processUid(), now, now,
                    SchedulerQueueEntry.Status.READY));
        }
    }

    private static boolean isWaitingFor(CilProcess process, UUID effectId) {
        return (process.status() == CilProcess.Status.WAITING_EFFECT
                || process.status() == CilProcess.Status.PAUSED)
                && process.continuation().waitState()
                .filter(wait -> wait.kind() == Continuation.WaitKind.EFFECT)
                .flatMap(Continuation.WaitState::targetId)
                .map(effectId::equals)
                .orElse(false);
    }

    private static Continuation.PersistedValue manualFailure(
            EffectRequest effect,
            String reason
    ) {
        JsonObject error = new JsonObject();
        error.addProperty("code", "MANUAL_EFFECT_FAILURE");
        error.addProperty("effectId", effect.effectId().toString());
        error.addProperty("message", reason);
        return new Continuation.PersistedValue("error", error.toString());
    }

    private static AuditEvent manualAudit(UUID ownerId, EffectRequest effect, String action,
                                          Instant now) {
        return new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER, ownerId.toString(),
                action, "effect.effect", effect.effectId().toString(),
                AuditEvent.Result.SUCCEEDED,
                Map.of("processUid", effect.processUid().toString(),
                        "status", effect.status().name()), now);
    }

    private static void requireEffectUpdated(boolean updated) {
        if (!updated) {
            throw new IllegalStateException("Concurrent effect resolution was rejected");
        }
    }

    private static void requireUpdated(ProcessRepository.UpdateResult result) {
        if (result != ProcessRepository.UpdateResult.UPDATED) {
            throw new IllegalStateException("Concurrent process update rejected: " + result);
        }
    }
}
