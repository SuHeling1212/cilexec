package com.follarce.terminal;

import com.follarce.auth.AuthService;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.vfs.VfsService;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;

/** Authenticates terminal users against their stable PostgreSQL LOGIN roles. */
public final class TerminalAccessService implements TerminalAccess {
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
    private final String jdbcUrl;
    private final Clock clock;

    public TerminalAccessService(JdbcTransactionExecutor transactions, String jdbcUrl,
                                 Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.jdbcUrl = requireJdbcUrl(jdbcUrl);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<UserAccount> login(String username, char[] password) {
        if (username == null || username.isBlank() || password == null) return Optional.empty();
        Optional<UserAccount> account = transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(username.trim()));
        if (account.isEmpty() || account.orElseThrow().status() != UserAccount.Status.ACTIVE) {
            return Optional.empty();
        }
        if (!principalAccepts(account.orElseThrow(), password)) return Optional.empty();
        ensureRoot(account.orElseThrow());
        return account;
    }

    @Override
    public UserAccount register(String username, char[] password) {
        return new AuthService(transactions, clock).create(
                normalizeUsername(username), password, USER_CAPABILITIES);
    }

    @Override
    public UserAccount register(String username, char[] password, char[] adminPassword) {
        if (adminPassword == null || adminPassword.length == 0) {
            throw new IllegalArgumentException("Admin password is required to create an administrator");
        }
        if (!verifyLocalPassword(adminPassword)) {
            throw new IllegalArgumentException("Invalid local administrator password");
        }
        return new AuthService(transactions, clock).create(
                normalizeUsername(username), password, ADMIN_CAPABILITIES);
    }

    @Override
    public boolean isFirstUse() {
        return transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser("local")).isEmpty();
    }

    @Override
    public UserAccount bootstrap(String username, char[] password) {
        return new AuthService(transactions, clock).create(
                normalizeUsername(username), password, ADMIN_CAPABILITIES);
    }

    private boolean verifyLocalPassword(char[] password) {
        Optional<UserAccount> local = transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser("local"));
        if (local.isEmpty()) return false;
        return principalAccepts(local.orElseThrow(), password);
    }

    private boolean principalAccepts(UserAccount account, char[] password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(jdbcUrl);
        source.setUser(account.postgresRoleName());
        source.setPassword(com.follarce.auth.PasswordPolicy.sha512Hex(password));
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT session_user")) {
            return row.next() && account.postgresRoleName().equals(row.getString(1));
        } catch (SQLException denied) {
            return false;
        }
    }

    private void ensureRoot(UserAccount account) {
        boolean exists = transactions.inUserTransaction(account.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.vfs()
                        .findChild(account.userId(), Optional.empty(), "/").isPresent());
        if (!exists) {
            new VfsService(transactions, clock).createDirectory(account.userId(), Optional.empty(),
                    "/", Set.of());
        }
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        String normalized = username.trim();
        if (normalized.length() > 128
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Username is invalid");
        }
        return normalized;
    }

    private static String requireJdbcUrl(String value) {
        if (value == null || !value.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("A PostgreSQL JDBC URL is required");
        }
        return value;
    }
}
