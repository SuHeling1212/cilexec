package com.follarce.domain.port;

import com.follarce.domain.effect.EffectAttempt;
import com.follarce.domain.effect.EffectRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EffectRepository {
    /** Registers a database-visible EFFECT runner before it can own attempts. */
    void registerWorker(UUID workerId, UUID bootId, Instant now);

    void save(EffectRequest effect);

    Optional<EffectRequest> findById(UUID effectId);

    List<EffectRequest> claimPending(UUID workerId, Instant now, int limit);

    boolean update(EffectRequest effect, EffectRequest.Status expectedStatus);

    /** Identity-bound UNKNOWN resolution; JDBC overrides this with the narrow SQL API. */
    default boolean resolveUnknownManually(EffectRequest effect) {
        return update(effect, EffectRequest.Status.UNKNOWN);
    }

    /** Claims only non-manual UNKNOWN work whose previous runner is no longer active. */
    default List<EffectRequest> claimRecoverableUnknown(UUID workerId, Instant now, int limit) {
        return List.of();
    }

    /**
     * Reclaims EXECUTING work whose runner stopped heartbeating inside the current boot;
     * no-op by default.
     */
    default List<EffectRequest> claimStalled(UUID workerId, Instant now,
                                             long stallTimeoutMillis, int limit) {
        return List.of();
    }

    /** Refreshes a worker runner's heartbeat so long-running effects are not reclaimed. */
    default boolean heartbeatWorker(UUID workerId, Instant now) {
        return true;
    }

    /** Returns the next number while holding the parent effect row lock. */
    int nextAttemptNumber(UUID effectId);

    void saveAttempt(EffectAttempt attempt);

    Optional<EffectAttempt> findAttempt(UUID attemptId);

    List<EffectAttempt> findAttempts(UUID effectId);

    boolean updateAttempt(EffectAttempt attempt, EffectAttempt.Status expectedStatus);
}
