package com.follarce.domain.port;

import com.follarce.domain.ipc.IpcDelivery;
import com.follarce.domain.ipc.IpcChannel;
import com.follarce.domain.ipc.IpcMessage;
import com.follarce.domain.ipc.IpcSubscription;
import com.follarce.domain.ipc.IpcTopic;
import com.follarce.domain.process.Continuation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IpcRepository {
    void saveChannel(IpcChannel channel);

    Optional<IpcChannel> findChannel(UUID channelId);

    void saveTopic(IpcTopic topic);

    Optional<IpcTopic> findTopic(String topicName);

    void saveSubscription(IpcSubscription subscription);

    Optional<UUID> selectChannelReceiver(UUID channelId);

    List<UUID> findTopicReceivers(UUID topicId);

    void saveMessage(IpcMessage message);

    void saveDeliveries(List<IpcDelivery> deliveries);

    Optional<IpcMessage> findMessage(UUID messageId);

    Optional<IpcDelivery> findDelivery(UUID deliveryId);

    List<IpcDelivery> findPending(UUID receiverProcessUid, int limit);

    boolean updateDelivery(IpcDelivery delivery, IpcDelivery.Status expectedStatus);

    /**
     * Removes a channel owned by this user together with its messages (deliveries cascade);
     * returns whether the channel existed.
     */
    default boolean removeChannel(UUID ownerId, UUID channelId) {
        throw new UnsupportedOperationException("Channels are not implemented");
    }

    /**
     * Removes a topic owned by this user together with its subscriptions; returns whether
     * the topic existed. Historical messages keep their topic name text and are retained.
     */
    default boolean removeTopic(UUID ownerId, UUID topicId) {
        throw new UnsupportedOperationException("Topics are not implemented");
    }

    default boolean createSwapPool(UUID ownerId, UUID ownerProcessUid, String poolName,
                                   Instant createdAt) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default boolean removeSwapPool(UUID ownerId, UUID ownerProcessUid, String poolName) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default boolean swapPoolExists(UUID ownerId, String poolName) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default List<String> findSwapPools(UUID ownerId) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default List<String> findSwapVariables(UUID ownerId, String poolName) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default boolean addSwapValue(UUID ownerId, String poolName, String variableName,
                                 Continuation.PersistedValue value, String retentionMode,
                                 Optional<Integer> remainingReads, Instant at) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default Optional<Continuation.PersistedValue> consumeSwapValue(UUID ownerId, String poolName,
                                                                    String variableName,
                                                                    Instant at) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default boolean updateSwapValue(UUID ownerId, String poolName, String variableName,
                                    Continuation.PersistedValue value, UUID processUid,
                                    long executionEpoch, Optional<Long> fencingToken, Instant at) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default boolean removeSwapValue(UUID ownerId, String poolName, String variableName,
                                    UUID processUid, long executionEpoch,
                                    Optional<Long> fencingToken) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default int clearSwapPool(UUID ownerId, String poolName, UUID ownerProcessUid) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default Optional<SwapLock> acquireSwapLock(UUID ownerId, String poolName,
                                               String variableName, UUID processUid,
                                               long executionEpoch, Instant leaseUntil,
                                               Instant at) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default Optional<SwapLock> renewSwapLock(UUID ownerId, String poolName,
                                             String variableName, UUID processUid,
                                             long executionEpoch, long fencingToken,
                                             Instant leaseUntil, Instant at) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default boolean releaseSwapLock(UUID ownerId, String poolName, String variableName,
                                    UUID processUid, long executionEpoch, long fencingToken) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default boolean signalSwapValue(UUID ownerId, String poolName, String variableName,
                                    Instant at) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    default boolean consumeSwapSignal(UUID ownerId, String poolName, String variableName) {
        throw new UnsupportedOperationException("Swap pools are not implemented");
    }

    record SwapLock(long fencingToken, Instant leaseUntil) {}
}
