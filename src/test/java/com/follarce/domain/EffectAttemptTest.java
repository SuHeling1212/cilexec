package com.follarce.domain;

import com.follarce.domain.effect.EffectAttempt;
import com.follarce.domain.process.Continuation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectAttemptTest {
    private static final Instant START = Instant.parse("2026-07-22T06:00:00Z");

    @Test
    void recordsOneFencedLifecycleForEachExternalInvocation() {
        UUID effectId = UUID.randomUUID();
        UUID runnerId = UUID.randomUUID();
        EffectAttempt claimed = EffectAttempt.claim(effectId, 1, runnerId, START);
        EffectAttempt executing = claimed.start();
        Continuation.PersistedValue result =
                new Continuation.PersistedValue("json", "{\"ok\":true}");
        EffectAttempt succeeded = executing.succeed(result, START.plusSeconds(1));

        assertEquals(EffectAttempt.Status.SUCCEEDED, succeeded.status());
        assertEquals(result, succeeded.result().orElseThrow());
        assertTrue(succeeded.finishedAt().isPresent());
        assertThrows(IllegalStateException.class, executing::start);
        assertThrows(IllegalStateException.class,
                () -> succeeded.fail("FAILED", "too late", START.plusSeconds(2)));
    }

    @Test
    void failureAndUnknownAreTerminalOutcomesWithStableDiagnostics() {
        EffectAttempt executing = EffectAttempt.claim(UUID.randomUUID(), 3,
                UUID.randomUUID(), START).start();
        EffectAttempt failed = executing.fail("REMOTE_REJECTED", "denied",
                START.plusSeconds(2));
        EffectAttempt unknown = EffectAttempt.claim(UUID.randomUUID(), 4,
                UUID.randomUUID(), START).start().unknown(
                "OUTCOME_UNKNOWN", "connection dropped", START.plusSeconds(3));

        assertEquals(Optional.of("REMOTE_REJECTED"), failed.errorCode());
        assertEquals(EffectAttempt.Status.UNKNOWN, unknown.status());
        assertEquals(Optional.of("connection dropped"), unknown.errorMessage());
        assertThrows(IllegalArgumentException.class, () -> new EffectAttempt(
                UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID(),
                EffectAttempt.Status.CLAIMED, START, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()));
    }
}
