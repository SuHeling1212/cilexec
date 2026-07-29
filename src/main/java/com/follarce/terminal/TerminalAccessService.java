package com.follarce.terminal;

import com.follarce.auth.AuthService;
import com.follarce.auth.UsernamePolicy;
import com.follarce.config.JdbcUrlPolicy;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.vfs.VfsService;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;

/** Authenticates terminal users against application-owned credential verifiers. */
public final class TerminalAccessService implements TerminalAccess {
    private static final int MAX_CONCURRENT_CREDENTIAL_CHECKS = 8;
    private static final String UNKNOWN_PRINCIPAL = "<unknown>";
    private static final String DUMMY_CREDENTIAL = com.follarce.auth.PasswordPolicy.hash(
            "invalid-terminal-credential".toCharArray());
    public static final Set<Capability> USER_CAPABILITIES = Set.of(
            Capability.PROCESS_CREATE,
            Capability.PROCESS_CONTROL_OWN,
            Capability.VFS_READ,
            Capability.VFS_WRITE,
            Capability.PACKAGE_IMPORT,
            Capability.PACKAGE_BIND,
            Capability.EFFECT_REQUEST,
            Capability.TERMINAL_ATTACH,
            Capability.AUDIT_READ);

    public static final Set<Capability> ADMIN_CAPABILITIES;
    static {
        java.util.EnumSet<Capability> caps = java.util.EnumSet.copyOf(USER_CAPABILITIES);
        caps.add(Capability.SYSTEM_ADMIN);
        ADMIN_CAPABILITIES = Set.copyOf(caps);
    }

    private final JdbcTransactionExecutor transactions;
    private final Clock clock;
    private final String administratorUsername;
    private final AuthService auth;
    private final VfsService vfs;
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
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        JdbcUrlPolicy.requirePostgreSql(jdbcUrl);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.administratorUsername = UsernamePolicy.normalize(administratorUsername);
        this.auth = new AuthService(transactions, clock);
        this.vfs = new VfsService(transactions, clock);
    }

    @Override
    public Optional<UserAccount> login(String username, char[] password) {
        if (username == null || username.isBlank() || password == null) return Optional.empty();
        final String normalized;
        try {
            normalized = UsernamePolicy.normalize(username);
        } catch (IllegalArgumentException invalid) {
            applyLoginDelay(UNKNOWN_PRINCIPAL);
            verifyDummyCredential(password);
            recordLoginFailure(UNKNOWN_PRINCIPAL);
            return Optional.empty();
        }
        Optional<UserAccount> account = transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(normalized));
        if (account.isEmpty() || account.orElseThrow().status() != UserAccount.Status.ACTIVE) {
            applyLoginDelay(UNKNOWN_PRINCIPAL);
            verifyDummyCredential(password);
            recordLoginFailure(UNKNOWN_PRINCIPAL);
            return Optional.empty();
        }
        applyLoginDelay(normalized);
        if (!principalAccepts(account.orElseThrow(), password)) {
            recordLoginFailure(normalized);
            return Optional.empty();
        }
        loginFailures.remove(normalized);
        ensureRoot(account.orElseThrow());
        return account;
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
        Optional<UserAccount> administrator = transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(administratorUsername));
        if (administrator.isEmpty()) return false;
        return principalAccepts(administrator.orElseThrow(), password);
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
        if (failure == null) return;
        long delayMillis = Math.min(10_000L, 250L << Math.min(5, failure.count() - 1));
        long elapsed = java.time.Duration.between(failure.at(), clock.instant()).toMillis();
        if (elapsed < delayMillis) {
            LockSupport.parkNanos(java.time.Duration.ofMillis(delayMillis - elapsed).toNanos());
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
    }

    private record LoginFailure(int count, java.time.Instant at) { }

    private void ensureRoot(UserAccount account) {
        boolean exists = transactions.inUserTransaction(account.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.vfs()
                        .findChild(account.userId(), Optional.empty(), "/").isPresent());
        if (!exists) {
            vfs.createDirectory(account.userId(), Optional.empty(), "/", Set.of());
        }
        if (account.username().equals(administratorUsername)) {
            ensureUsersDirectory(account);
        }
    }

    /** The administrator gets a stable entry point for the virtual per-user home mounts. */
    private void ensureUsersDirectory(UserAccount account) {
        transactions.inUserTransaction(account.userId(), Isolation.SERIALIZABLE, transaction -> {
            var root = transaction.vfs().findChild(account.userId(), Optional.empty(), "/")
                    .orElseThrow(() -> new IllegalStateException("VFS root is missing"));
            if (transaction.vfs().findChild(account.userId(), Optional.of(root.nodeId()),
                    "Users").isEmpty()) {
                transaction.vfs().insertNode(new com.follarce.domain.vfs.VfsNode(
                        java.util.UUID.randomUUID(), Optional.of(root.nodeId()), account.userId(),
                        "Users", com.follarce.domain.vfs.VfsNode.Type.DIRECTORY,
                        Optional.empty(), Set.of(), false, clock.instant(), clock.instant()));
            }
            return null;
        });
    }

}
