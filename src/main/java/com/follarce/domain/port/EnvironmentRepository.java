package com.follarce.domain.port;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable FCL environment variables: per-user values plus administrator-managed shared defaults. */
public interface EnvironmentRepository {
    Optional<String> findUser(UUID ownerId, String name);
    Map<String, String> findUsers(UUID ownerId);
    void saveUser(UUID ownerId, String name, String value, Instant at);
    boolean deleteUser(UUID ownerId, String name);

    Optional<String> findShared(String name);
    Map<String, String> findShared();
    void saveShared(String name, String value, UUID actorId, Instant at);
    boolean deleteShared(String name);

    SharedPolicy sharedPolicy();
    void saveSharedPolicy(SharedPolicy policy, UUID actorId, Instant at);

    record SharedPolicy(Mode mode, Set<String> names) {
        public SharedPolicy {
            java.util.Objects.requireNonNull(mode, "mode");
            names = Set.copyOf(names);
        }

        public boolean allows(String name) {
            return mode == Mode.ALLOWLIST ? names.contains(name) : !names.contains(name);
        }

        public enum Mode { ALLOWLIST, DENYLIST }
    }
}
