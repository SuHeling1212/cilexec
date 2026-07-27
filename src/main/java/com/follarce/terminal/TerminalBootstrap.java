package com.follarce.terminal;

import com.follarce.auth.AuthService;
import com.follarce.config.DockerSecretLoader;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.vfs.VfsService;

import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** Creates the deployment-bound terminal administrator and its VFS root exactly once. */
public final class TerminalBootstrap {
    private final JdbcTransactionExecutor transactions;
    private final Clock clock;

    public TerminalBootstrap(JdbcTransactionExecutor transactions, Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public UserAccount ensure(TerminalSettings settings) {
        java.util.Objects.requireNonNull(settings, "settings");
        Optional<UserAccount> existing = transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(settings.username()));
        UserAccount account;
        if (existing.isPresent()) {
            account = existing.orElseThrow();
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
        } else {
            try (DockerSecretLoader.SecretValue secret = DockerSecretLoader.read(
                    settings.bootstrapPasswordFile())) {
                char[] password = secret.copy();
                try {
                    account = new AuthService(transactions, clock).create(settings.username(),
                            password, Set.of(Capability.SYSTEM_ADMIN));
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
        }

        boolean hasRoot = transactions.inUserTransaction(account.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.vfs().findChild(account.userId(), Optional.empty(), "/")
                        .isPresent());
        if (!hasRoot) {
            new VfsService(transactions, clock).createDirectory(account.userId(), Optional.empty(),
                    "/", Set.of());
        }
        return account;
    }
}
