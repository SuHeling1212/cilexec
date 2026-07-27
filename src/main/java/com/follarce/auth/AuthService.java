package com.follarce.auth;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionExecutor;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Transactional CilExec-user and stable PostgreSQL-principal lifecycle. */
public final class AuthService {
    private final TransactionExecutor transactions;
    private final Clock clock;

    public AuthService(TransactionExecutor transactions, Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public UserAccount create(String username, char[] password, Set<Capability> capabilities) {
        PasswordPolicy.require(password);
        char[] secret = password.clone();
        try {
            Instant now = clock.instant();
            return transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
                if (transaction.auth().findUser(username).isPresent()) {
                    throw new IllegalArgumentException("Username already exists");
                }
                UserAccount account = UserAccount.active(UUID.randomUUID(), username, now);
                transaction.auth().saveUser(account);
                String role = transaction.auth().provisionPrincipal(account.userId(), secret);
                if (!role.equals(account.postgresRoleName())) {
                    throw new IllegalStateException("Database provisioned an unexpected role");
                }
                transaction.auth().replaceCapabilities(account.userId(), Set.copyOf(capabilities));
                if (transaction.vfs().findChild(account.userId(), Optional.empty(), "/").isEmpty()) {
                    transaction.vfs().createDirectory(account.userId(), Optional.empty(), "/",
                            Set.of());
                }
                transaction.audit().append(audit("auth.user.create", account, now));
                return account;
            });
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    public UserAccount rotateCredential(UUID userId, char[] password) {
        PasswordPolicy.require(password);
        char[] secret = password.clone();
        try {
            Instant now = clock.instant();
            return transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
                UserAccount current = transaction.auth().findUser(userId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
                if (current.status() != UserAccount.Status.ACTIVE) {
                    throw new IllegalStateException("Disabled user credentials cannot be rotated");
                }
                UserAccount changed = current.rotateCredential();
                transaction.auth().saveUser(changed);
                transaction.auth().provisionPrincipal(userId, secret);
                transaction.audit().append(audit("auth.credential.rotate", changed, now));
                return changed;
            });
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    public UserAccount disable(UUID userId) {
        Instant now = clock.instant();
        return transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            UserAccount current = transaction.auth().findUser(userId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
            UserAccount disabled = current.disable(now);
            transaction.auth().saveUser(disabled);
            transaction.auth().disablePrincipal(userId);
            transaction.audit().append(audit("auth.user.disable", disabled, now));
            return disabled;
        });
    }

    private static AuditEvent audit(String action, UserAccount account, Instant now) {
        return new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.ADMINISTRATOR,
                "runtime", action, "auth.user", account.userId().toString(),
                AuditEvent.Result.SUCCEEDED,
                Map.of("username", account.username(), "status", account.status().name()), now);
    }
}
