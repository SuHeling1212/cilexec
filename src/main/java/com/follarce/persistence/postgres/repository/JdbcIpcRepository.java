package com.follarce.persistence.postgres.repository;

import com.follarce.domain.ipc.IpcDelivery;
import com.follarce.domain.ipc.IpcChannel;
import com.follarce.domain.ipc.IpcMessage;
import com.follarce.domain.ipc.IpcSubscription;
import com.follarce.domain.ipc.IpcTopic;
import com.follarce.domain.port.IpcRepository;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcIpcRepository extends JdbcRepositorySupport implements IpcRepository {
    public JdbcIpcRepository(Connection connection) {
        super(connection);
    }

    @Override
    public void saveChannel(IpcChannel channel) {
        String sql = "INSERT INTO ipc.channel(channel_id,owner_id,channel_name,status,created_at,closed_at) "
                + "VALUES (?,?,?,?,?,?) ON CONFLICT (channel_id) DO UPDATE SET "
                + "channel_name=EXCLUDED.channel_name,status=EXCLUDED.status,closed_at=EXCLUDED.closed_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, channel.channelId());
            statement.setObject(2, channel.ownerId());
            statement.setString(3, channel.name());
            statement.setString(4, channel.status().name());
            statement.setTimestamp(5, java.sql.Timestamp.from(channel.createdAt()));
            JdbcValues.nullableInstant(statement, 6, channel.closedAt());
            requireOne("ipc.saveChannel", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("ipc.saveChannel", exception);
        }
    }

    @Override
    public Optional<IpcChannel> findChannel(UUID channelId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ipc.channel WHERE channel_id=?")) {
            statement.setObject(1, channelId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapChannel(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("ipc.findChannel", exception);
        }
    }

    @Override
    public void saveTopic(IpcTopic topic) {
        String sql = "INSERT INTO ipc.topic(topic_id,owner_id,topic_name,status,created_at,closed_at) "
                + "VALUES (?,?,?,?,?,?) ON CONFLICT (topic_id) DO UPDATE SET "
                + "topic_name=EXCLUDED.topic_name,status=EXCLUDED.status,closed_at=EXCLUDED.closed_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, topic.topicId());
            statement.setObject(2, topic.ownerId());
            statement.setString(3, topic.name());
            statement.setString(4, topic.status().name());
            statement.setTimestamp(5, java.sql.Timestamp.from(topic.createdAt()));
            JdbcValues.nullableInstant(statement, 6, topic.closedAt());
            requireOne("ipc.saveTopic", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("ipc.saveTopic", exception);
        }
    }

    @Override
    public Optional<IpcTopic> findTopic(String topicName) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ipc.topic WHERE topic_name=?")) {
            statement.setString(1, topicName);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapTopic(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("ipc.findTopic", exception);
        }
    }

    @Override
    public void saveSubscription(IpcSubscription subscription) {
        String sql = "INSERT INTO ipc.subscription(subscription_id,owner_id,subscriber_process_uid,"
                + "source_kind,channel_id,topic_id,status,created_at,cancelled_at) VALUES (?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (subscription_id) DO UPDATE SET status=EXCLUDED.status,"
                + "cancelled_at=EXCLUDED.cancelled_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, subscription.subscriptionId());
            statement.setObject(2, subscription.ownerId());
            statement.setObject(3, subscription.subscriberProcessUid());
            statement.setString(4, subscription.sourceKind().name());
            JdbcValues.nullableUuid(statement, 5, subscription.channelId());
            JdbcValues.nullableUuid(statement, 6, subscription.topicId());
            statement.setString(7, subscription.status().name());
            statement.setTimestamp(8, java.sql.Timestamp.from(subscription.createdAt()));
            JdbcValues.nullableInstant(statement, 9, subscription.cancelledAt());
            requireOne("ipc.saveSubscription", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("ipc.saveSubscription", exception);
        }
    }

    @Override
    public Optional<UUID> selectChannelReceiver(UUID channelId) {
        String sql = "SELECT subscriber_process_uid FROM ipc.subscription "
                + "WHERE source_kind='CHANNEL' AND channel_id=? AND status='ACTIVE' "
                + "ORDER BY created_at,subscription_id FOR SHARE SKIP LOCKED LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, channelId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("ipc.selectChannelReceiver", exception);
        }
    }

    @Override
    public List<UUID> findTopicReceivers(UUID topicId) {
        String sql = "SELECT subscriber_process_uid FROM ipc.subscription "
                + "WHERE source_kind='TOPIC' AND topic_id=? AND status='ACTIVE' "
                + "ORDER BY created_at,subscription_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, topicId);
            try (ResultSet rows = statement.executeQuery()) {
                List<UUID> receivers = new ArrayList<>();
                while (rows.next()) receivers.add(rows.getObject(1, UUID.class));
                return List.copyOf(receivers);
            }
        } catch (SQLException exception) {
            throw failure("ipc.findTopicReceivers", exception);
        }
    }

    @Override
    public void saveMessage(IpcMessage message) {
        String sql = "INSERT INTO ipc.message(message_id,owner_id,sender_process_uid,message_kind,channel_id,"
                + "topic_name,payload_type,payload_json,payload_object_hash,created_at,expires_at) "
                + "SELECT ?,COALESCE((SELECT owner_id FROM process.process WHERE process_uid=?),"
                + "auth.current_cilexec_user_id()),?,?,?,?,?,?,?,?,?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, message.messageId());
            JdbcValues.nullableUuid(statement, 2, message.senderProcessUid());
            JdbcValues.nullableUuid(statement, 3, message.senderProcessUid());
            statement.setString(4, message.kind().name());
            JdbcValues.nullableUuid(statement, 5, message.channelId());
            if (message.topicName().isPresent()) statement.setString(6, message.topicName().get());
            else statement.setNull(6, java.sql.Types.VARCHAR);
            statement.setString(7, message.payloadType());
            if (message.payloadJson().isPresent()) statement.setObject(8, JdbcValues.json(message.payloadJson().get()));
            else statement.setNull(8, java.sql.Types.OTHER);
            if (message.payloadObjectHash().isPresent()) {
                statement.setBytes(9, JdbcValues.hash(message.payloadObjectHash().get()));
            } else {
                statement.setNull(9, java.sql.Types.BINARY);
            }
            statement.setTimestamp(10, java.sql.Timestamp.from(message.createdAt()));
            JdbcValues.nullableInstant(statement, 11, message.expiresAt());
            requireOne("ipc.saveMessage", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("ipc.saveMessage", exception);
        }
    }

    @Override
    public void saveDeliveries(List<IpcDelivery> deliveries) {
        String sql = "INSERT INTO ipc.delivery(delivery_id,message_id,owner_id,receiver_process_uid,status,"
                + "reserved_by,reserved_at,consumed_at,failed_at,failure_reason) "
                + "SELECT ?,?,owner_id,?,?,?,?,?,?,? FROM ipc.message WHERE message_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (IpcDelivery delivery : deliveries) {
                statement.setObject(1, delivery.deliveryId());
                statement.setObject(2, delivery.messageId());
                statement.setObject(3, delivery.receiverProcessUid());
                statement.setString(4, delivery.status().name());
                JdbcValues.nullableUuid(statement, 5, delivery.reservedBy());
                JdbcValues.nullableInstant(statement, 6, delivery.reservedAt());
                JdbcValues.nullableInstant(statement, 7, delivery.consumedAt());
                JdbcValues.nullableInstant(statement, 8, delivery.failedAt());
                if (delivery.failureReason().isPresent()) statement.setString(9, delivery.failureReason().get());
                else statement.setNull(9, java.sql.Types.VARCHAR);
                statement.setObject(10, delivery.messageId());
                statement.addBatch();
            }
            int[] affected = statement.executeBatch();
            for (int count : affected) {
                requireOne("ipc.saveDelivery", count);
            }
        } catch (SQLException exception) {
            throw failure("ipc.createDeliveries", exception);
        }
    }

    @Override
    public Optional<IpcMessage> findMessage(UUID messageId) {
        String sql = "SELECT message_id,sender_process_uid,message_kind,channel_id,topic_name,payload_type,"
                + "payload_json::text,payload_object_hash,created_at,expires_at FROM ipc.message WHERE message_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, messageId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapMessage(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("ipc.findMessage", exception);
        }
    }

    @Override
    public Optional<IpcDelivery> findDelivery(UUID deliveryId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ipc.delivery WHERE delivery_id=?")) {
            statement.setObject(1, deliveryId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapDelivery(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("ipc.findDelivery", exception);
        }
    }

    @Override
    public List<IpcDelivery> findPending(UUID receiverProcessUid, int limit) {
        String sql = "SELECT * FROM ipc.delivery WHERE receiver_process_uid=? AND status='PENDING' "
                + "ORDER BY created_at,delivery_id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, receiverProcessUid);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<IpcDelivery> deliveries = new ArrayList<>();
                while (rows.next()) deliveries.add(mapDelivery(rows));
                return List.copyOf(deliveries);
            }
        } catch (SQLException exception) {
            throw failure("ipc.findPending", exception);
        }
    }

    @Override
    public boolean updateDelivery(IpcDelivery delivery, IpcDelivery.Status expectedStatus) {
        String sql = "UPDATE ipc.delivery SET status=?,reserved_by=?,reserved_at=?,consumed_at=?,failed_at=?,"
                + "failure_reason=?,delivery_attempts=delivery_attempts+CASE WHEN ?='RESERVED' THEN 1 ELSE 0 END "
                + "WHERE delivery_id=? AND status=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, delivery.status().name());
            JdbcValues.nullableUuid(statement, 2, delivery.reservedBy());
            JdbcValues.nullableInstant(statement, 3, delivery.reservedAt());
            JdbcValues.nullableInstant(statement, 4, delivery.consumedAt());
            JdbcValues.nullableInstant(statement, 5, delivery.failedAt());
            if (delivery.failureReason().isPresent()) statement.setString(6, delivery.failureReason().get());
            else statement.setNull(6, java.sql.Types.VARCHAR);
            statement.setString(7, delivery.status().name());
            statement.setObject(8, delivery.deliveryId());
            statement.setString(9, expectedStatus.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("ipc.updateDelivery", exception);
        }
    }

    private static IpcMessage mapMessage(ResultSet rows) throws SQLException {
        byte[] objectHash = rows.getBytes("payload_object_hash");
        return new IpcMessage(
                rows.getObject("message_id", UUID.class),
                JdbcValues.optionalUuid(rows, "sender_process_uid"),
                IpcMessage.Kind.valueOf(rows.getString("message_kind")),
                JdbcValues.optionalUuid(rows, "channel_id"),
                JdbcValues.optionalString(rows, "topic_name"),
                rows.getString("payload_type"),
                JdbcValues.optionalString(rows, "payload_json"),
                objectHash == null ? Optional.empty() : Optional.of(JdbcValues.hash(objectHash)),
                rows.getTimestamp("created_at").toInstant(),
                JdbcValues.optionalInstant(rows, "expires_at")
        );
    }

    private static IpcChannel mapChannel(ResultSet rows) throws SQLException {
        return new IpcChannel(rows.getObject("channel_id", UUID.class),
                rows.getObject("owner_id", UUID.class), rows.getString("channel_name"),
                IpcChannel.Status.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                JdbcValues.optionalInstant(rows, "closed_at"));
    }

    private static IpcTopic mapTopic(ResultSet rows) throws SQLException {
        return new IpcTopic(rows.getObject("topic_id", UUID.class),
                rows.getObject("owner_id", UUID.class), rows.getString("topic_name"),
                IpcTopic.Status.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                JdbcValues.optionalInstant(rows, "closed_at"));
    }

    private static IpcDelivery mapDelivery(ResultSet rows) throws SQLException {
        return new IpcDelivery(
                rows.getObject("delivery_id", UUID.class),
                rows.getObject("message_id", UUID.class),
                rows.getObject("receiver_process_uid", UUID.class),
                IpcDelivery.Status.valueOf(rows.getString("status")),
                JdbcValues.optionalUuid(rows, "reserved_by"),
                JdbcValues.optionalInstant(rows, "reserved_at"),
                JdbcValues.optionalInstant(rows, "consumed_at"),
                JdbcValues.optionalInstant(rows, "failed_at"),
                JdbcValues.optionalString(rows, "failure_reason")
        );
    }
}
