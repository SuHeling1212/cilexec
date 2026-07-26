package com.follarce.effect;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.audit.AuditRetentionPolicy;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.effect.EffectAttempt;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.port.AuditRepository;
import com.follarce.domain.port.AuthRepository;
import com.follarce.domain.port.EffectRepository;
import com.follarce.domain.port.IpcRepository;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.ProgramRepository;
import com.follarce.domain.port.SchedulerRepository;
import com.follarce.domain.port.TerminalRepository;
import com.follarce.domain.port.TimerRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.port.TransactionWork;
import com.follarce.domain.port.VfsRepository;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.scheduler.SchedulerClaim;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-22T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void manualSuccessCompletesEffectAndAtomicallyReadiesWaitingProcess() {
        Fixture fixture = new Fixture(CilProcess.Status.WAITING_EFFECT, manualPolicy());
        Continuation.PersistedValue result = value("json", "{\"accepted\":true}");

        EffectRequest completed = fixture.service.resolveUnknownSuccess(
                fixture.ownerId, fixture.effect.effectId(), result);

        assertEquals(EffectRequest.Status.COMPLETED, completed.status());
        assertEquals(completed, fixture.persistence.effects.effect);
        assertEquals(CilProcess.Status.READY, fixture.persistence.processes.process.status());
        assertTrue(fixture.persistence.processes.process.continuation().waitState().isEmpty());
        assertEquals(result, fixture.persistence.processes.process.continuation()
                .globalVariables().get(ProcessInbox.EFFECT_RESULT));
        assertEquals(1, fixture.persistence.scheduler.enqueued.size());
        assertEquals(1, fixture.persistence.transactions);
        assertEquals("effect.resolve.success",
                fixture.persistence.audit.events.getFirst().action());
    }

    @Test
    void manualFailureDeliversStableErrorButPreservesAdministrativePause() {
        Fixture fixture = new Fixture(CilProcess.Status.PAUSED, manualPolicy());

        EffectRequest failed = fixture.service.resolveUnknownFailure(
                fixture.ownerId, fixture.effect.effectId(), "operator confirmed rejection");

        assertEquals(EffectRequest.Status.FAILED, failed.status());
        assertEquals(CilProcess.Status.PAUSED, fixture.persistence.processes.process.status());
        assertTrue(fixture.persistence.processes.process.continuation().waitState().isEmpty());
        Continuation.PersistedValue delivery = fixture.persistence.processes.process
                .continuation().globalVariables().get(ProcessInbox.EFFECT_RESULT);
        assertEquals("error", delivery.type());
        assertEquals("{\"code\":\"MANUAL_EFFECT_FAILURE\",\"effectId\":\""
                        + fixture.effect.effectId()
                        + "\",\"message\":\"operator confirmed rejection\"}",
                delivery.canonicalPayload());
        assertTrue(fixture.persistence.scheduler.enqueued.isEmpty());
        assertEquals("effect.resolve.failure",
                fixture.persistence.audit.events.getFirst().action());
    }

    @Test
    void automaticUnknownPolicyCannotBeResolvedThroughManualApi() {
        EffectRequest.Policy query = new EffectRequest.Policy(false, Optional.empty(), true,
                false, EffectRequest.UnknownAction.QUERY_REMOTE);
        Fixture fixture = new Fixture(CilProcess.Status.WAITING_EFFECT, query);

        assertThrows(IllegalStateException.class, () -> fixture.service.resolveUnknownFailure(
                fixture.ownerId, fixture.effect.effectId(), "not a manual effect"));

        assertEquals(EffectRequest.Status.UNKNOWN, fixture.persistence.effects.effect.status());
        assertEquals(CilProcess.Status.WAITING_EFFECT,
                fixture.persistence.processes.process.status());
        assertTrue(fixture.persistence.audit.events.isEmpty());
    }

    private static EffectRequest.Policy manualPolicy() {
        return new EffectRequest.Policy(false, Optional.empty(), false, false,
                EffectRequest.UnknownAction.MANUAL);
    }

    private static Continuation.PersistedValue value(String type, String payload) {
        return new Continuation.PersistedValue(type, payload);
    }

    private static final class Fixture {
        final UUID ownerId = UUID.randomUUID();
        final EffectRequest effect;
        final MemoryPersistence persistence;
        final EffectService service;

        Fixture(CilProcess.Status processStatus, EffectRequest.Policy policy) {
            UUID processUid = UUID.randomUUID();
            effect = EffectRequest.prepare(UUID.randomUUID(), processUid, "test.effect",
                            value("json", "{\"request\":true}"), policy,
                            NOW.minusSeconds(4))
                    .claim(UUID.randomUUID(), NOW.minusSeconds(3))
                    .start(NOW.minusSeconds(2))
                    .unknown("runtime stopped", NOW.minusSeconds(1));
            Continuation continuation = continuation(effect.effectId());
            CilProcess process = new CilProcess(new ProcessIdentity(processUid, 17), ownerId,
                    processStatus, 4, 9, continuation, Optional.empty(),
                    NOW.minusSeconds(10), NOW.minusSeconds(1));
            persistence = new MemoryPersistence(effect, process);
            service = new EffectService(persistence, new EffectHandlerRegistry(List.of()), CLOCK);
        }

        private static Continuation continuation(UUID effectId) {
            ObjectHash hash = ObjectHash.sha256(new BinaryContent(
                    "effect-service".getBytes(StandardCharsets.UTF_8)));
            return new Continuation(UUID.randomUUID(), hash, 3, List.of(), List.of(), List.of(),
                    List.of(), Optional.of(new Continuation.WaitState(
                    Continuation.WaitKind.EFFECT, Optional.of(effectId), Optional.empty())),
                    Map.of(), Map.of(), "fcl-1", "1");
        }
    }

    private static final class MemoryPersistence
            implements UserTransactionExecutor, TransactionContext {
        final MemoryEffects effects;
        final MemoryProcesses processes;
        final MemoryScheduler scheduler = new MemoryScheduler();
        final MemoryAuth auth = new MemoryAuth();
        final MemoryAudit audit = new MemoryAudit();
        int transactions;

        MemoryPersistence(EffectRequest effect, CilProcess process) {
            effects = new MemoryEffects(effect);
            processes = new MemoryProcesses(process);
        }

        @Override
        public <T> T inUserTransaction(UUID userId, Isolation isolation,
                                       TransactionWork<T> work) {
            transactions++;
            return work.execute(this);
        }

        @Override public ProgramRepository programs() { return null; }
        @Override public ProcessRepository processes() { return processes; }
        @Override public SchedulerRepository scheduler() { return scheduler; }
        @Override public IpcRepository ipc() { return null; }
        @Override public TimerRepository timers() { return null; }
        @Override public VfsRepository vfs() { return null; }
        @Override public PackageRepository packages() { return null; }
        @Override public EffectRepository effects() { return effects; }
        @Override public AuthRepository auth() { return auth; }
        @Override public AuditRepository audit() { return audit; }
        @Override public TerminalRepository terminal() { return null; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public void close() { }
    }

    private static final class MemoryEffects implements EffectRepository {
        EffectRequest effect;

        MemoryEffects(EffectRequest effect) {
            this.effect = effect;
        }

        @Override public void registerWorker(UUID workerId, UUID bootId, Instant now) { }
        @Override public void save(EffectRequest effect) { this.effect = effect; }
        @Override public Optional<EffectRequest> findById(UUID effectId) {
            return effect.effectId().equals(effectId) ? Optional.of(effect) : Optional.empty();
        }
        @Override public List<EffectRequest> claimPending(UUID workerId, Instant now, int limit) {
            return List.of();
        }
        @Override public boolean update(EffectRequest changed,
                                        EffectRequest.Status expectedStatus) {
            if (effect.status() != expectedStatus) return false;
            effect = changed;
            return true;
        }
        @Override public int nextAttemptNumber(UUID effectId) { return 1; }
        @Override public void saveAttempt(EffectAttempt attempt) { }
        @Override public Optional<EffectAttempt> findAttempt(UUID attemptId) {
            return Optional.empty();
        }
        @Override public List<EffectAttempt> findAttempts(UUID effectId) { return List.of(); }
        @Override public boolean updateAttempt(EffectAttempt attempt,
                                               EffectAttempt.Status expectedStatus) {
            return false;
        }
    }

    private static final class MemoryProcesses implements ProcessRepository {
        CilProcess process;

        MemoryProcesses(CilProcess process) {
            this.process = process;
        }

        @Override public long allocatePid() { throw new UnsupportedOperationException(); }
        @Override public Optional<CilProcess> findByUid(UUID processUid) {
            return process.identity().processUid().equals(processUid)
                    ? Optional.of(process) : Optional.empty();
        }
        @Override public Optional<CilProcess> findByPid(long pid) { return Optional.empty(); }
        @Override public void insert(CilProcess process) { this.process = process; }
        @Override public UpdateResult update(CilProcess changed, long expectedStateVersion,
                                             long expectedExecutionEpoch) {
            if (!process.acceptsCommit(expectedStateVersion, expectedExecutionEpoch)) {
                return UpdateResult.VERSION_CONFLICT;
            }
            process = changed;
            return UpdateResult.UPDATED;
        }
        @Override public UpdateResult updateClaimed(CilProcess changed,
                long expectedStateVersion, SchedulerClaim claim) {
            return update(changed, expectedStateVersion, claim.executionEpoch());
        }
    }

    private static final class MemoryScheduler implements SchedulerRepository {
        final List<SchedulerQueueEntry> enqueued = new ArrayList<>();
        @Override public void enqueue(SchedulerQueueEntry entry) { enqueued.add(entry); }
        @Override public Optional<SchedulerClaim> claimNext(UUID runnerId, UUID bootId, Instant now,
                Duration leaseDuration) { return Optional.empty(); }
        @Override public boolean heartbeat(SchedulerClaim claim) { return false; }
        @Override public void release(UUID processUid, long executionEpoch) { }
        @Override public int releaseExpired(Instant now) { return 0; }
    }

    private static final class MemoryAuth implements AuthRepository {
        @Override public Optional<UserAccount> findUser(UUID userId) { return Optional.empty(); }
        @Override public Optional<UserAccount> findUser(String username) { return Optional.empty(); }
        @Override public void saveUser(UserAccount user) { }
        @Override public String provisionPrincipal(UUID userId, char[] password) {
            throw new UnsupportedOperationException();
        }
        @Override public void disablePrincipal(UUID userId) { }
        @Override public Set<Capability> capabilities(UUID userId) {
            return EnumSet.of(Capability.EFFECT_REQUEST);
        }
        @Override public void replaceCapabilities(UUID userId, Set<Capability> capabilities) { }
    }

    private static final class MemoryAudit implements AuditRepository {
        final List<AuditEvent> events = new ArrayList<>();
        @Override public void append(AuditEvent event) { events.add(event); }
        @Override public List<AuditEvent> findByResource(String resourceType, String resourceId,
                                                        int limit) { return List.copyOf(events); }
        @Override public void saveRetentionPolicy(AuditRetentionPolicy policy) { }
        @Override public Optional<AuditRetentionPolicy> findRetentionPolicy(String eventType) {
            return Optional.empty();
        }
        @Override public int purgeExpired(int limit) { return 0; }
    }
}
