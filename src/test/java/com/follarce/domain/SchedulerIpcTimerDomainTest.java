package com.follarce.domain;

import com.follarce.domain.ipc.IpcDelivery;
import com.follarce.domain.ipc.IpcMessage;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.scheduler.SchedulerClaim;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.domain.timer.ProcessTimer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerIpcTimerDomainTest {
    private static final Instant T0 = Instant.parse("2026-07-22T01:00:00Z");

    @Test
    void queueEntriesUseStableFifoOrderingAndReadyTime() {
        UUID earlierUid = new UUID(0, 1);
        UUID laterUid = new UUID(0, 2);
        SchedulerQueueEntry later = entry(laterUid, T0.plusSeconds(1), T0.plusSeconds(1));
        SchedulerQueueEntry tieSecond = entry(laterUid, T0, T0);
        SchedulerQueueEntry tieFirst = entry(earlierUid, T0, T0);
        List<SchedulerQueueEntry> entries = new ArrayList<>(
                List.of(later, tieSecond, tieFirst));

        entries.sort(null);

        assertEquals(List.of(tieFirst, tieSecond, later), entries);
        assertFalse(later.claimableAt(T0));
        assertTrue(later.claimableAt(T0.plusSeconds(1)));
        assertEquals(SchedulerQueueEntry.Status.CLAIMED, later.claimed().status());
        assertThrows(IllegalStateException.class, () -> later.claimed().claimed());
    }

    @Test
    void leaseHeartbeatCannotReviveExpiredOrAuthorizeOldEpoch() {
        SchedulerClaim claim = new SchedulerClaim(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 7, T0, T0, T0.plusSeconds(10));

        assertTrue(claim.authorizes(7, T0.plusSeconds(9)));
        assertFalse(claim.authorizes(6, T0.plusSeconds(9)));
        assertTrue(claim.isExpiredAt(T0.plusSeconds(10)));
        assertThrows(IllegalStateException.class,
                () -> claim.heartbeat(T0.plusSeconds(10), Duration.ofSeconds(5)));

        SchedulerClaim renewed = claim.heartbeat(T0.plusSeconds(5), Duration.ofSeconds(20));
        assertEquals(T0.plusSeconds(25), renewed.expiresAt());
    }

    @Test
    void messageRoutingAndExpiryAreExplicit() {
        IpcMessage topic = new IpcMessage(UUID.randomUUID(), Optional.empty(),
                IpcMessage.Kind.TOPIC, Optional.empty(), Optional.of("updates"), "json",
                Optional.of("{\"ok\":true}"), Optional.empty(), T0,
                Optional.of(T0.plusSeconds(30)));

        assertFalse(topic.isExpiredAt(T0.plusSeconds(29)));
        assertTrue(topic.isExpiredAt(T0.plusSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new IpcMessage(
                UUID.randomUUID(), Optional.empty(), IpcMessage.Kind.DIRECT,
                Optional.of(UUID.randomUUID()), Optional.empty(), "json",
                Optional.of("{}"), Optional.empty(), T0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new IpcMessage(
                UUID.randomUUID(), Optional.empty(), IpcMessage.Kind.DIRECT,
                Optional.empty(), Optional.empty(), "json", Optional.empty(),
                Optional.empty(), T0, Optional.empty()));
    }

    @Test
    void deliveryCanBeConsumedExactlyOnce() {
        IpcDelivery pending = IpcDelivery.pending(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        IpcDelivery reserved = pending.reserve(UUID.randomUUID(), T0);
        IpcDelivery consumed = reserved.consume(T0.plusSeconds(1));

        assertEquals(IpcDelivery.Status.CONSUMED, consumed.status());
        assertTrue(consumed.isTerminal());
        assertThrows(IllegalStateException.class,
                () -> consumed.consume(T0.plusSeconds(2)));
        assertEquals(IpcDelivery.Status.PENDING, reserved.releaseReservation().status());
    }

    @Test
    void failedDeliveryMayBecomeDeadButConsumedDeliveryCannot() {
        IpcDelivery failed = IpcDelivery.pending(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .reserve(UUID.randomUUID(), T0)
                .fail(T0.plusSeconds(1), "handler rejected payload");

        IpcDelivery dead = failed.dead(T0.plusSeconds(2), "retry limit reached");

        assertEquals(IpcDelivery.Status.DEAD, dead.status());
        assertTrue(dead.isTerminal());
        assertThrows(IllegalStateException.class, () -> dead.reserve(UUID.randomUUID(), T0));
    }

    @Test
    void deadAcceptsNullReasonButRejectsUnstartedDeliveries() {
        IpcDelivery reserved = IpcDelivery.pending(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .reserve(UUID.randomUUID(), T0);
        IpcDelivery dead = reserved.dead(T0.plusSeconds(1), null);

        assertEquals(IpcDelivery.Status.DEAD, dead.status());
        assertTrue(dead.failureReason().isEmpty());
        assertThrows(IllegalStateException.class, () ->
                IpcDelivery.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                        .dead(T0, null));
    }

    @Test
    void timerTruthSurvivesWithoutAnInMemoryWait() {
        ProcessTimer timer = new ProcessTimer(UUID.randomUUID(), UUID.randomUUID(),
                T0.plusSeconds(10), ProcessTimer.Status.SCHEDULED, T0,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new Continuation.PersistedValue("json", "{\"wake\":true}")));

        assertFalse(timer.isDueAt(T0.plusSeconds(9)));
        assertThrows(IllegalStateException.class,
                () -> timer.claim(UUID.randomUUID(), T0.plusSeconds(9)));

        ProcessTimer claimed = timer.claim(UUID.randomUUID(), T0.plusSeconds(10));
        ProcessTimer fired = claimed.fire(T0.plusSeconds(11));
        assertEquals(ProcessTimer.Status.FIRED, fired.status());
        assertThrows(IllegalStateException.class, () -> fired.fire(T0.plusSeconds(12)));
        assertThrows(IllegalStateException.class, fired::cancel);
    }

    private static SchedulerQueueEntry entry(UUID uid, Instant ready, Instant enqueued) {
        return new SchedulerQueueEntry(uid, ready, enqueued, SchedulerQueueEntry.Status.READY);
    }
}
