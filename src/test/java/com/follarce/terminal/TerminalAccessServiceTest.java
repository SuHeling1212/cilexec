package com.follarce.terminal;

import com.follarce.auth.AccountCapabilityProfiles;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalAccessServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");
    private static final char[] PASSWORD = "correct-password".toCharArray();
    private static final char[] ADMIN_PASSWORD = "correct-admin-password".toCharArray();
    private static final char[] NEW_ADMIN_PASSWORD = "new-admin-password".toCharArray();

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

    @Test
    void currentAdministratorCanCreateAnotherAdministrator() {
        Persistence persistence = new Persistence(false);
        TerminalAccessService access = service(persistence);

        UserAccount created = access.register("bob", NEW_ADMIN_PASSWORD.clone(),
                ADMIN_PASSWORD.clone());

        assertNotNull(created);
        assertEquals(List.of("bob"), persistence.createdUsernames);
        assertEquals(List.of(AccountCapabilityProfiles.ADMIN),
                persistence.assignedCapabilities);
    }

    @Test
    void revokedAdministratorCannotCreateAnotherAdministrator() {
        Persistence persistence = new Persistence(false);
        persistence.administratorCapabilities =
                AccountCapabilityProfiles.USER;
        TerminalAccessService access = service(persistence);

        assertThrows(IllegalArgumentException.class,
                () -> access.register("bob", NEW_ADMIN_PASSWORD.clone(),
                        ADMIN_PASSWORD.clone()));

        assertTrue(persistence.createdUsernames.isEmpty());
        assertTrue(persistence.assignedCapabilities.isEmpty());
        // The credential itself was valid; the denial is an authorization failure,
        // so it must not consume the login-failure throttle shared with normal login.
        assertTrue(persistence.recordedFailures.isEmpty());
    }

    @Test
    void expiredAdministratorCapabilityCannotCreateAdministrator() {
        // capabilities() already resolves expiry/group inheritance, so an expired
        // SYSTEM_ADMIN is observed here as an effective set without SYSTEM_ADMIN.
        // The PostgreSQL-backed expiry path is exercised end to end in
        // TerminalAccessServiceIT.
        Persistence persistence = new Persistence(false);
        persistence.administratorCapabilities = Set.of();
        TerminalAccessService access = service(persistence);

        assertThrows(IllegalArgumentException.class,
                () -> access.register("bob", NEW_ADMIN_PASSWORD.clone(),
                        ADMIN_PASSWORD.clone()));

        assertTrue(persistence.createdUsernames.isEmpty());
    }

    @Test
    void administratorPasswordAloneDoesNotGrantAdministratorCreation() {
        Persistence persistence = new Persistence(false);
        persistence.administratorCapabilities = Set.of(Capability.TERMINAL_ATTACH);
        TerminalAccessService access = service(persistence);

        assertThrows(IllegalArgumentException.class,
                () -> access.register("bob", NEW_ADMIN_PASSWORD.clone(),
                        ADMIN_PASSWORD.clone()));

        assertTrue(persistence.createdUsernames.isEmpty());
        assertTrue(persistence.assignedCapabilities.isEmpty());
    }

    @Test
    void groupGrantedSystemAdminCanCreateAdministrator() {
        // At the service boundary capabilities() is the effective set, so a
        // group-derived SYSTEM_ADMIN is indistinguishable from a direct one, and
        // must keep authorizing administrator creation.
        Persistence persistence = new Persistence(false);
        persistence.administratorCapabilities = Set.of(Capability.SYSTEM_ADMIN);
        TerminalAccessService access = service(persistence);

        UserAccount created = access.register("bob", NEW_ADMIN_PASSWORD.clone(),
                ADMIN_PASSWORD.clone());

        assertNotNull(created);
        assertEquals(List.of(AccountCapabilityProfiles.ADMIN),
                persistence.assignedCapabilities);
    }

    @Test
    void disabledAdministratorCannotCreateAdministrator() {
        Persistence persistence = new Persistence(false);
        persistence.administratorActive = false;
        TerminalAccessService access = service(persistence);

        assertThrows(IllegalArgumentException.class,
                () -> access.register("bob", NEW_ADMIN_PASSWORD.clone(),
                        ADMIN_PASSWORD.clone()));

        assertTrue(persistence.createdUsernames.isEmpty());
        assertEquals(List.of("local"), persistence.recordedFailures);
    }

    @Test
    void wrongAdministratorPasswordCannotCreateAdministrator() {
        Persistence persistence = new Persistence(false);
        TerminalAccessService access = service(persistence);

        assertThrows(IllegalArgumentException.class,
                () -> access.register("bob", NEW_ADMIN_PASSWORD.clone(),
                        "wrong-admin-password".toCharArray()));

        assertTrue(persistence.createdUsernames.isEmpty());
        assertEquals(List.of("local"), persistence.recordedFailures);
    }

    private static TerminalAccessService service(Persistence persistence) {
        return new TerminalAccessService(persistence, persistence,
                "jdbc:postgresql://127.0.0.1/cilexec",
                Clock.fixed(NOW, ZoneOffset.UTC), "local");
    }

    private static final class Persistence
            implements TransactionExecutor, UserTransactionExecutor, TransactionContext {
        private final UserAccount account = UserAccount.active(UUID.randomUUID(), "alice", NOW);
        private final UserAccount administrator =
                UserAccount.active(UUID.randomUUID(), "local", NOW);
        private final List<AuditEvent> events = new ArrayList<>();
        private final List<String> throttleLookups = new ArrayList<>();
        private final List<String> recordedFailures = new ArrayList<>();
        private final boolean credentialAccepted;
        private final AuthRepository auth;
        private final VfsRepository vfs;
        private final AuditRepository audit;
        private final List<String> createdUsernames = new ArrayList<>();
        private final List<Set<Capability>> assignedCapabilities = new ArrayList<>();
        private UUID lastUserTransaction;
        private int credentialChecks;
        private boolean administratorActive = true;
        private Set<Capability> administratorCapabilities =
                AccountCapabilityProfiles.ADMIN;

        private Persistence(boolean credentialAccepted) {
            this.credentialAccepted = credentialAccepted;
            this.auth = proxy(AuthRepository.class, (method, arguments) -> switch (method.getName()) {
                case "findUser" -> findUser(arguments);
                case "credentialMatches" -> {
                    credentialChecks++;
                    yield credentialMatches(arguments);
                }
                case "capabilities" -> {
                    UUID userId = (UUID) arguments[0];
                    if (userId.equals(account.userId())) {
                        yield AccountCapabilityProfiles.USER;
                    }
                    if (userId.equals(administrator.userId())) {
                        yield Set.copyOf(administratorCapabilities);
                    }
                    yield Set.of();
                }
                case "saveUser" -> {
                    createdUsernames.add(((UserAccount) arguments[0]).username());
                    yield null;
                }
                case "provisionPrincipal" -> UserAccount.roleNameFor((UUID) arguments[0]);
                case "replaceCapabilities" -> {
                    Set<?> requested = (Set<?>) arguments[1];
                    assignedCapabilities.add(requested.stream()
                            .map(Capability.class::cast)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
                    yield null;
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

        private Optional<UserAccount> findUser(Object[] arguments) {
            if (arguments[0] instanceof String username) {
                if (username.equals(account.username())) return Optional.of(account);
                if (username.equals(administrator.username())) return Optional.of(administrator);
                return Optional.empty();
            }
            UUID userId = (UUID) arguments[0];
            if (userId.equals(account.userId())) return Optional.of(account);
            if (userId.equals(administrator.userId())) return Optional.of(administrator);
            return Optional.empty();
        }

        private boolean credentialMatches(Object[] arguments) {
            UUID userId = (UUID) arguments[0];
            char[] supplied = (char[]) arguments[1];
            if (userId.equals(account.userId())) {
                return credentialAccepted && Arrays.equals(supplied, PASSWORD);
            }
            if (userId.equals(administrator.userId())) {
                return administratorActive && Arrays.equals(supplied, ADMIN_PASSWORD);
            }
            return false;
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
