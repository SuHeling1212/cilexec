package com.follarce.audit;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.audit.AuditRetentionPolicy;
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
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-22T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void configuresFindsAndPurgesThroughExplicitTransactionBoundaries() {
        FakePersistence persistence = new FakePersistence();
        AuditRetentionService service = new AuditRetentionService(persistence, CLOCK);

        AuditRetentionPolicy configured = service.configure(
                "auth.login", Duration.ofDays(30), true);

        assertEquals(new AuditRetentionPolicy("auth.login", 2_592_000, true, NOW),
                configured);
        assertEquals(Isolation.SERIALIZABLE, persistence.lastIsolation);
        assertEquals(configured, service.find("auth.login").orElseThrow());
        assertEquals(Isolation.READ_COMMITTED, persistence.lastIsolation);

        persistence.purgeResult = 7;
        assertEquals(7, service.purgeExpired(250));
        assertEquals(250, persistence.purgeLimit);
        assertEquals(Isolation.READ_COMMITTED, persistence.lastIsolation);
    }

    @Test
    void rejectsAmbiguousDurationsAndUnboundedPurgeRequestsBeforeOpeningTransaction() {
        FakePersistence persistence = new FakePersistence();
        AuditRetentionService service = new AuditRetentionService(persistence, CLOCK);

        assertThrows(IllegalArgumentException.class,
                () -> service.configure("auth.login", Duration.ZERO, true));
        assertThrows(IllegalArgumentException.class,
                () -> service.configure("auth.login", Duration.ofMillis(1500), true));
        assertThrows(IllegalArgumentException.class, () -> service.purgeExpired(0));
        assertThrows(IllegalArgumentException.class,
                () -> service.purgeExpired(AuditRetentionService.MAX_PURGE_BATCH + 1));
        assertEquals(0, persistence.transactions);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void auditDetailsAreAnImmutableStringValuedObject() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("reason", "accepted");
        AuditEvent event = event(mutable);
        mutable.clear();

        assertEquals(Map.of("reason", "accepted"), event.details());
        assertThrows(UnsupportedOperationException.class,
                () -> event.details().put("changed", "true"));

        Map invalid = Map.of("attempts", 3);
        assertThrows(IllegalArgumentException.class,
                () -> event((Map<String, String>) invalid));
        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("reason", null);
        assertThrows(IllegalArgumentException.class, () -> event(nullValue));
    }

    private static AuditEvent event(Map<String, String> details) {
        return new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.RUNTIME, "runtime",
                "audit.test", "audit.event", "test", AuditEvent.Result.SUCCEEDED,
                details, NOW);
    }

    private static final class FakePersistence
            implements TransactionExecutor, TransactionContext, AuditRepository {
        private AuditRetentionPolicy policy;
        private Isolation lastIsolation;
        private int transactions;
        private int purgeResult;
        private int purgeLimit;

        @Override
        public <T> T inTransaction(Isolation isolation, TransactionWork<T> work) {
            transactions++;
            lastIsolation = isolation;
            return work.execute(this);
        }

        @Override public void append(AuditEvent event) { }
        @Override public List<AuditEvent> findByResource(String type, String id, int limit) {
            return List.of();
        }
        @Override public void saveRetentionPolicy(AuditRetentionPolicy changed) {
            policy = changed;
        }
        @Override public Optional<AuditRetentionPolicy> findRetentionPolicy(String eventType) {
            return policy == null || !policy.eventType().equals(eventType)
                    ? Optional.empty() : Optional.of(policy);
        }
        @Override public int purgeExpired(int limit) {
            purgeLimit = limit;
            return purgeResult;
        }

        @Override public AuditRepository audit() { return this; }
        @Override public ProgramRepository programs() { return null; }
        @Override public ProcessRepository processes() { return null; }
        @Override public SchedulerRepository scheduler() { return null; }
        @Override public IpcRepository ipc() { return null; }
        @Override public TimerRepository timers() { return null; }
        @Override public VfsRepository vfs() { return null; }
        @Override public PackageRepository packages() { return null; }
        @Override public EffectRepository effects() { return null; }
        @Override public AuthRepository auth() { return null; }
        @Override public TerminalRepository terminal() { return null; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public void close() { }
    }
}
