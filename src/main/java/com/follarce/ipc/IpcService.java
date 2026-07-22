package com.follarce.ipc;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.ipc.IpcChannel;
import com.follarce.domain.ipc.IpcDelivery;
import com.follarce.domain.ipc.IpcMessage;
import com.follarce.domain.ipc.IpcSubscription;
import com.follarce.domain.ipc.IpcTopic;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.google.gson.Gson;

/** Durable direct, channel, topic, and broadcast messaging use cases. */
public final class IpcService {
    private static final Gson JSON = new Gson();
    private final UserTransactionExecutor transactions;
    private final Clock clock;

    public IpcService(UserTransactionExecutor transactions, Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public IpcChannel createChannel(UUID ownerId, String name) {
        Instant now = clock.instant();
        IpcChannel channel = new IpcChannel(UUID.randomUUID(), ownerId, name,
                IpcChannel.Status.ACTIVE, now, Optional.empty());
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            transaction.ipc().saveChannel(channel);
            return channel;
        });
    }

    public IpcTopic createTopic(UUID ownerId, String name) {
        Instant now = clock.instant();
        IpcTopic topic = new IpcTopic(UUID.randomUUID(), ownerId, name,
                IpcTopic.Status.ACTIVE, now, Optional.empty());
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            transaction.ipc().saveTopic(topic);
            return topic;
        });
    }

    public IpcSubscription subscribeChannel(UUID ownerId, UUID processUid, UUID channelId) {
        return subscribe(ownerId, processUid, IpcSubscription.SourceKind.CHANNEL,
                Optional.of(channelId), Optional.empty());
    }

    public IpcSubscription subscribeTopic(UUID ownerId, UUID processUid, String topicName) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            IpcTopic topic = transaction.ipc().findTopic(topicName)
                    .filter(candidate -> candidate.status() == IpcTopic.Status.ACTIVE)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown active topic"));
            requireProcess(transaction, processUid);
            Instant now = clock.instant();
            IpcSubscription subscription = new IpcSubscription(UUID.randomUUID(), ownerId,
                    processUid, IpcSubscription.SourceKind.TOPIC, Optional.empty(),
                    Optional.of(topic.topicId()), IpcSubscription.Status.ACTIVE, now,
                    Optional.empty());
            transaction.ipc().saveSubscription(subscription);
            return subscription;
        });
    }

    private IpcSubscription subscribe(UUID ownerId, UUID processUid,
                                      IpcSubscription.SourceKind kind,
                                      Optional<UUID> channelId,
                                      Optional<UUID> topicId) {
        Instant now = clock.instant();
        IpcSubscription subscription = new IpcSubscription(UUID.randomUUID(), ownerId,
                processUid, kind, channelId, topicId, IpcSubscription.Status.ACTIVE,
                now, Optional.empty());
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            requireProcess(transaction, processUid);
            if (kind == IpcSubscription.SourceKind.CHANNEL) {
                transaction.ipc().findChannel(channelId.orElseThrow())
                        .filter(channel -> channel.status() == IpcChannel.Status.ACTIVE)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown active channel"));
            }
            transaction.ipc().saveSubscription(subscription);
            return subscription;
        });
    }

    public IpcMessage sendDirect(UUID ownerId, Optional<UUID> senderProcessUid,
                                 UUID receiverProcessUid, Payload payload,
                                 Optional<Instant> expiresAt) {
        return send(ownerId, senderProcessUid, IpcMessage.Kind.DIRECT, Optional.empty(),
                Optional.empty(), List.of(receiverProcessUid), payload, expiresAt);
    }

    public IpcMessage sendChannel(UUID ownerId, Optional<UUID> senderProcessUid,
                                  UUID channelId, Payload payload,
                                  Optional<Instant> expiresAt) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            IpcChannel channel = transaction.ipc().findChannel(channelId)
                    .filter(candidate -> candidate.status() == IpcChannel.Status.ACTIVE)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown active channel"));
            UUID receiver = transaction.ipc().selectChannelReceiver(channel.channelId())
                    .orElseThrow(() -> new IllegalStateException("Channel has no active consumer"));
            return persist(transaction, ownerId, senderProcessUid, IpcMessage.Kind.CHANNEL,
                    Optional.of(channelId), Optional.empty(), List.of(receiver), payload,
                    expiresAt, clock.instant());
        });
    }

    public IpcMessage publishTopic(UUID ownerId, Optional<UUID> senderProcessUid,
                                   String topicName, Payload payload,
                                   Optional<Instant> expiresAt) {
        return publish(ownerId, senderProcessUid, topicName, IpcMessage.Kind.TOPIC,
                payload, expiresAt);
    }

    public IpcMessage broadcast(UUID ownerId, Optional<UUID> senderProcessUid,
                                String topicName, Payload payload,
                                Optional<Instant> expiresAt) {
        return publish(ownerId, senderProcessUid, topicName, IpcMessage.Kind.BROADCAST,
                payload, expiresAt);
    }

    private IpcMessage publish(UUID ownerId, Optional<UUID> senderProcessUid,
                               String topicName, IpcMessage.Kind kind, Payload payload,
                               Optional<Instant> expiresAt) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            IpcTopic topic = transaction.ipc().findTopic(topicName)
                    .filter(candidate -> candidate.status() == IpcTopic.Status.ACTIVE)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown active topic"));
            List<UUID> receivers = transaction.ipc().findTopicReceivers(topic.topicId());
            return persist(transaction, ownerId, senderProcessUid, kind, Optional.empty(),
                    Optional.of(topic.name()), receivers, payload, expiresAt, clock.instant());
        });
    }

    private IpcMessage send(UUID ownerId, Optional<UUID> senderProcessUid, IpcMessage.Kind kind,
                            Optional<UUID> channelId, Optional<String> topicName,
                            List<UUID> receivers, Payload payload, Optional<Instant> expiresAt) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                persist(transaction, ownerId, senderProcessUid, kind, channelId, topicName,
                        receivers, payload, expiresAt, clock.instant()));
    }

    private static IpcMessage persist(
            com.follarce.domain.port.TransactionContext transaction,
            UUID ownerId,
            Optional<UUID> senderProcessUid,
            IpcMessage.Kind kind,
            Optional<UUID> channelId,
            Optional<String> topicName,
            List<UUID> receivers,
            Payload payload,
            Optional<Instant> expiresAt,
            Instant now
    ) {
        senderProcessUid.ifPresent(sender -> requireProcess(transaction, sender));
        receivers.forEach(receiver -> requireProcess(transaction, receiver));
        IpcMessage message = new IpcMessage(UUID.randomUUID(), senderProcessUid, kind,
                channelId, topicName, payload.type(), payload.json(), payload.objectHash(),
                now, expiresAt);
        List<IpcDelivery> deliveries = receivers.stream()
                .distinct()
                .map(receiver -> IpcDelivery.pending(UUID.randomUUID(), message.messageId(), receiver))
                .toList();
        transaction.ipc().saveMessage(message);
        transaction.ipc().saveDeliveries(deliveries);
        for (IpcDelivery delivery : deliveries) {
            wakeWaitingReceiver(transaction, delivery, message, now);
        }
        transaction.audit().append(new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                ownerId.toString(), "ipc.send", "ipc.message", message.messageId().toString(),
                AuditEvent.Result.SUCCEEDED,
                Map.of("kind", kind.name(), "deliveries", Integer.toString(deliveries.size())), now));
        return message;
    }

    public Optional<Envelope> reserveNext(UUID ownerId, UUID receiverProcessUid, UUID workerId) {
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            List<IpcDelivery> pending = transaction.ipc().findPending(receiverProcessUid, 1);
            if (pending.isEmpty()) return Optional.empty();
            IpcDelivery reserved = pending.getFirst().reserve(workerId, now);
            if (!transaction.ipc().updateDelivery(reserved, IpcDelivery.Status.PENDING)) {
                return Optional.empty();
            }
            IpcMessage message = transaction.ipc().findMessage(reserved.messageId())
                    .orElseThrow(() -> new IllegalStateException("Delivery message is missing"));
            if (message.isExpiredAt(now)) {
                IpcDelivery dead = reserved.dead(now, "message expired before consumption");
                transaction.ipc().updateDelivery(dead, IpcDelivery.Status.RESERVED);
                return Optional.empty();
            }
            return Optional.of(new Envelope(message, reserved));
        });
    }

    public boolean consume(UUID ownerId, UUID deliveryId) {
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            IpcDelivery reserved = transaction.ipc().findDelivery(deliveryId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown delivery"));
            return transaction.ipc().updateDelivery(reserved.consume(now),
                    IpcDelivery.Status.RESERVED);
        });
    }

    private static CilProcess requireProcess(com.follarce.domain.port.TransactionContext transaction,
                                             UUID processUid) {
        return transaction.processes().findByUid(processUid)
                .orElseThrow(() -> new IllegalArgumentException("Unknown process " + processUid));
    }

    private static void wakeWaitingReceiver(com.follarce.domain.port.TransactionContext transaction,
                                            IpcDelivery delivery, IpcMessage message,
                                            Instant now) {
        UUID receiver = delivery.receiverProcessUid();
        CilProcess current = requireProcess(transaction, receiver);
        if (!isWaitingFor(current, delivery, message)) return;
        // The fast path delivers directly into the durable process inbox. Reserve and
        // consume the receiver-specific row in the same transaction before waking the
        // process, so the ordinary polling path can never observe the same delivery.
        IpcDelivery reserved = delivery.reserve(receiver, now);
        if (!transaction.ipc().updateDelivery(reserved, IpcDelivery.Status.PENDING)) {
            throw new IllegalStateException("Concurrent IPC delivery reservation rejected");
        }
        IpcDelivery consumed = reserved.consume(now);
        if (!transaction.ipc().updateDelivery(consumed, IpcDelivery.Status.RESERVED)) {
            throw new IllegalStateException("Concurrent IPC delivery consumption rejected");
        }
        Continuation source = current.continuation();
        Map<String, Continuation.PersistedValue> variables =
                new java.util.LinkedHashMap<>(source.globalVariables());
        variables.put(ProcessInbox.IPC_RESULT, ipcResult(delivery, message));
        Continuation resumed = source.withoutWait().withGlobalVariables(Map.copyOf(variables));
        CilProcess.Status target = current.status() == CilProcess.Status.PAUSED
                ? CilProcess.Status.PAUSED : CilProcess.Status.READY;
        CilProcess ready = current.commitStatement(resumed, target,
                current.stateVersion(), current.executionEpoch(), now);
        ProcessRepository.UpdateResult updated = transaction.processes().update(ready,
                current.stateVersion(), current.executionEpoch());
        if (updated != ProcessRepository.UpdateResult.UPDATED) {
            throw new IllegalStateException("Concurrent IPC wake rejected: " + updated);
        }
        if (ready.status() == CilProcess.Status.READY) {
            transaction.scheduler().enqueue(new SchedulerQueueEntry(receiver, now, now,
                    SchedulerQueueEntry.Status.READY));
        }
    }

    private static boolean isWaitingFor(CilProcess process, IpcDelivery delivery,
                                        IpcMessage message) {
        if (process.status() != CilProcess.Status.WAITING_IPC
                && process.status() != CilProcess.Status.PAUSED) return false;
        return process.continuation().waitState()
                .filter(wait -> wait.kind() == Continuation.WaitKind.IPC)
                .flatMap(Continuation.WaitState::targetId)
                .map(target -> target.equals(process.identity().processUid())
                        || target.equals(delivery.deliveryId())
                        || target.equals(message.messageId())
                        || message.channelId().map(target::equals).orElse(false))
                .orElse(false);
    }

    private static Continuation.PersistedValue ipcResult(IpcDelivery delivery,
                                                         IpcMessage message) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("deliveryId", delivery.deliveryId().toString());
        value.put("messageId", message.messageId().toString());
        value.put("kind", message.kind().name());
        value.put("payloadType", message.payloadType());
        message.payloadJson().ifPresent(payload -> value.put("payloadJson", payload));
        message.payloadObjectHash().ifPresent(hash -> value.put("payloadObjectHash", hash.value()));
        return new Continuation.PersistedValue(
                "application/vnd.cilexec.ipc-delivery+json", JSON.toJson(value));
    }

    public record Payload(
            String type,
            Optional<String> json,
            Optional<ObjectHash> objectHash
    ) {
        public Payload {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("type is required");
            json = java.util.Objects.requireNonNull(json, "json");
            objectHash = java.util.Objects.requireNonNull(objectHash, "objectHash");
            if (json.isPresent() == objectHash.isPresent()) {
                throw new IllegalArgumentException("payload requires exactly one representation");
            }
        }

        public static Payload json(String type, String canonicalJson) {
            return new Payload(type, Optional.of(canonicalJson), Optional.empty());
        }

        public static Payload object(String type, ObjectHash objectHash) {
            return new Payload(type, Optional.empty(), Optional.of(objectHash));
        }
    }

    public record Envelope(IpcMessage message, IpcDelivery delivery) {
    }
}
