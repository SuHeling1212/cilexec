package com.follarce.application;

import com.follarce.domain.ipc.IpcDelivery;
import com.follarce.domain.ipc.IpcMessage;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.ipc.IpcService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpcServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

    @Test
    void directWakeConsumesDeliveryBeforeMakingReceiverReady() {
        ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        UUID ownerId = UUID.randomUUID();
        UUID receiverUid = UUID.randomUUID();
        ObjectHash programHash = ObjectHash.sha256(new BinaryContent(
                "ipc-test".getBytes(StandardCharsets.UTF_8)));
        Continuation continuation = new Continuation(UUID.randomUUID(), programHash, 4,
                List.of(), List.of(), List.of(), List.of(),
                Optional.of(new Continuation.WaitState(Continuation.WaitKind.IPC,
                        Optional.of(receiverUid), Optional.empty())),
                Map.of(), Map.of(), "fcl-1", "1");
        persistence.processes.current = new CilProcess(
                new ProcessIdentity(receiverUid, 55), ownerId,
                CilProcess.Status.WAITING_IPC, 3, 9, continuation,
                Optional.empty(), NOW.minusSeconds(1), NOW.minusSeconds(1));
        IpcService service = new IpcService(persistence,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.sendDirect(ownerId, Optional.empty(), receiverUid,
                IpcService.Payload.json("json", "{\"value\":1}"), Optional.empty());

        IpcDelivery delivery = persistence.ipc.deliveries.values().iterator().next();
        assertEquals(IpcDelivery.Status.CONSUMED, delivery.status());
        assertEquals(Optional.of(receiverUid), delivery.reservedBy());
        assertEquals(Optional.of(NOW), delivery.consumedAt());
        assertTrue(persistence.ipc.findPending(receiverUid, 1).isEmpty());
        assertEquals(CilProcess.Status.READY, persistence.processes.current.status());
        assertTrue(persistence.processes.current.continuation().waitState().isEmpty());
        assertTrue(persistence.processes.current.continuation().globalVariables()
                .containsKey(ProcessInbox.IPC_RESULT));
        assertEquals(1, persistence.scheduler.enqueues);
        assertEquals(1, persistence.ipc.receiverLocks,
                "the wake decision must serialize with the receiver wait-state transaction");
    }

    @Test
    void receiveInReservesAMessageThatArrivedWhileTheReceiverWasNotWaiting() {
        ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        UUID ownerId = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        IpcMessage message = message(NOW.minusSeconds(5), Optional.of(NOW.plusSeconds(30)));
        IpcDelivery delivery = IpcDelivery.pending(UUID.randomUUID(), message.messageId(),
                receiver);
        // The message was committed while the receiver was RUNNING, so no wake was
        // delivered; the delivery is still PENDING when ipc.receive() finally runs.
        persistence.ipc.messages.put(message.messageId(), message);
        persistence.ipc.deliveries.put(delivery.deliveryId(), delivery);

        IpcService.Envelope envelope = IpcService.receiveIn(
                persistence, ownerId, receiver, worker, NOW).orElseThrow();

        assertEquals(message.messageId(), envelope.message().messageId());
        assertEquals(receiver, envelope.delivery().receiverProcessUid());
        assertEquals(IpcDelivery.Status.RESERVED,
                persistence.ipc.deliveries.get(delivery.deliveryId()).status());
        assertEquals(1, persistence.ipc.receiverLocks);
    }

    @Test
    void receiveInWithoutPendingMessagesLeavesTheProcessFreeToWait() {
        ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        UUID ownerId = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();

        Optional<IpcService.Envelope> envelope = IpcService.receiveIn(
                persistence, ownerId, receiver, UUID.randomUUID(), NOW);

        assertTrue(envelope.isEmpty());
        assertEquals(1, persistence.ipc.receiverLocks);
        assertTrue(persistence.ipc.deliveries.isEmpty());
    }

    @Test
    void purgeIsOwnerScopedBoundedAndAudited() {
        ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        UUID ownerId = UUID.randomUUID();
        Instant cutoff = NOW.minusSeconds(3600);
        persistence.ipc.purgeResult = 7;
        IpcService service = new IpcService(persistence,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(7, service.purgeMessages(ownerId, cutoff, 100));
        assertEquals(ownerId, persistence.ipc.purgeOwner);
        assertEquals(cutoff, persistence.ipc.purgeCutoff);
        assertEquals(NOW, persistence.ipc.purgeNow);
        assertEquals(100, persistence.ipc.purgeLimit);
        assertEquals("ipc.purge", persistence.audit.events.getLast().action());
        assertThrows(IllegalArgumentException.class,
                () -> service.purgeMessages(ownerId, NOW.plusSeconds(1), 100));
        assertThrows(IllegalArgumentException.class,
                () -> service.purgeMessages(ownerId, cutoff, 0));
    }

    @Test
    void pollingSkipsExpiredDeliveriesAndReturnsTheNextValidMessage() {
        ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        UUID ownerId = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        IpcMessage expired = message(NOW.minusSeconds(10), Optional.of(NOW.minusSeconds(1)));
        IpcMessage valid = message(NOW.minusSeconds(5), Optional.of(NOW.plusSeconds(10)));
        IpcDelivery expiredDelivery = IpcDelivery.pending(
                UUID.randomUUID(), expired.messageId(), receiver);
        IpcDelivery validDelivery = IpcDelivery.pending(
                UUID.randomUUID(), valid.messageId(), receiver);
        persistence.ipc.messages.put(expired.messageId(), expired);
        persistence.ipc.messages.put(valid.messageId(), valid);
        persistence.ipc.deliveries.put(expiredDelivery.deliveryId(), expiredDelivery);
        persistence.ipc.deliveries.put(validDelivery.deliveryId(), validDelivery);

        IpcService.Envelope envelope = IpcService.reserveNextIn(
                persistence, ownerId, receiver, worker, NOW).orElseThrow();

        assertEquals(valid.messageId(), envelope.message().messageId());
        assertEquals(IpcDelivery.Status.DEAD,
                persistence.ipc.deliveries.get(expiredDelivery.deliveryId()).status());
        assertEquals(IpcDelivery.Status.RESERVED,
                persistence.ipc.deliveries.get(validDelivery.deliveryId()).status());
    }

    @Test
    void pollFindsALiveMessageBehindMoreThanOneScanBatchOfExpiredDeliveries() {
        ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        UUID ownerId = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        for (int index = 0; index < 105; index++) {
            IpcMessage expired = message(NOW.minusSeconds(10),
                    Optional.of(NOW.minusSeconds(1)));
            IpcDelivery delivery = IpcDelivery.pending(
                    UUID.randomUUID(), expired.messageId(), receiver);
            persistence.ipc.messages.put(expired.messageId(), expired);
            persistence.ipc.deliveries.put(delivery.deliveryId(), delivery);
        }
        IpcMessage valid = message(NOW.minusSeconds(5), Optional.of(NOW.plusSeconds(10)));
        IpcDelivery validDelivery = IpcDelivery.pending(
                UUID.randomUUID(), valid.messageId(), receiver);
        persistence.ipc.messages.put(valid.messageId(), valid);
        persistence.ipc.deliveries.put(validDelivery.deliveryId(), validDelivery);

        IpcService.Envelope envelope = IpcService.reserveNextIn(
                persistence, ownerId, receiver, worker, NOW).orElseThrow();

        assertEquals(valid.messageId(), envelope.message().messageId());
        assertEquals(105L, persistence.ipc.deliveries.values().stream()
                .filter(delivery -> delivery.status() == IpcDelivery.Status.DEAD).count());
        assertEquals(IpcDelivery.Status.RESERVED,
                persistence.ipc.deliveries.get(validDelivery.deliveryId()).status());
    }

    private static IpcMessage message(Instant createdAt, Optional<Instant> expiresAt) {
        return new IpcMessage(UUID.randomUUID(), Optional.empty(), IpcMessage.Kind.DIRECT,
                Optional.empty(), Optional.empty(), "json", Optional.of("{}"),
                Optional.empty(), createdAt, expiresAt);
    }
}
