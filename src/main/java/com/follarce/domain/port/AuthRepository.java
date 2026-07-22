package com.follarce.domain.port;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AuthRepository {
    Optional<UserAccount> findUser(UUID userId);

    Optional<UserAccount> findUser(String username);

    void saveUser(UserAccount user);

    /** Provisions or rotates the stable PostgreSQL LOGIN role in this same transaction. */
    String provisionPrincipal(UUID userId, char[] password);

    /** Makes the stable PostgreSQL role unable to log in in this same transaction. */
    void disablePrincipal(UUID userId);

    Set<Capability> capabilities(UUID userId);

    void replaceCapabilities(UUID userId, Set<Capability> capabilities);
}
