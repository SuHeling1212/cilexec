package com.follarce.effect;

import com.follarce.domain.effect.EffectAttempt;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
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
import com.follarce.domain.vfs.ObjectHash;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectWorkerServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-22T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void registersRunnerAndCommitsAttemptAlongsideSuccessfulEffect() throws Exception {
        FakePersistence persistence = new FakePersistence(prepared(manualPolicy()));
        UUID bootId = UUID.randomUUID();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        AtomicInteger schedulerWakes = new AtomicInteger();
        EffectHandler handler = handler(request -> value("json", "{\"ok\":true}"));

        try (EffectWorkerService workers = new EffectWorkerService(
                persistence, persistence, bootId, new EffectHandlerRegistry(List.of(handler)),
                1, Duration.ofMillis(1), CLOCK, fatal::set, schedulerWakes::incrementAndGet)) {
            workers.start();
            assertTrue(persistence.completed.await(3, TimeUnit.SECONDS));
        }

        assertEquals(bootId, persistence.effects.bootId);
        assertEquals(EffectRequest.Status.COMPLETED, persistence.effects.effect.status());
        assertEquals(1, persistence.effects.attempts.size());
        assertEquals(EffectAttempt.Status.SUCCEEDED,
                persistence.effects.attempts.getFirst().status());
        assertNull(fatal.get());
        assertEquals(1, schedulerWakes.get());
    }

    @Test
    void givesAnIdempotentRetryItsOwnDurableAttempt() throws Exception {
        FakePersistence persistence = new FakePersistence(prepared(retryPolicy()));
        AtomicInteger invocations = new AtomicInteger();
        EffectHandler handler = handler(request -> {
            if (invocations.getAndIncrement() == 0) {
                throw new EffectOutcomeUnknownException("connection lost after send");
            }
            return value("text/plain", "accepted");
        });

        try (EffectWorkerService workers = new EffectWorkerService(
                persistence, persistence, UUID.randomUUID(),
                new EffectHandlerRegistry(List.of(handler)), 1, Duration.ofMillis(1), CLOCK,
                failure -> { })) {
            workers.start();
            assertTrue(persistence.completed.await(3, TimeUnit.SECONDS));
        }

        assertEquals(2, invocations.get());
        assertEquals(List.of(EffectAttempt.Status.UNKNOWN, EffectAttempt.Status.SUCCEEDED),
                persistence.effects.attempts.stream().map(EffectAttempt::status).toList());
        assertEquals(List.of(1, 2), persistence.effects.attempts.stream()
                .map(EffectAttempt::attemptNumber).toList());
    }

    @Test
    void recoveredQueryableUnknownQueriesWithoutReexecutingTheEffect() throws Exception {
        FakePersistence persistence = new FakePersistence(unknown(queryPolicy()));
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        EffectHandler handler = new EffectHandler() {
            @Override public String effectType() { return "test.effect"; }

            @Override
            public Continuation.PersistedValue execute(
                    Continuation.PersistedValue request,
                    Optional<String> idempotencyKey
            ) {
                executions.incrementAndGet();
                throw new AssertionError("QUERY_REMOTE must not execute the effect again");
            }

            @Override
            public Optional<Continuation.PersistedValue> queryOutcome(EffectRequest request) {
                queries.incrementAndGet();
                return Optional.of(value("text/plain", "already-completed"));
            }
        };

        try (EffectWorkerService workers = workers(persistence, handler)) {
            workers.start();
            assertTrue(persistence.completed.await(3, TimeUnit.SECONDS));
        }

        assertEquals(0, executions.get());
        assertEquals(1, queries.get());
        assertEquals(EffectRequest.Status.COMPLETED, persistence.effects.effect.status());
        assertTrue(persistence.effects.attempts.isEmpty());
    }

    @Test
    void recoveredRetryableUnknownCreatesOneNewAttempt() throws Exception {
        EffectRequest unknown = unknown(retryPolicy());
        FakePersistence persistence = new FakePersistence(unknown);
        UUID oldWorker = UUID.randomUUID();
        persistence.effects.attempts.add(EffectAttempt.claim(unknown.effectId(), 1, oldWorker,
                NOW.minusSeconds(2)).start().unknown("RUNTIME_CRASH", "lost", NOW.minusSeconds(1)));
        AtomicInteger executions = new AtomicInteger();
        EffectHandler handler = handler(request -> {
            executions.incrementAndGet();
            return value("text/plain", "retried");
        });

        try (EffectWorkerService workers = workers(persistence, handler)) {
            workers.start();
            assertTrue(persistence.completed.await(3, TimeUnit.SECONDS));
        }

        assertEquals(1, executions.get());
        assertEquals(List.of(EffectAttempt.Status.UNKNOWN, EffectAttempt.Status.SUCCEEDED),
                persistence.effects.attempts.stream().map(EffectAttempt::status).toList());
        assertEquals(List.of(1, 2), persistence.effects.attempts.stream()
                .map(EffectAttempt::attemptNumber).toList());
    }

    @Test
    void manualUnknownIsNeverClaimedForAutomaticRecovery() throws Exception {
        FakePersistence persistence = new FakePersistence(unknown(manualPolicy()));
        AtomicInteger executions = new AtomicInteger();
        EffectHandler handler = handler(request -> {
            executions.incrementAndGet();
            return value("text/plain", "must-not-run");
        });

        try (EffectWorkerService workers = workers(persistence, handler)) {
            workers.start();
            assertFalse(persistence.completed.await(100, TimeUnit.MILLISECONDS));
        }

        assertEquals(0, executions.get());
        assertEquals(EffectRequest.Status.UNKNOWN, persistence.effects.effect.status());
        assertTrue(persistence.effects.attempts.isEmpty());
    }

    @Test
    void gracefulCloseDoesNotInterruptAnInFlightDatabaseOperation() throws Exception {
        FakePersistence persistence = new FakePersistence(unknown(manualPolicy()));
        BlockingTransactions blocking = new BlockingTransactions(persistence);
        EffectWorkerService workers = new EffectWorkerService(blocking, persistence,
                UUID.randomUUID(), new EffectHandlerRegistry(List.of(handler(request ->
                value("text/plain", "unused")))), 1, Duration.ofMillis(1), CLOCK,
                failure -> { });
        workers.start();
        assertTrue(blocking.entered.await(1, TimeUnit.SECONDS));

        Thread closer = Thread.ofVirtual().start(workers::close);
        Thread.sleep(50);
        assertFalse(blocking.interrupted.get());
        blocking.release.countDown();
        assertTrue(closer.join(Duration.ofSeconds(1)));
        assertFalse(blocking.interrupted.get());
    }

    @Test
    void idleEffectWorkerDoesNotRepeatedlyQueryUntilNotified() throws Exception {
        FakePersistence persistence = new FakePersistence(unknown(manualPolicy()));
        try (EffectWorkerService workers = workers(persistence, handler(request ->
                value("text/plain", "unused")))) {
            workers.start();
            assertTrue(persistence.effects.initialClaim.await(1, TimeUnit.SECONDS));
            assertFalse(persistence.effects.notifiedClaim.await(150, TimeUnit.MILLISECONDS));
            assertEquals(1, persistence.effects.claimCycles.get());

            workers.wake();
            assertTrue(persistence.effects.notifiedClaim.await(1, TimeUnit.SECONDS));
            assertEquals(2, persistence.effects.claimCycles.get());
        }
    }

    @Test
    void stalledExecutingEffectIsRecoveredThroughIdempotentRetry() throws Exception {
        FakePersistence persistence = new FakePersistence(stalled(retryPolicy()));
        AtomicInteger executions = new AtomicInteger();
        EffectHandler handler = handler(request -> {
            executions.incrementAndGet();
            return value("text/plain", "recovered");
        });

        try (EffectWorkerService workers = workers(persistence, handler)) {
            workers.start();
            assertTrue(persistence.completed.await(3, TimeUnit.SECONDS));
        }

        assertEquals(1, executions.get());
        assertEquals(EffectRequest.Status.COMPLETED, persistence.effects.effect.status());
        assertEquals(List.of(EffectAttempt.Status.SUCCEEDED),
                persistence.effects.attempts.stream().map(EffectAttempt::status).toList());
    }

    @Test
    void stalledQueryableEffectQueriesOutcomeWithoutReexecuting() throws Exception {
        FakePersistence persistence = new FakePersistence(stalled(queryPolicy()));
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        EffectHandler handler = new EffectHandler() {
            @Override public String effectType() { return "test.effect"; }

            @Override
            public Continuation.PersistedValue execute(
                    Continuation.PersistedValue request,
                    Optional<String> idempotencyKey
            ) {
                executions.incrementAndGet();
                throw new AssertionError("QUERY_REMOTE must not execute the effect again");
            }

            @Override
            public Optional<Continuation.PersistedValue> queryOutcome(EffectRequest request) {
                queries.incrementAndGet();
                return Optional.of(value("text/plain", "already-completed"));
            }
        };

        try (EffectWorkerService workers = workers(persistence, handler)) {
            workers.start();
            assertTrue(persistence.completed.await(3, TimeUnit.SECONDS));
        }

        assertEquals(0, executions.get());
        assertEquals(1, queries.get());
        assertEquals(EffectRequest.Status.COMPLETED, persistence.effects.effect.status());
    }

    @Test
    void stalledManualEffectIsLeftAsUnknownForManualResolution() throws Exception {
        FakePersistence persistence = new FakePersistence(stalled(manualPolicy()));
        AtomicInteger executions = new AtomicInteger();
        EffectHandler handler = handler(request -> {
            executions.incrementAndGet();
            return value("text/plain", "must-not-run");
        });

        try (EffectWorkerService workers = workers(persistence, handler)) {
            workers.start();
            assertFalse(persistence.completed.await(100, TimeUnit.MILLISECONDS));
        }

        assertEquals(0, executions.get());
        assertEquals(EffectRequest.Status.UNKNOWN, persistence.effects.effect.status());
        assertTrue(persistence.effects.attempts.isEmpty());
    }

    @Test
    void wakeConflictDoesNotRollBackCompletedEffectResult() throws Exception {
        EffectRequest prepared = prepared(manualPolicy());
        FakePersistence persistence = new FakePersistence(prepared,
                new WakeConflictProcess(prepared.effectId()));
        AtomicReference<Throwable> fatal = new AtomicReference<>();

        try (EffectWorkerService workers = new EffectWorkerService(
                persistence, persistence, UUID.randomUUID(),
                new EffectHandlerRegistry(List.of(handler(request ->
                        value("text/plain", "completed")))), 1, Duration.ofMillis(1), CLOCK,
                fatal::set)) {
            workers.start();
            assertTrue(persistence.completed.await(3, TimeUnit.SECONDS));
        }

        assertEquals(EffectRequest.Status.COMPLETED, persistence.effects.effect.status());
        assertEquals(EffectAttempt.Status.SUCCEEDED,
                persistence.effects.attempts.getFirst().status());
        assertNull(fatal.get());
    }

    @Test
    void wakeConflictIsRetriedAndEventuallyDelivers() throws Exception {
        EffectRequest prepared = prepared(manualPolicy());
        FlakyWakeProcess processes = new FlakyWakeProcess(prepared.effectId());
        FakeScheduler scheduler = new FakeScheduler();
        FakePersistence persistence = new FakePersistence(prepared, processes, scheduler);
        AtomicReference<Throwable> fatal = new AtomicReference<>();

        try (EffectWorkerService workers = new EffectWorkerService(
                persistence, persistence, UUID.randomUUID(),
                new EffectHandlerRegistry(List.of(handler(request ->
                        value("text/plain", "ok")))), 1, Duration.ofMillis(1), CLOCK,
                fatal::set)) {
            workers.start();
            assertTrue(persistence.completed.await(3, TimeUnit.SECONDS));
        }

        assertEquals(EffectRequest.Status.COMPLETED, persistence.effects.effect.status());
        assertTrue(processes.updates.get() >= 2,
                "wake must retry after a transient conflict, attempts: " + processes.updates.get());
        assertEquals(1, scheduler.enqueues.get());
        assertNull(fatal.get());
    }

    @Test
    void abandonedWakeAfterRetriesLeavesEffectCompletedAndProcessWaiting() throws Exception {
        EffectRequest prepared = prepared(manualPolicy());
        FakePersistence persistence = new FakePersistence(prepared,
                new WakeConflictProcess(prepared.effectId()));
        AtomicReference<Throwable> fatal = new AtomicReference<>();

        try (EffectWorkerService workers = new EffectWorkerService(
                persistence, persistence, UUID.randomUUID(),
                new EffectHandlerRegistry(List.of(handler(request ->
                        value("text/plain", "completed")))), 1, Duration.ofMillis(1), CLOCK,
                fatal::set)) {
            workers.start();
            assertTrue(persistence.completed.await(5, TimeUnit.SECONDS));
        }

        assertEquals(EffectRequest.Status.COMPLETED, persistence.effects.effect.status());
        assertNull(fatal.get(), "an abandoned wake must never fence the runtime");
    }

    private static EffectWorkerService workers(FakePersistence persistence,
                                                EffectHandler handler) {
        return new EffectWorkerService(persistence, persistence, UUID.randomUUID(),
                new EffectHandlerRegistry(List.of(handler)), 1, Duration.ofMillis(1), CLOCK,
                failure -> { });
    }

    private static EffectRequest prepared(EffectRequest.Policy policy) {
        return EffectRequest.prepare(UUID.randomUUID(), UUID.randomUUID(), "test.effect",
                value("json", "{\"request\":true}"), policy, NOW);
    }

    private static EffectRequest stalled(EffectRequest.Policy policy) {
        EffectRequest request = EffectRequest.prepare(UUID.randomUUID(), UUID.randomUUID(),
                "test.effect", value("json", "{\"request\":true}"), policy,
                NOW.minusSeconds(20));
        return request.claim(UUID.randomUUID(), NOW.minusSeconds(10))
                .start(NOW.minusSeconds(10));
    }

    private static EffectRequest unknown(EffectRequest.Policy policy) {
        EffectRequest prepared = EffectRequest.prepare(UUID.randomUUID(), UUID.randomUUID(),
                "test.effect", value("json", "{\"request\":true}"), policy,
                NOW.minusSeconds(4));
        return prepared.claim(UUID.randomUUID(), NOW.minusSeconds(3))
                .start(NOW.minusSeconds(2)).unknown("runtime stopped", NOW.minusSeconds(1));
    }

    private static EffectRequest.Policy manualPolicy() {
        return new EffectRequest.Policy(false, Optional.empty(), false, false,
                EffectRequest.UnknownAction.MANUAL);
    }

    private static EffectRequest.Policy retryPolicy() {
        return new EffectRequest.Policy(true, Optional.of("request-1"), false, true,
                EffectRequest.UnknownAction.RETRY_IDEMPOTENT);
    }

    private static EffectRequest.Policy queryPolicy() {
        return new EffectRequest.Policy(false, Optional.empty(), true, false,
                EffectRequest.UnknownAction.QUERY_REMOTE);
    }

    private static Continuation.PersistedValue value(String type, String payload) {
        return new Continuation.PersistedValue(type, payload);
    }

    private static EffectHandler handler(ThrowingHandler action) {
        return new EffectHandler() {
            @Override public String effectType() { return "test.effect"; }

            @Override
            public Continuation.PersistedValue execute(
                    Continuation.PersistedValue request,
                    Optional<String> idempotencyKey
            ) throws Exception {
                return action.execute(request);
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        Continuation.PersistedValue execute(Continuation.PersistedValue request) throws Exception;
    }

    private static final class FakePersistence implements TransactionExecutor, TransactionContext {
        final FakeEffects effects;
        final CountDownLatch completed = new CountDownLatch(1);
        final ProcessRepository processes;
        final SchedulerRepository scheduler;
        final AuthRepository auth = new AllowEffectsAuth();

        FakePersistence(EffectRequest effect) {
            effects = new FakeEffects(effect, completed);
            processes = new OneProcess(effect.processUid());
            scheduler = new FakeScheduler();
        }

        FakePersistence(EffectRequest effect, ProcessRepository processes) {
            effects = new FakeEffects(effect, completed);
            this.processes = processes;
            scheduler = new FakeScheduler();
        }

        FakePersistence(EffectRequest effect, ProcessRepository processes,
                        SchedulerRepository scheduler) {
            effects = new FakeEffects(effect, completed);
            this.processes = processes;
            this.scheduler = scheduler;
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
        @Override public AuthRepository auth() { return auth; }
        @Override public AuditRepository audit() { return null; }
        @Override public TerminalRepository terminal() { return null; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public void close() { }
    }

    private static final class BlockingTransactions implements TransactionExecutor {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean interrupted =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final TransactionExecutor delegate;

        BlockingTransactions(TransactionExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T inTransaction(Isolation isolation, TransactionWork<T> work) {
            entered.countDown();
            try {
                if (!release.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release test transaction");
                }
            } catch (InterruptedException failure) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Database operation was interrupted", failure);
            }
            return delegate.inTransaction(isolation, work);
        }
    }

    private static final class FakeEffects implements EffectRepository {
        EffectRequest effect;
        final List<EffectAttempt> attempts = new ArrayList<>();
        final CountDownLatch completed;
        final CountDownLatch initialClaim = new CountDownLatch(1);
        final CountDownLatch notifiedClaim = new CountDownLatch(2);
        final AtomicInteger claimCycles = new AtomicInteger();
        UUID bootId;

        FakeEffects(EffectRequest effect, CountDownLatch completed) {
            this.effect = effect;
            this.completed = completed;
        }

        @Override
        public void registerWorker(UUID workerId, UUID bootId, Instant now) {
            this.bootId = bootId;
        }

        @Override public void save(EffectRequest effect) { this.effect = effect; }

        @Override
        public Optional<EffectRequest> findById(UUID effectId) {
            return effect.effectId().equals(effectId) ? Optional.of(effect) : Optional.empty();
        }

        @Override
        public List<EffectRequest> claimPending(UUID workerId, Instant now, int limit) {
            claimCycles.incrementAndGet();
            initialClaim.countDown();
            notifiedClaim.countDown();
            if (effect.status() != EffectRequest.Status.PREPARED) return List.of();
            effect = effect.claim(workerId, now);
            return List.of(effect);
        }

        @Override
        public boolean update(EffectRequest changed, EffectRequest.Status expectedStatus) {
            if (effect.status() != expectedStatus) return false;
            effect = changed;
            if (changed.status() == EffectRequest.Status.COMPLETED
                    || changed.status() == EffectRequest.Status.FAILED
                    || changed.status() == EffectRequest.Status.UNKNOWN) {
                completed.countDown();
            }
            return true;
        }

        @Override
        public List<EffectRequest> claimRecoverableUnknown(UUID workerId, Instant now, int limit) {
            if (effect.status() != EffectRequest.Status.UNKNOWN
                    || effect.policy().unknownAction() == EffectRequest.UnknownAction.MANUAL) {
                return List.of();
            }
            effect = effect.claimRecoveredUnknown(workerId, now);
            return List.of(effect);
        }

        @Override
        public List<EffectRequest> claimStalled(UUID workerId, Instant now,
                                                long stallTimeoutMillis, int limit) {
            if (effect.status() != EffectRequest.Status.EXECUTING) return List.of();
            effect = effect.unknown("EFFECT_STALLED", now);
            if (effect.policy().unknownAction() != EffectRequest.UnknownAction.MANUAL) {
                effect = effect.claimRecoveredUnknown(workerId, now);
            }
            return List.of(effect);
        }

        @Override public int nextAttemptNumber(UUID effectId) { return attempts.size() + 1; }
        @Override public void saveAttempt(EffectAttempt attempt) { attempts.add(attempt); }

        @Override
        public Optional<EffectAttempt> findAttempt(UUID attemptId) {
            return attempts.stream().filter(attempt -> attempt.attemptId().equals(attemptId))
                    .findFirst();
        }

        @Override public List<EffectAttempt> findAttempts(UUID effectId) { return List.copyOf(attempts); }

        @Override
        public boolean updateAttempt(EffectAttempt changed, EffectAttempt.Status expectedStatus) {
            for (int index = 0; index < attempts.size(); index++) {
                EffectAttempt current = attempts.get(index);
                if (current.attemptId().equals(changed.attemptId())) {
                    if (current.status() != expectedStatus) return false;
                    attempts.set(index, changed);
                    return true;
                }
            }
            return false;
        }
    }

    private static final class WakeConflictProcess implements ProcessRepository {
        private final CilProcess process;

        private WakeConflictProcess(UUID effectId) {
            UUID ownerId = UUID.randomUUID();
            Continuation continuation = new Continuation(UUID.randomUUID(),
                    new ObjectHash("0".repeat(64)), 0, List.of(), List.of(), List.of(),
                    List.of(), Optional.of(new Continuation.WaitState(
                    Continuation.WaitKind.EFFECT, Optional.of(effectId), Optional.empty())),
                    Map.of(), Map.of(), "1", "1");
            process = new CilProcess(new ProcessIdentity(UUID.randomUUID(), 1), ownerId,
                    CilProcess.Status.WAITING_EFFECT, 0, 0, continuation, Optional.empty(),
                    NOW.minusSeconds(10), NOW.minusSeconds(5));
        }

        @Override public long allocatePid() { throw new UnsupportedOperationException(); }
        @Override public Optional<CilProcess> findByUid(UUID processUid) {
            return Optional.of(process);
        }
        @Override public Optional<CilProcess> findByPid(long pid) { return Optional.empty(); }
        @Override public void insert(CilProcess process) { throw new UnsupportedOperationException(); }
        @Override public UpdateResult update(CilProcess process, long state, long epoch) {
            return UpdateResult.VERSION_CONFLICT;
        }
        @Override public UpdateResult updateClaimed(CilProcess process, long state,
                com.follarce.domain.scheduler.SchedulerClaim claim) {
            return UpdateResult.VERSION_CONFLICT;
        }
    }

    /** Wakes reject the first update (transient conflict) and succeed on the second. */
    private static final class FlakyWakeProcess implements ProcessRepository {
        private final CilProcess process;
        final AtomicInteger updates = new AtomicInteger();

        private FlakyWakeProcess(UUID effectId) {
            UUID ownerId = UUID.randomUUID();
            Continuation continuation = new Continuation(UUID.randomUUID(),
                    new ObjectHash("0".repeat(64)), 0, List.of(), List.of(), List.of(),
                    List.of(), Optional.of(new Continuation.WaitState(
                    Continuation.WaitKind.EFFECT, Optional.of(effectId), Optional.empty())),
                    Map.of(), Map.of(), "1", "1");
            process = new CilProcess(new ProcessIdentity(UUID.randomUUID(), 1), ownerId,
                    CilProcess.Status.WAITING_EFFECT, 0, 0, continuation, Optional.empty(),
                    NOW.minusSeconds(10), NOW.minusSeconds(5));
        }

        @Override public long allocatePid() { throw new UnsupportedOperationException(); }
        @Override public Optional<CilProcess> findByUid(UUID processUid) {
            return Optional.of(process);
        }
        @Override public Optional<CilProcess> findByPid(long pid) { return Optional.empty(); }
        @Override public void insert(CilProcess process) { throw new UnsupportedOperationException(); }
        @Override public UpdateResult update(CilProcess process, long state, long epoch) {
            return updates.incrementAndGet() == 1
                    ? UpdateResult.VERSION_CONFLICT : UpdateResult.UPDATED;
        }
        @Override public UpdateResult updateClaimed(CilProcess process, long state,
                com.follarce.domain.scheduler.SchedulerClaim claim) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeScheduler implements SchedulerRepository {
        final AtomicInteger enqueues = new AtomicInteger();

        @Override public void enqueue(com.follarce.domain.scheduler.SchedulerQueueEntry entry) {
            enqueues.incrementAndGet();
        }

        @Override public Optional<com.follarce.domain.scheduler.SchedulerClaim> claimNext(
                UUID runnerId, UUID bootId, Instant now, Duration leaseDuration) {
            return Optional.empty();
        }

        @Override public void release(UUID processUid, long executionEpoch) { }
        @Override public int releaseExpired(Instant now) { return 0; }
        @Override public boolean heartbeat(com.follarce.domain.scheduler.SchedulerClaim claim) {
            return true;
        }
    }

    private static final class OneProcess implements ProcessRepository {
        private final CilProcess process;

        private OneProcess(UUID processUid) {
            UUID ownerId = UUID.randomUUID();
            Continuation continuation = new Continuation(UUID.randomUUID(),
                    new ObjectHash("0".repeat(64)), 0, List.of(), List.of(), List.of(),
                    List.of(), Optional.empty(), Map.of(), Map.of(), "1", "1");
            process = new CilProcess(new ProcessIdentity(processUid, 1), ownerId,
                    CilProcess.Status.WAITING_EFFECT, 0, 0, continuation, Optional.empty(),
                    NOW, NOW);
        }

        @Override public long allocatePid() { throw new UnsupportedOperationException(); }
        @Override public Optional<CilProcess> findByUid(UUID processUid) {
            return process.identity().processUid().equals(processUid)
                    ? Optional.of(process) : Optional.empty();
        }
        @Override public Optional<CilProcess> findByPid(long pid) {
            return process.identity().pid() == pid ? Optional.of(process) : Optional.empty();
        }
        @Override public void insert(CilProcess process) { throw new UnsupportedOperationException(); }
        @Override public UpdateResult update(CilProcess process, long state, long epoch) {
            throw new UnsupportedOperationException();
        }
        @Override public UpdateResult updateClaimed(CilProcess process, long state,
                com.follarce.domain.scheduler.SchedulerClaim claim) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class AllowEffectsAuth implements AuthRepository {
        @Override public Optional<UserAccount> findUser(UUID userId) { return Optional.empty(); }
        @Override public Optional<UserAccount> findUser(String username) { return Optional.empty(); }
        @Override public void saveUser(UserAccount user) { }
        @Override public String provisionPrincipal(UUID userId, char[] password) {
            return UserAccount.roleNameFor(userId);
        }
        @Override public void disablePrincipal(UUID userId) { }
        @Override public Set<Capability> capabilities(UUID userId) {
            return Set.of(Capability.EFFECT_REQUEST, Capability.SYSTEM_ADMIN);
        }
        @Override public void replaceCapabilities(UUID userId, Set<Capability> capabilities) { }
    }
}
