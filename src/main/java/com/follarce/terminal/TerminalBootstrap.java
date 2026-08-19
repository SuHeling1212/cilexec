package com.follarce.terminal;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;

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
            Set<Capability> current = transaction.auth().capabilities(account.userId());
            if (!current.contains(Capability.SYSTEM_ADMIN)) {
                // Union with the current grants instead of replacing them, so this
                // bootstrap never deletes capabilities assigned through other channels.
                Set<Capability> merged = new java.util.LinkedHashSet<>(current);
                merged.add(Capability.SYSTEM_ADMIN);
                transaction.auth().replaceCapabilities(account.userId(), Set.copyOf(merged));
            }
            return null;
        });

        transactions.inUserTransaction(account.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    // Database-level idempotent creation: concurrent bootstrap paths must
                    // not fail on the unique owner-root index.
                    var root = transaction.vfs()
                            .findChild(account.userId(), Optional.empty(), "/")
                            .orElseGet(() -> {
                                var now = clock.instant();
                                transaction.vfs().insertNodeIfAbsent(
                                        new com.follarce.domain.vfs.VfsNode(
                                                java.util.UUID.randomUUID(), Optional.empty(),
                                                account.userId(), "/",
                                                com.follarce.domain.vfs.VfsNode.Type.DIRECTORY,
                                                Optional.empty(), Set.of(), false, now, now));
                                return transaction.vfs()
                                        .findChild(account.userId(), Optional.empty(), "/")
                                        .orElseThrow(() -> new IllegalStateException(
                                                "VFS root is missing"));
                            });
                    ensureUsersDirectory(transaction, root);
                    return null;
                });
        return Optional.of(account);
    }

    private void ensureUsersDirectory(
            com.follarce.domain.port.TransactionContext transaction,
            com.follarce.domain.vfs.VfsNode root) {
        if (transaction.vfs().findChild(root.ownerId(), Optional.of(root.nodeId()),
                "Users").isEmpty()) {
            var now = clock.instant();
            transaction.vfs().insertNodeIfAbsent(new com.follarce.domain.vfs.VfsNode(
                    java.util.UUID.randomUUID(), Optional.of(root.nodeId()), root.ownerId(),
                    "Users", com.follarce.domain.vfs.VfsNode.Type.DIRECTORY,
                    Optional.empty(), Set.of(), false, now, now));
        }
    }
}
