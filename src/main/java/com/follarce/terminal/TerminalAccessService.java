package com.follarce.terminal;

import com.follarce.auth.AuthService;
import com.follarce.auth.UsernamePolicy;
import com.follarce.config.JdbcUrlPolicy;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;

/** Authenticates terminal users against application-owned credential verifiers. */
public final class TerminalAccessService implements TerminalAccess {
    private static final int MAX_CONCURRENT_CREDENTIAL_CHECKS = 8;
    private static final long MAX_LOGIN_DELAY_MILLIS = 30_000;
    // The shared <unknown> bucket accumulates every unknown-name lookup, so it keeps its own
    // lower ceiling to prevent one attacker from slowing unrelated unknown-username logins.
    private static final long MAX_UNKNOWN_LOGIN_DELAY_MILLIS = 10_000;
    private static final String UNKNOWN_PRINCIPAL = "<unknown>";
    private static final String DUMMY_CREDENTIAL = com.follarce.auth.PasswordPolicy.hash(
            "invalid-terminal-credential".toCharArray());
    public static final Set<Capability> USER_CAPABILITIES = Set.of(
            Capability.PROCESS_CREATE,
            Capability.PROCESS_CONTROL_OWN,
            Capability.VFS_READ,
            Capability.VFS_WRITE,
            Capability.TERMINAL_ATTACH,
            Capability.AUDIT_READ);

    public static final Set<Capability> ADMIN_CAPABILITIES;
    static {
        java.util.EnumSet<Capability> caps = java.util.EnumSet.copyOf(USER_CAPABILITIES);
        caps.add(Capability.SYSTEM_ADMIN);
        ADMIN_CAPABILITIES = Set.copyOf(caps);
    }

    private final TransactionExecutor transactions;
    private final UserTransactionExecutor userTransactions;
    private final Clock clock;
    private final String administratorUsername;
    private final AuthService auth;
    private final ConcurrentHashMap<String, LoginFailure> loginFailures =
            new ConcurrentHashMap<>();
    private final Semaphore credentialChecks = new Semaphore(
            MAX_CONCURRENT_CREDENTIAL_CHECKS, true);

    public TerminalAccessService(JdbcTransactionExecutor transactions, String jdbcUrl,
                                 Clock clock) {
        this(transactions, jdbcUrl, clock, "local");
    }

    public TerminalAccessService(JdbcTransactionExecutor transactions, String jdbcUrl,
                                 Clock clock, String administratorUsername) {
        this(transactions, transactions, jdbcUrl, clock, administratorUsername);
    }

    TerminalAccessService(TransactionExecutor transactions,
                          UserTransactionExecutor userTransactions, String jdbcUrl,
                          Clock clock, String administratorUsername) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.userTransactions = java.util.Objects.requireNonNull(
                userTransactions, "userTransactions");
        JdbcUrlPolicy.requirePostgreSql(jdbcUrl);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.administratorUsername = UsernamePolicy.normalize(administratorUsername);
        this.auth = new AuthService(transactions, clock);
    }

    @Override
    public Optional<UserAccount> login(String username, char[] password) {
        if (username == null || username.isBlank() || password == null) {
            return rejectUnknownCredential(password, "missing_credentials");
        }
        final String normalized;
        try {
            normalized = UsernamePolicy.normalize(username);
        } catch (IllegalArgumentException invalid) {
            return rejectUnknownCredential(password, "invalid_username");
        }
        Optional<UserAccount> account = transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(normalized));
        if (account.isEmpty() || account.orElseThrow().status() != UserAccount.Status.ACTIVE) {
            applyLoginDelay(UNKNOWN_PRINCIPAL);
            verifyDummyCredential(password);
            recordLoginFailure(UNKNOWN_PRINCIPAL);
            auditLoginFailure(UNKNOWN_PRINCIPAL, "unknown_or_inactive_user");
            return Optional.empty();
        }
        applyLoginDelay(normalized);
        if (!principalAccepts(account.orElseThrow(), password)) {
            recordLoginFailure(normalized);
            auditLoginFailure(account.orElseThrow().userId().toString(),
                    "invalid_credential");
            return Optional.empty();
        }
        loginFailures.remove(normalized);
        clearPersistentLoginFailures(normalized);
        auditLoginSuccess(account.orElseThrow());
        ensureRoot(account.orElseThrow());
        return account;
    }

    private Optional<UserAccount> rejectUnknownCredential(char[] password, String reason) {
        applyLoginDelay(UNKNOWN_PRINCIPAL);
        verifyDummyCredential(password == null ? new char[0] : password);
        recordLoginFailure(UNKNOWN_PRINCIPAL);
        auditLoginFailure(UNKNOWN_PRINCIPAL, reason);
        return Optional.empty();
    }

    @Override
    public UserAccount register(String username, char[] password) {
        return auth.create(UsernamePolicy.normalize(username), password, USER_CAPABILITIES);
    }

    @Override
    public UserAccount register(String username, char[] password, char[] adminPassword) {
        if (adminPassword == null || adminPassword.length == 0) {
            throw new IllegalArgumentException("Admin password is required to create an administrator");
        }
        if (!verifyAdministratorPassword(adminPassword)) {
            throw new IllegalArgumentException("Invalid administrator password");
        }
        return auth.create(UsernamePolicy.normalize(username), password, ADMIN_CAPABILITIES);
    }

    @Override
    public boolean isFirstUse() {
        return transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(administratorUsername)).isEmpty();
    }

    @Override
    public UserAccount bootstrap(String username, char[] password) {
        return auth.create(UsernamePolicy.normalize(username), password, ADMIN_CAPABILITIES);
    }

    private boolean verifyAdministratorPassword(char[] password) {
        applyLoginDelay(administratorUsername);
        Optional<UserAccount> administrator = transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(administratorUsername));
        if (administrator.isEmpty()) {
            recordLoginFailure(administratorUsername);
            return false;
        }
        if (!principalAccepts(administrator.orElseThrow(), password)) {
            recordLoginFailure(administratorUsername);
            return false;
        }
        loginFailures.remove(administratorUsername);
        clearPersistentLoginFailures(administratorUsername);
        return true;
    }

    private boolean principalAccepts(UserAccount account, char[] password) {
        credentialChecks.acquireUninterruptibly();
        try {
            return transactions.inTransaction(Isolation.READ_COMMITTED,
                    transaction -> transaction.auth().credentialMatches(
                            account.userId(), password));
        } finally {
            credentialChecks.release();
        }
    }

    private void verifyDummyCredential(char[] password) {
        credentialChecks.acquireUninterruptibly();
        try {
            dummyVerify(password);
        } finally {
            credentialChecks.release();
        }
    }

    private static void dummyVerify(char[] password) {
        com.follarce.auth.PasswordPolicy.matches(password, DUMMY_CREDENTIAL);
    }

    private void applyLoginDelay(String username) {
        LoginFailure failure = loginFailures.get(username);
        java.time.Instant blockedUntil = transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().loginBlockedUntil(username).orElse(null));
        long capMillis = username.equals(UNKNOWN_PRINCIPAL)
                ? MAX_UNKNOWN_LOGIN_DELAY_MILLIS : MAX_LOGIN_DELAY_MILLIS;
        long memoryDelay = failure == null ? 0
                : Math.min(capMillis, 250L << Math.min(7, failure.count() - 1))
                - java.time.Duration.between(failure.at(), clock.instant()).toMillis();
        long databaseDelay = blockedUntil == null ? 0
                : java.time.Duration.between(clock.instant(), blockedUntil).toMillis();
        long delayMillis = Math.max(memoryDelay, databaseDelay);
        if (delayMillis > 0) {
            LockSupport.parkNanos(java.time.Duration.ofMillis(delayMillis).toNanos());
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Login was interrupted");
            }
        }
    }

    private void recordLoginFailure(String username) {
        java.time.Instant now = clock.instant();
        loginFailures.compute(username, (ignored, previous) -> new LoginFailure(
                previous == null || java.time.Duration.between(previous.at(), now)
                        .toMinutes() >= 10 ? 1 : Math.min(32, previous.count() + 1), now));
        long maximumDelay = username.equals(UNKNOWN_PRINCIPAL)
                ? MAX_UNKNOWN_LOGIN_DELAY_MILLIS : MAX_LOGIN_DELAY_MILLIS;
        transactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            transaction.auth().recordLoginFailure(username, now, maximumDelay);
            return null;
        });
    }

    private void clearPersistentLoginFailures(String username) {
        transactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            transaction.auth().clearLoginFailures(username);
            return null;
        });
    }

    private void auditLoginSuccess(UserAccount account) {
        userTransactions.inUserTransaction(account.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    transaction.audit().append(new AuditEvent(UUID.randomUUID(),
                            AuditEvent.ActorType.USER, account.userId().toString(),
                            "auth.login", "auth.user", account.userId().toString(),
                            AuditEvent.Result.SUCCEEDED,
                            Map.of("username", account.username()), clock.instant()));
                    return null;
                });
    }

    private void auditLoginFailure(String resourceId, String reason) {
        transactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            transaction.audit().append(new AuditEvent(UUID.randomUUID(),
                    AuditEvent.ActorType.RUNTIME, "terminal", "auth.login", "auth.user",
                    resourceId, AuditEvent.Result.DENIED,
                    Map.of("reason", reason), clock.instant()));
            return null;
        });
    }

    private record LoginFailure(int count, java.time.Instant at) { }

    private void ensureRoot(UserAccount account) {
        userTransactions.inUserTransaction(account.userId(), Isolation.READ_COMMITTED, transaction -> {
            // Idempotent database-level creation: a concurrent first-time login that
            // races this one must not fail on the unique owner-root index.
            com.follarce.domain.vfs.VfsNode root = transaction.vfs()
                    .findChild(account.userId(), Optional.empty(), "/")
                    .orElseGet(() -> {
                        transaction.vfs().insertNodeIfAbsent(new com.follarce.domain.vfs.VfsNode(
                                UUID.randomUUID(), Optional.empty(), account.userId(), "/",
                                com.follarce.domain.vfs.VfsNode.Type.DIRECTORY,
                                Optional.empty(), Set.of(), false,
                                clock.instant(), clock.instant()));
                        return transaction.vfs().findChild(account.userId(), Optional.empty(), "/")
                                .orElseThrow(() -> new IllegalStateException("VFS root is missing"));
                    });
            if (account.username().equals(administratorUsername)) {
                ensureUsersDirectory(transaction, root);
            }
            return null;
        });
    }

    /** The administrator gets a stable entry point for the virtual per-user home mounts. */
    private void ensureUsersDirectory(TransactionContext transaction,
                                      com.follarce.domain.vfs.VfsNode root) {
        if (transaction.vfs().findChild(root.ownerId(), Optional.of(root.nodeId()),
                "Users").isEmpty()) {
            transaction.vfs().insertNodeIfAbsent(new com.follarce.domain.vfs.VfsNode(
                    UUID.randomUUID(), Optional.of(root.nodeId()), root.ownerId(), "Users",
                    com.follarce.domain.vfs.VfsNode.Type.DIRECTORY,
                    Optional.empty(), Set.of(), false, clock.instant(), clock.instant()));
        }
    }

}
