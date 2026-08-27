package com.follarce.ipc;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.ipc.IpcChannel;
import com.follarce.domain.ipc.IpcDelivery;
import com.follarce.domain.ipc.IpcMessage;
import com.follarce.domain.ipc.IpcSubscription;
import com.follarce.domain.ipc.IpcTopic;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.port.UserTransactionRunner;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.google.gson.Gson;

/**
 * Durable direct, channel, topic, and broadcast messaging use cases.
 *
 * <p>Every operation has an {@code *In(TransactionContext, ...)} variant that executes
 * inside an already-open transaction (the FCL runtime slice transaction), and a public
 * wrapper that opens its own user transaction for callers outside the runtime.
 */
public final class IpcService {
    private static final Gson JSON = new Gson();
    public static final int MAX_PURGE_BATCH = 10_000;
    private static final int MAX_EXPIRY_SCAN = 100;
    /** Defensive cap on consecutive full batches of expired/contended deliveries. */
    private static final int MAX_EXPIRY_ROUNDS = 10;
    private final UserTransactionRunner transactions;
    private final Clock clock;

    public IpcService(UserTransactionRunner transactions,
                      Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public IpcChannel createChannel(UUID ownerId, String name) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> createChannelIn(transaction, ownerId, name, clock.instant()));
    }

    public static IpcChannel createChannelIn(TransactionContext transaction, UUID ownerId,
                                             String name, Instant now) {
        IpcChannel channel = new IpcChannel(UUID.randomUUID(), ownerId, name,
                IpcChannel.Status.ACTIVE, now, Optional.empty());
        transaction.ipc().saveChannel(channel);
        return channel;
    }

    public IpcTopic createTopic(UUID ownerId, String name) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> createTopicIn(transaction, ownerId, name, clock.instant()));
    }

    public static IpcTopic createTopicIn(TransactionContext transaction, UUID ownerId,
                                         String name, Instant now) {
        IpcTopic topic = new IpcTopic(UUID.randomUUID(), ownerId, name,
                IpcTopic.Status.ACTIVE, now, Optional.empty());
        transaction.ipc().saveTopic(topic);
        return topic;
    }

    public boolean removeChannel(UUID ownerId, UUID channelId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> transaction.ipc().removeChannel(ownerId, channelId));
    }

    public static boolean removeChannelIn(TransactionContext transaction, UUID ownerId,
                                          UUID channelId) {
        return transaction.ipc().removeChannel(ownerId, channelId);
    }

    public boolean removeTopic(UUID ownerId, UUID topicId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> transaction.ipc().removeTopic(ownerId, topicId));
    }

    public static boolean removeTopicIn(TransactionContext transaction, UUID ownerId,
                                        UUID topicId) {
        return transaction.ipc().removeTopic(ownerId, topicId);
    }

    public int purgeMessages(UUID ownerId, Instant olderThan, int limit) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> purgeMessagesIn(transaction, ownerId, olderThan,
                        clock.instant(), limit));
    }

    public static int purgeMessagesIn(TransactionContext transaction, UUID ownerId,
                                      Instant olderThan, Instant now, int limit) {
        if (olderThan == null || now == null || olderThan.isAfter(now)) {
            throw new IllegalArgumentException("IPC purge cutoff must not be in the future");
        }
        if (limit < 1 || limit > MAX_PURGE_BATCH) {
            throw new IllegalArgumentException("IPC purge limit must be between 1 and "
                    + MAX_PURGE_BATCH);
        }
        int deleted = transaction.ipc().purgeMessages(ownerId, olderThan, now, limit);
        transaction.audit().append(new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                ownerId.toString(), "ipc.purge", "ipc.messages", ownerId.toString(),
                AuditEvent.Result.SUCCEEDED,
                Map.of("olderThan", olderThan.toString(), "deleted", Integer.toString(deleted)),
                now));
        return deleted;
    }

    public IpcSubscription subscribeChannel(UUID ownerId, UUID processUid, UUID channelId) {
        return subscribe(ownerId, processUid, IpcSubscription.SourceKind.CHANNEL,
                Optional.of(channelId), Optional.empty());
    }

    public static IpcSubscription subscribeChannelIn(TransactionContext transaction,
                                                     UUID ownerId, UUID processUid,
                                                     UUID channelId, Instant now) {
        return subscribeIn(transaction, ownerId, processUid,
                IpcSubscription.SourceKind.CHANNEL, Optional.of(channelId), Optional.empty(), now);
    }

    public IpcSubscription subscribeTopic(UUID ownerId, UUID processUid, String topicName) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            IpcTopic topic = transaction.ipc().findTopic(topicName)
                    .filter(candidate -> candidate.status() == IpcTopic.Status.ACTIVE)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown active topic"));
            return subscribeIn(transaction, ownerId, processUid,
                    IpcSubscription.SourceKind.TOPIC, Optional.empty(),
                    Optional.of(topic.topicId()), clock.instant());
        });
    }

    public static IpcSubscription subscribeTopicIn(TransactionContext transaction,
                                                   UUID ownerId, UUID processUid,
                                                   String topicName, Instant now) {
        IpcTopic topic = transaction.ipc().findTopic(topicName)
                .filter(candidate -> candidate.status() == IpcTopic.Status.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Unknown active topic"));
        return subscribeIn(transaction, ownerId, processUid,
                IpcSubscription.SourceKind.TOPIC, Optional.empty(), Optional.of(topic.topicId()),
                now);
    }

    private IpcSubscription subscribe(UUID ownerId, UUID processUid,
                                      IpcSubscription.SourceKind kind,
                                      Optional<UUID> channelId,
                                      Optional<UUID> topicId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                subscribeIn(transaction, ownerId, processUid, kind, channelId, topicId,
                        clock.instant()));
    }

    private static IpcSubscription subscribeIn(TransactionContext transaction, UUID ownerId,
                                               UUID processUid,
                                               IpcSubscription.SourceKind kind,
                                               Optional<UUID> channelId,
                                               Optional<UUID> topicId, Instant now) {
        IpcSubscription subscription = new IpcSubscription(UUID.randomUUID(), ownerId,
                processUid, kind, channelId, topicId, IpcSubscription.Status.ACTIVE,
                now, Optional.empty());
        requireProcess(transaction, processUid);
        if (kind == IpcSubscription.SourceKind.CHANNEL) {
            transaction.ipc().findChannel(channelId.orElseThrow())
                    .filter(channel -> channel.status() == IpcChannel.Status.ACTIVE)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown active channel"));
        }
        transaction.ipc().saveSubscription(subscription);
        return subscription;
    }

    public IpcMessage sendDirect(UUID ownerId, Optional<UUID> senderProcessUid,
                                 UUID receiverProcessUid, Payload payload,
                                 Optional<Instant> expiresAt) {
        return send(ownerId, senderProcessUid, IpcMessage.Kind.DIRECT, Optional.empty(),
                Optional.empty(), List.of(receiverProcessUid), payload, expiresAt);
    }

    public static IpcMessage sendDirectIn(TransactionContext transaction, UUID ownerId,
                                          Optional<UUID> senderProcessUid,
                                          UUID receiverProcessUid, Payload payload,
                                          Optional<Instant> expiresAt, Instant now) {
        return persist(transaction, ownerId, senderProcessUid, IpcMessage.Kind.DIRECT,
                Optional.empty(), Optional.empty(), List.of(receiverProcessUid), payload,
                expiresAt, now);
    }

    public IpcMessage sendChannel(UUID ownerId, Optional<UUID> senderProcessUid,
                                  UUID channelId, Payload payload,
                                  Optional<Instant> expiresAt) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                sendChannelIn(transaction, ownerId, senderProcessUid, channelId, payload,
                        expiresAt, clock.instant()));
    }

    public static IpcMessage sendChannelIn(TransactionContext transaction, UUID ownerId,
                                           Optional<UUID> senderProcessUid, UUID channelId,
                                           Payload payload, Optional<Instant> expiresAt,
                                           Instant now) {
        IpcChannel channel = transaction.ipc().findChannel(channelId)
                .filter(candidate -> candidate.status() == IpcChannel.Status.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Unknown active channel"));
        UUID receiver = transaction.ipc().selectChannelReceiver(channel.channelId())
                .orElseThrow(() -> new IllegalStateException("Channel has no active consumer"));
        return persist(transaction, ownerId, senderProcessUid, IpcMessage.Kind.CHANNEL,
                Optional.of(channelId), Optional.empty(), List.of(receiver), payload,
                expiresAt, now);
    }

    public IpcMessage publishTopic(UUID ownerId, Optional<UUID> senderProcessUid,
                                   String topicName, Payload payload,
                                   Optional<Instant> expiresAt) {
        return publish(ownerId, senderProcessUid, topicName, IpcMessage.Kind.TOPIC,
                payload, expiresAt);
    }

    public static IpcMessage publishTopicIn(TransactionContext transaction, UUID ownerId,
                                            Optional<UUID> senderProcessUid, String topicName,
                                            Payload payload, Optional<Instant> expiresAt,
                                            Instant now) {
        return publishIn(transaction, ownerId, senderProcessUid, topicName,
                IpcMessage.Kind.TOPIC, payload, expiresAt, now);
    }

    public IpcMessage broadcast(UUID ownerId, Optional<UUID> senderProcessUid,
                                String topicName, Payload payload,
                                Optional<Instant> expiresAt) {
        return publish(ownerId, senderProcessUid, topicName, IpcMessage.Kind.BROADCAST,
                payload, expiresAt);
    }

    public static IpcMessage broadcastIn(TransactionContext transaction, UUID ownerId,
                                         Optional<UUID> senderProcessUid, String topicName,
                                         Payload payload, Optional<Instant> expiresAt,
                                         Instant now) {
        return publishIn(transaction, ownerId, senderProcessUid, topicName,
                IpcMessage.Kind.BROADCAST, payload, expiresAt, now);
    }

    private IpcMessage publish(UUID ownerId, Optional<UUID> senderProcessUid,
                               String topicName, IpcMessage.Kind kind, Payload payload,
                               Optional<Instant> expiresAt) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                publishIn(transaction, ownerId, senderProcessUid, topicName, kind, payload,
                        expiresAt, clock.instant()));
    }

    private static IpcMessage publishIn(TransactionContext transaction, UUID ownerId,
                                        Optional<UUID> senderProcessUid, String topicName,
                                        IpcMessage.Kind kind, Payload payload,
                                        Optional<Instant> expiresAt, Instant now) {
        IpcTopic topic = transaction.ipc().findTopic(topicName)
                .filter(candidate -> candidate.status() == IpcTopic.Status.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Unknown active topic"));
        List<UUID> receivers = transaction.ipc().findTopicReceivers(topic.topicId());
        return persist(transaction, ownerId, senderProcessUid, kind, Optional.empty(),
                Optional.of(topic.name()), receivers, payload, expiresAt, now);
    }

    private IpcMessage send(UUID ownerId, Optional<UUID> senderProcessUid, IpcMessage.Kind kind,
                            Optional<UUID> channelId, Optional<String> topicName,
                            List<UUID> receivers, Payload payload, Optional<Instant> expiresAt) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                persist(transaction, ownerId, senderProcessUid, kind, channelId, topicName,
                        receivers, payload, expiresAt, clock.instant()));
    }

    private static IpcMessage persist(
            TransactionContext transaction,
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
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                reserveNextIn(transaction, ownerId, receiverProcessUid, workerId,
                        clock.instant()));
    }

    public static Optional<Envelope> reserveNextIn(TransactionContext transaction,
                                                   UUID ownerId, UUID receiverProcessUid,
                                                   UUID workerId, Instant now) {
        // Scan in batches: deliveries that expire or are contended stop being PENDING, so
        // every round moves the cursor forward and a live message behind expired ones is
        // still found. MAX_EXPIRY_ROUNDS is only a defensive cap against a caller that
        // keeps adding deliveries faster than the scan drains them.
        for (int round = 0; round < MAX_EXPIRY_ROUNDS; round++) {
            List<IpcDelivery> pending = transaction.ipc().findPending(
                    receiverProcessUid, MAX_EXPIRY_SCAN);
            if (pending.isEmpty()) return Optional.empty();
            for (IpcDelivery candidate : pending) {
                IpcDelivery reserved = candidate.reserve(workerId, now);
                if (!transaction.ipc().updateDelivery(reserved, IpcDelivery.Status.PENDING)) {
                    continue;
                }
                IpcMessage message = transaction.ipc().findMessage(reserved.messageId())
                        .orElseThrow(() -> new IllegalStateException("Delivery message is missing"));
                if (message.isExpiredAt(now)) {
                    IpcDelivery dead = reserved.dead(now, "message expired before consumption");
                    transaction.ipc().updateDelivery(dead, IpcDelivery.Status.RESERVED);
                    continue;
                }
                return Optional.of(new Envelope(message, reserved));
            }
        }
        return Optional.empty();
    }

    /**
     * Blocking-receive reservation. The receiver process is serialized with the sender
     * wake path (see {@link com.follarce.domain.port.IpcRepository#lockReceiverProcess});
     * the caller persists the durable IPC wait in the same transaction after this returns
     * empty, so a delivery committed before the wait state is never lost to a
     * check-before-sleep race.
     */
    public static Optional<Envelope> receiveIn(TransactionContext transaction,
                                               UUID ownerId, UUID receiverProcessUid,
                                               UUID workerId, Instant now) {
        transaction.ipc().lockReceiverProcess(receiverProcessUid);
        return reserveNextIn(transaction, ownerId, receiverProcessUid, workerId, now);
    }

    public boolean consume(UUID ownerId, UUID deliveryId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                consumeIn(transaction, ownerId, deliveryId));
    }

    public static boolean consumeIn(TransactionContext transaction, UUID ownerId,
                                    UUID deliveryId) {
        IpcDelivery reserved = transaction.ipc().findDelivery(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown delivery"));
        return transaction.ipc().updateDelivery(reserved.consume(Instant.now()),
                IpcDelivery.Status.RESERVED);
    }

    private static CilProcess requireProcess(TransactionContext transaction,
                                             UUID processUid) {
        return transaction.processes().findByUid(processUid)
                .orElseThrow(() -> new IllegalArgumentException("Unknown process " + processUid));
    }

    private static void wakeWaitingReceiver(TransactionContext transaction,
                                            IpcDelivery delivery, IpcMessage message,
                                            Instant now) {
        UUID receiver = delivery.receiverProcessUid();
        // Serialize with the receiver's own reservation/wait-state transaction: the wake
        // decision must observe either the committed WAITING_IPC row or the reservation
        // performed inside that same transaction, never a stale pre-wait snapshot.
        transaction.ipc().lockReceiverProcess(receiver);
        CilProcess current = requireProcess(transaction, receiver);
        if (!isWaitingFor(current, delivery, message)) return;
        // The fast path delivers directly into the durable process inbox. The process row is
        // updated first so a concurrent modification leaves the delivery PENDING for the
        // polling path; a lost wake must never roll back the persisted send.
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
            return;
        }
        IpcDelivery reserved = delivery.reserve(receiver, now);
        if (!transaction.ipc().updateDelivery(reserved, IpcDelivery.Status.PENDING)) {
            return;
        }
        IpcDelivery consumed = reserved.consume(now);
        if (!transaction.ipc().updateDelivery(consumed, IpcDelivery.Status.RESERVED)) {
            return;
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
        return new Continuation.PersistedValue(
                "application/vnd.cilexec.ipc-delivery+json", JSON.toJson(envelopeMap(delivery, message)));
    }

    /** Stable delivery envelope shared by the wake fast path and the polling path. */
    public static Map<String, Object> envelopeMap(IpcDelivery delivery, IpcMessage message) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("deliveryId", delivery.deliveryId().toString());
        value.put("messageId", message.messageId().toString());
        value.put("kind", message.kind().name());
        value.put("payloadType", message.payloadType());
        message.payloadJson().ifPresent(payload -> value.put("payloadJson", payload));
        message.payloadObjectHash().ifPresent(hash -> value.put("payloadObjectHash", hash.value()));
        return value;
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
