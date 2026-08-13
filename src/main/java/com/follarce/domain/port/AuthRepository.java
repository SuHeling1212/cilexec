package com.follarce.domain.port;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface AuthRepository {
    Optional<UserAccount> findUser(UUID userId);

    Optional<UserAccount> findUser(String username);

    /** Returns a username only when the current database identity owns that account. */
    default Optional<String> findVisibleUsername(UUID userId) {
        return findUser(userId).map(UserAccount::username);
    }

    default List<UserAccount> findUsers() {
        throw new UnsupportedOperationException("User listing is not implemented");
    }

    /** Capability-checked cross-user listing for an administrator FCL statement. */
    default List<UserAccount> findUsersByAdministrator(UUID administratorId) {
        throw new UnsupportedOperationException("Administrator user listing is not implemented");
    }

    /** Creates the CilExec account and stable NOLOGIN tenant role in the current transaction. */
    default UserAccount createUserByAdministrator(UUID administratorId, UUID userId,
                                                   String username, char[] password,
                                                   Set<Capability> capabilities,
                                                   UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Atomic administrator user creation is not implemented");
    }

    /** Disables the CilExec account and its stable tenant role in the current transaction. */
    default UserAccount disableUserByAdministrator(UUID administratorId, UUID userId,
                                                    UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Atomic administrator user disable is not implemented");
    }

    void saveUser(UserAccount user);

    /** Provisions the stable PostgreSQL NOLOGIN tenant role in this same transaction. */
    String provisionPrincipal(UUID userId, char[] password);

    /** Verifies a terminal credential without exposing a database LOGIN principal. */
    default boolean credentialMatches(UUID userId, char[] password) {
        throw new UnsupportedOperationException("Credential verification is not implemented");
    }

    default Optional<Instant> loginBlockedUntil(String principalKey) {
        return Optional.empty();
    }

    default void recordLoginFailure(String principalKey, Instant failedAt, long maximumDelayMillis) {
    }

    default void clearLoginFailures(String principalKey) {
    }

    /** Makes the stable PostgreSQL role unable to log in in this same transaction. */
    void disablePrincipal(UUID userId);

    Set<Capability> capabilities(UUID userId);

    /** Trusted runtime lookup used after entering an explicitly audited administrator path. */
    default boolean hasCapabilityByAdministrator(UUID userId, Capability capability) {
        Set<Capability> available = capabilities(userId);
        return available.contains(Capability.SYSTEM_ADMIN) || available.contains(capability);
    }

    void replaceCapabilities(UUID userId, Set<Capability> capabilities);
}
