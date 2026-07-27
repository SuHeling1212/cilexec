package com.follarce.terminal;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.vfs.VfsService;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

/** Ensures the deployment-bound terminal administrator retains SYSTEM_ADMIN and a VFS root. */
public final class TerminalBootstrap {
    private final JdbcTransactionExecutor transactions;
    private final Clock clock;

    public TerminalBootstrap(JdbcTransactionExecutor transactions, Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** Returns the existing terminal administrator, or empty on first use. */
    public Optional<UserAccount> ensure(TerminalSettings settings) {
        java.util.Objects.requireNonNull(settings, "settings");
        Optional<UserAccount> existing = transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(settings.username()));
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        UserAccount account = existing.orElseThrow();
        if (account.status() != UserAccount.Status.ACTIVE) {
            throw new IllegalStateException("Configured terminal user is not active: "
                    + account.username());
        }
        transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            if (!transaction.auth().capabilities(account.userId())
                    .contains(Capability.SYSTEM_ADMIN)) {
                transaction.auth().replaceCapabilities(account.userId(),
                        Set.of(Capability.SYSTEM_ADMIN));
            }
            return null;
        });

        boolean hasRoot = transactions.inUserTransaction(account.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.vfs().findChild(account.userId(), Optional.empty(), "/")
                        .isPresent());
        if (!hasRoot) {
            new VfsService(transactions, clock).createDirectory(account.userId(), Optional.empty(),
                    "/", Set.of());
        }
        return Optional.of(account);
    }
}
