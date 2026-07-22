package com.follarce.application;

import com.follarce.domain.ipc.IpcDelivery;
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
    }
}
