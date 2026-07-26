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

    default List<UserAccount> findUsers() {
        throw new UnsupportedOperationException("User listing is not implemented");
    }

    /** Capability-checked cross-user listing for an administrator FCL statement. */
    default List<UserAccount> findUsersByAdministrator(UUID administratorId) {
        throw new UnsupportedOperationException("Administrator user listing is not implemented");
    }

    /** Creates the CilExec account and LOGIN role in the caller's current transaction. */
    default UserAccount createUserByAdministrator(UUID administratorId, UUID userId,
                                                   String username, char[] password,
                                                   Set<Capability> capabilities,
                                                   UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Atomic administrator user creation is not implemented");
    }

    /** Disables the CilExec account and LOGIN role in the caller's current transaction. */
    default UserAccount disableUserByAdministrator(UUID administratorId, UUID userId,
                                                    UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Atomic administrator user disable is not implemented");
    }

    void saveUser(UserAccount user);

    /** Provisions or rotates the stable PostgreSQL LOGIN role in this same transaction. */
    String provisionPrincipal(UUID userId, char[] password);

    /** Makes the stable PostgreSQL role unable to log in in this same transaction. */
    void disablePrincipal(UUID userId);

    Set<Capability> capabilities(UUID userId);

    /** Trusted runtime lookup used after entering an explicitly audited administrator path. */
    default boolean hasCapabilityByAdministrator(UUID userId, Capability capability) {
        return capabilities(userId).contains(capability);
    }

    void replaceCapabilities(UUID userId, Set<Capability> capabilities);
}
