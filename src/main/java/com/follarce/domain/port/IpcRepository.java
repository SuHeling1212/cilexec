package com.follarce.domain.port;

import com.follarce.domain.ipc.IpcDelivery;
import com.follarce.domain.ipc.IpcChannel;
import com.follarce.domain.ipc.IpcMessage;
import com.follarce.domain.ipc.IpcSubscription;
import com.follarce.domain.ipc.IpcTopic;

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
}
