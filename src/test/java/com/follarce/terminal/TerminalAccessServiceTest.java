package com.follarce.terminal;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.AuditRepository;
import com.follarce.domain.port.AuthRepository;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.port.TransactionWork;
import com.follarce.domain.port.VfsRepository;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalAccessServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");
    private static final char[] PASSWORD = "correct-password".toCharArray();

    @Test
    void persistsSuccessfulLoginAsUserAudit() {
        Persistence persistence = new Persistence(true);
        TerminalAccessService access = service(persistence);

        Optional<UserAccount> authenticated = access.login("alice", PASSWORD.clone());

        assertTrue(authenticated.isPresent());
        assertEquals(1, persistence.events.size());
        AuditEvent event = persistence.events.getFirst();
        assertEquals("auth.login", event.action());
        assertEquals(AuditEvent.ActorType.USER, event.actorType());
        assertEquals(AuditEvent.Result.SUCCEEDED, event.result());
        assertEquals(persistence.account.userId(), persistence.lastUserTransaction);
    }

    @Test
    void persistsFailedLoginWithoutCredentialMaterial() {
        Persistence persistence = new Persistence(false);
        TerminalAccessService access = service(persistence);

        Optional<UserAccount> authenticated = access.login(
                "alice", "incorrect-password".toCharArray());

        assertTrue(authenticated.isEmpty());
        assertEquals(1, persistence.events.size());
        AuditEvent event = persistence.events.getFirst();
        assertEquals("auth.login", event.action());
        assertEquals(AuditEvent.ActorType.RUNTIME, event.actorType());
        assertEquals(AuditEvent.Result.DENIED, event.result());
        assertEquals("invalid_credential", event.details().get("reason"));
        assertEquals(Set.of("reason"), event.details().keySet());
    }

    @Test
    void throttlesMissingCredentialsThroughTheUnknownPrincipal() {
        Persistence persistence = new Persistence(false);
        TerminalAccessService access = service(persistence);

        assertTrue(access.login("", PASSWORD.clone()).isEmpty());
        assertTrue(access.login(null, PASSWORD.clone()).isEmpty());
        assertTrue(access.login("alice", null).isEmpty());

        assertEquals(List.of("<unknown>", "<unknown>", "<unknown>"),
                persistence.throttleLookups);
        assertEquals(List.of("<unknown>", "<unknown>", "<unknown>"),
                persistence.recordedFailures);
        assertEquals(0, persistence.credentialChecks);
        assertEquals(3, persistence.events.size());
    }

    private static TerminalAccessService service(Persistence persistence) {
        return new TerminalAccessService(persistence, persistence,
                "jdbc:postgresql://127.0.0.1/cilexec",
                Clock.fixed(NOW, ZoneOffset.UTC), "local");
    }

    private static final class Persistence
            implements TransactionExecutor, UserTransactionExecutor, TransactionContext {
        private final UserAccount account = UserAccount.active(UUID.randomUUID(), "alice", NOW);
        private final List<AuditEvent> events = new ArrayList<>();
        private final List<String> throttleLookups = new ArrayList<>();
        private final List<String> recordedFailures = new ArrayList<>();
        private final boolean credentialAccepted;
        private final AuthRepository auth;
        private final VfsRepository vfs;
        private final AuditRepository audit;
        private UUID lastUserTransaction;
        private int credentialChecks;

        private Persistence(boolean credentialAccepted) {
            this.credentialAccepted = credentialAccepted;
            this.auth = proxy(AuthRepository.class, (method, arguments) -> switch (method.getName()) {
                case "findUser" -> arguments[0] instanceof String username
                        && username.equals(account.username()) ? Optional.of(account) : Optional.empty();
                case "credentialMatches" -> {
                    credentialChecks++;
                    yield credentialAccepted
                            && arguments[0].equals(account.userId())
                            && Arrays.equals((char[]) arguments[1], PASSWORD);
                }
                case "loginBlockedUntil" -> {
                    throttleLookups.add((String) arguments[0]);
                    yield Optional.empty();
                }
                case "recordLoginFailure" -> {
                    recordedFailures.add((String) arguments[0]);
                    yield null;
                }
                case "clearLoginFailures" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            VfsNode root = new VfsNode(UUID.randomUUID(), Optional.empty(), account.userId(), "/",
                    VfsNode.Type.DIRECTORY, Optional.empty(), Set.of(), false, NOW, NOW);
            this.vfs = proxy(VfsRepository.class, (method, arguments) -> switch (method.getName()) {
                case "findChild" -> Optional.of(root);
                default -> throw new UnsupportedOperationException(method.getName());
            });
            this.audit = proxy(AuditRepository.class, (method, arguments) -> {
                if (method.getName().equals("append")) {
                    events.add((AuditEvent) arguments[0]);
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        }

        @Override
        public <T> T inTransaction(Isolation isolation, TransactionWork<T> work) {
            return work.execute(this);
        }

        @Override
        public <T> T inUserTransaction(UUID userId, Isolation isolation,
                                       TransactionWork<T> work) {
            lastUserTransaction = userId;
            return work.execute(this);
        }

        @Override public AuthRepository auth() { return auth; }
        @Override public VfsRepository vfs() { return vfs; }
        @Override public AuditRepository audit() { return audit; }
        @Override public com.follarce.domain.port.ProgramRepository programs() { return null; }
        @Override public com.follarce.domain.port.ProcessRepository processes() { return null; }
        @Override public com.follarce.domain.port.SchedulerRepository scheduler() { return null; }
        @Override public com.follarce.domain.port.IpcRepository ipc() { return null; }
        @Override public com.follarce.domain.port.TimerRepository timers() { return null; }
        @Override public com.follarce.domain.port.PackageRepository packages() { return null; }
        @Override public com.follarce.domain.port.EffectRepository effects() { return null; }
        @Override public com.follarce.domain.port.TerminalRepository terminal() { return null; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public void close() { }
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments);
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        Object instance = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> invocation.invoke(method,
                        arguments == null ? new Object[0] : arguments));
        return type.cast(instance);
    }
}
