package com.follarce.timer;

import com.follarce.domain.timer.ProcessTimer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TimerServiceTest {
    @Test
    void databaseRoundedClaimTimeCannotMakeFirePrecedeClaim() {
        Instant observedAt = Instant.parse("2026-08-01T10:49:36.123456Z");
        Instant roundedClaimAt = observedAt.plusNanos(1);
        ProcessTimer claimed = new ProcessTimer(UUID.randomUUID(), UUID.randomUUID(),
                observedAt.minusSeconds(1), ProcessTimer.Status.CLAIMED,
                observedAt.minusSeconds(2), Optional.of(UUID.randomUUID()),
                Optional.of(roundedClaimAt), Optional.empty(), Optional.empty());

        ProcessTimer fired = TimerService.fireNotBeforeClaim(claimed, observedAt);

        assertEquals(ProcessTimer.Status.FIRED, fired.status());
        assertEquals(Optional.of(roundedClaimAt), fired.firedAt());
    }
}
