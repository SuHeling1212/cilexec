package com.follarce.domain.auth;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** CilExec user with a PostgreSQL role name derived only from stable user identity. */
public record UserAccount(
        UUID userId,
        String username,
        String postgresRoleName,
        Status status,
        Instant createdAt,
        Optional<Instant> disabledAt,
        long credentialVersion
) {
    public UserAccount {
        Invariant.required(userId, "userId");
        username = username(username);
        postgresRoleName = Invariant.text(postgresRoleName, "postgresRoleName");
        Invariant.check(postgresRoleName.equals(roleNameFor(userId)),
                "database role name must be derived from stable user ID");
        Invariant.required(status, "status");
        Invariant.required(createdAt, "createdAt");
        disabledAt = Invariant.required(disabledAt, "disabledAt");
        Invariant.positive(credentialVersion, "credentialVersion");
        boolean terminallyDisabled = status == Status.DISABLED || status == Status.DELETED;
        Invariant.check(terminallyDisabled == disabledAt.isPresent(),
                "disabled/deleted status and timestamp must agree");
        disabledAt.ifPresent(at -> Invariant.check(!at.isBefore(createdAt),
                "disabledAt must not precede creation"));
    }

    public static UserAccount active(UUID userId, String username, Instant createdAt) {
        return new UserAccount(userId, username, roleNameFor(userId), Status.ACTIVE,
                createdAt, Optional.empty(), 1);
    }

    public static String roleNameFor(UUID userId) {
        Invariant.required(userId, "userId");
        return "cilexec_user_" + userId.toString().replace("-", "");
    }

    public UserAccount rename(String changedUsername) {
        return new UserAccount(userId, changedUsername, postgresRoleName, status, createdAt,
                disabledAt, credentialVersion);
    }

    public UserAccount rotateCredential() {
        if (credentialVersion == Long.MAX_VALUE) {
            throw new IllegalStateException("credential version is exhausted");
        }
        return new UserAccount(userId, username, postgresRoleName, status, createdAt,
                disabledAt, credentialVersion + 1);
    }

    public UserAccount disable(Instant at) {
        if (status == Status.DISABLED || status == Status.DELETED) {
            throw new IllegalStateException("user is already disabled or deleted");
        }
        return new UserAccount(userId, username, postgresRoleName, Status.DISABLED,
                createdAt, Optional.of(Invariant.required(at, "at")), credentialVersion);
    }

    private static String username(String value) {
        value = Invariant.text(value, "username");
        Invariant.check(value.length() <= 128, "username is too long");
        Invariant.check(value.chars().noneMatch(Character::isISOControl),
                "username contains control characters");
        return value;
    }

    public enum Status {
        ACTIVE,
        LOCKED,
        DISABLED,
        DELETED
    }
}
