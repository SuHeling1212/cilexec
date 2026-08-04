package com.follarce.app;

import com.follarce.domain.effect.EffectAttempt;
import com.follarce.domain.effect.EffectPayload;
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
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.port.TransactionWork;
import com.follarce.domain.port.VfsRepository;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.domain.vfs.ObjectHash;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliverySweeperTest {
    private static final Instant NOW = Instant.parse("2026-07-22T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void redeliversCompletedEffectsAndReclaimsStalePrepared() {
        UUID processUid = UUID.randomUUID();
        UUID effectId = UUID.randomUUID();
        EffectRequest completed = completed(processUid, effectId, "{\"ok\":true}");
        EffectRequest reclaimed = reclaimed(processUid, effectId);
        FakePersistence persistence = new FakePersistence(processUid, effectId, completed, reclaimed);
        DeliverySweeper sweeper = new DeliverySweeper(persistence, CLOCK);

        int repaired = sweeper.sweepOnce();

        assertTrue(repaired >= 2, "completed redelivery + prepared reclamation, got " + repaired);
        assertEquals(2, persistence.processes.wakes.get());
        assertEquals(2, persistence.scheduler.enqueues.get());
        assertEquals(1, persistence.scheduler.staleAnnounced.get());
        assertTrue(persistence.effects.completedButUndeliveredQueried);
        assertTrue(persistence.effects.reclaimStalePreparedQueried);
    }

    @Test
    void neverWakesAProcessThatIsNoLongerWaiting() {
        UUID processUid = UUID.randomUUID();
        UUID effectId = UUID.randomUUID();
        EffectRequest completed = completed(processUid, effectId, "{\"ok\":true}");
        FakePersistence persistence = new FakePersistence(processUid, effectId, completed, null);
        persistence.processes.waiting = false;
        DeliverySweeper sweeper = new DeliverySweeper(persistence, CLOCK);

        int repaired = sweeper.sweepOnce();

        assertEquals(0, persistence.processes.wakes.get(),
                "a process that stopped waiting must not be woken");
        assertEquals(0, persistence.scheduler.enqueues.get());
    }

    private static EffectRequest completed(UUID processUid, UUID effectId, String payload) {
        EffectRequest effect = EffectRequest.prepare(effectId, processUid,
                "test.effect", value("json", payload), manualPolicy(), NOW.minusSeconds(30));
        return effect.claim(UUID.randomUUID(), NOW.minusSeconds(30))
                .start(NOW.minusSeconds(30))
                .complete(EffectPayload.json(value("json", payload)), NOW.minusSeconds(20));
    }

    private static EffectRequest reclaimed(UUID processUid, UUID effectId) {
        EffectRequest effect = EffectRequest.prepare(effectId, processUid,
                "test.effect", value("json", "{\"request\":true}"), manualPolicy(),
                NOW.minusSeconds(120));
        return effect.claim(UUID.randomUUID(), NOW.minusSeconds(120))
                .start(NOW.minusSeconds(120))
                .fail("Prepared effect reclaimed after timeout", NOW.minusSeconds(1));
    }

    private static Continuation.PersistedValue value(String type, String payload) {
        return new Continuation.PersistedValue(type, payload);
    }

    private static EffectRequest.Policy manualPolicy() {
        return new EffectRequest.Policy(false, Optional.empty(), false, false,
                EffectRequest.UnknownAction.MANUAL);
    }

    private static final class FakePersistence implements TransactionExecutor, TransactionContext {
        final FakeEffects effects;
        final FakeProcesses processes;
        final FakeScheduler scheduler = new FakeScheduler();

        FakePersistence(UUID processUid, UUID effectId, EffectRequest completed,
                         EffectRequest reclaimed) {
            this.effects = new FakeEffects(completed, reclaimed);
            this.processes = new FakeProcesses(processUid, effectId);
        }

        @Override
        public synchronized <T> T inTransaction(Isolation isolation, TransactionWork<T> work) {
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
        @Override public AuthRepository auth() { return null; }
        @Override public AuditRepository audit() { return null; }
        @Override public TerminalRepository terminal() { return null; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public void close() { }
    }

    private static final class FakeEffects implements EffectRepository {
        final EffectRequest completed;
        final EffectRequest reclaimed;
        boolean completedButUndeliveredQueried;
        boolean reclaimStalePreparedQueried;

        FakeEffects(EffectRequest completed, EffectRequest reclaimed) {
            this.completed = completed;
            this.reclaimed = reclaimed;
        }

        @Override public void registerWorker(UUID workerId, UUID bootId, Instant now) { }
        @Override public void save(EffectRequest effect) { }
        @Override public Optional<EffectRequest> findById(UUID effectId) { return Optional.empty(); }
        @Override public List<EffectRequest> claimPending(UUID workerId, Instant now, int limit) {
            return List.of();
        }
        @Override public boolean update(EffectRequest effect, EffectRequest.Status expectedStatus) {
            return true;
        }
        @Override public List<EffectRequest> reclaimStalePrepared(Instant now, long timeoutMillis,
                                                                  int limit) {
            reclaimStalePreparedQueried = true;
            return reclaimed == null ? List.of() : List.of(reclaimed);
        }
        @Override public List<EffectRequest> completedButUndelivered(Instant now, long graceMillis,
                                                                     int limit) {
            completedButUndeliveredQueried = true;
            return completed == null ? List.of() : List.of(completed);
        }
        @Override public int nextAttemptNumber(UUID effectId) { return 1; }
        @Override public void saveAttempt(EffectAttempt attempt) { }
        @Override public Optional<EffectAttempt> findAttempt(UUID attemptId) {
            return Optional.empty();
        }
        @Override public List<EffectAttempt> findAttempts(UUID effectId) { return List.of(); }
        @Override public boolean updateAttempt(EffectAttempt attempt,
                                               EffectAttempt.Status expectedStatus) {
            return true;
        }
    }

    private static final class FakeProcesses implements ProcessRepository {
        final UUID processUid;
        final AtomicInteger wakes = new AtomicInteger();
        final CilProcess waitingProcess;
        final CilProcess readyProcess;
        volatile boolean waiting = true;

        FakeProcesses(UUID processUid, UUID effectId) {
            this.processUid = processUid;
            waitingProcess = process(processUid, effectId, CilProcess.Status.WAITING_EFFECT);
            readyProcess = process(processUid, effectId, CilProcess.Status.READY);
        }

        private static CilProcess process(UUID processUid, UUID effectId,
                                          CilProcess.Status status) {
            Continuation continuation = new Continuation(UUID.randomUUID(),
                    new ObjectHash("0".repeat(64)), 0, List.of(), List.of(), List.of(),
                    List.of(), Optional.of(new Continuation.WaitState(
                    Continuation.WaitKind.EFFECT, Optional.of(effectId),
                    Optional.empty())), Map.of(), Map.of(), "1", "1");
            return new CilProcess(new ProcessIdentity(processUid, 1), UUID.randomUUID(),
                    status, 0, 0, continuation, Optional.empty(),
                    NOW.minusSeconds(120), NOW.minusSeconds(120));
        }

        @Override public long allocatePid() { throw new UnsupportedOperationException(); }
        @Override public Optional<CilProcess> findByUid(UUID processUid) {
            return Optional.of(waiting ? waitingProcess : readyProcess);
        }
        @Override public Optional<CilProcess> findByPid(long pid) { return Optional.empty(); }
        @Override public void insert(CilProcess process) { throw new UnsupportedOperationException(); }
        @Override public UpdateResult update(CilProcess process, long state, long epoch) {
            wakes.incrementAndGet();
            return UpdateResult.UPDATED;
        }
        @Override public UpdateResult updateClaimed(CilProcess process, long state,
                com.follarce.domain.scheduler.SchedulerClaim claim) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeScheduler implements SchedulerRepository {
        final AtomicInteger enqueues = new AtomicInteger();
        final AtomicInteger staleAnnounced = new AtomicInteger();

        @Override public void enqueue(SchedulerQueueEntry entry) { enqueues.incrementAndGet(); }
        @Override public Optional<com.follarce.domain.scheduler.SchedulerClaim> claimNext(
                UUID runnerId, UUID bootId, Instant now, Duration leaseDuration) {
            return Optional.empty();
        }
        @Override public void release(UUID processUid, long executionEpoch) { }
        @Override public int releaseExpired(Instant now) { return 0; }
        @Override public boolean heartbeat(com.follarce.domain.scheduler.SchedulerClaim claim) {
            return true;
        }
        @Override public int requeueStale(Instant now, long staleAgeMillis) {
            staleAnnounced.incrementAndGet();
            return 1;
        }
    }
}
