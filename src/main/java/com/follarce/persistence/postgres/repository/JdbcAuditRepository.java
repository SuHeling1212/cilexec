package com.follarce.persistence.postgres.repository;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.audit.AuditRetentionPolicy;
import com.follarce.domain.port.AuditRepository;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import com.google.gson.JsonElement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class JdbcAuditRepository extends JdbcRepositorySupport implements AuditRepository {
    private final JsonCodec json;

    public JdbcAuditRepository(Connection connection, JsonCodec json) {
        super(connection);
        this.json = json;
    }

    @Override
    public void append(AuditEvent event) {
        String sql = "INSERT INTO audit.event(event_id,owner_id,actor_type,actor_id,action,resource_type,"
                + "resource_id,result,details_json,created_at) VALUES (?,auth.current_cilexec_user_id(),"
                + "?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, event.eventId());
            statement.setString(2, event.actorType().name());
            statement.setString(3, event.actorId());
            statement.setString(4, event.action());
            statement.setString(5, event.resourceType());
            statement.setString(6, event.resourceId());
            statement.setString(7, event.result().name());
            statement.setObject(8, com.follarce.persistence.postgres.mapper.JdbcValues.json(json.write(event.details())));
            statement.setTimestamp(9, java.sql.Timestamp.from(event.createdAt()));
            requireOne("audit.append", statement.executeUpdate());
            notifyWork("cilexec_timer_work", "audit.append.notify");
        } catch (SQLException exception) {
            throw failure("audit.append", exception);
        }
    }

    @Override
    public List<AuditEvent> findByResource(String resourceType, String resourceId, int limit) {
        String sql = "SELECT * FROM audit.event WHERE resource_type=? AND resource_id=? "
                + "ORDER BY created_at DESC,event_id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, resourceType);
            statement.setString(2, resourceId);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<AuditEvent> events = new ArrayList<>();
                while (rows.next()) events.add(map(rows));
                return List.copyOf(events);
            }
        } catch (SQLException exception) {
            throw failure("audit.findByResource", exception);
        }
    }

    @Override
    public void saveRetentionPolicy(AuditRetentionPolicy policy) {
        String sql = "INSERT INTO audit.retention_policy(event_type,retain_for,enabled,updated_at) "
                + "VALUES (?,CAST(? AS interval),?,?) ON CONFLICT (event_type) DO UPDATE SET "
                + "retain_for=EXCLUDED.retain_for,enabled=EXCLUDED.enabled,"
                + "updated_at=EXCLUDED.updated_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, policy.eventType());
            statement.setString(2, policy.retainForSeconds() + " seconds");
            statement.setBoolean(3, policy.enabled());
            statement.setTimestamp(4, java.sql.Timestamp.from(policy.updatedAt()));
            requireOne("audit.saveRetentionPolicy", statement.executeUpdate());
            notifyWork("cilexec_timer_work", "audit.retention.notify");
        } catch (SQLException exception) {
            throw failure("audit.saveRetentionPolicy", exception);
        }
    }

    @Override
    public Optional<AuditRetentionPolicy> findRetentionPolicy(String eventType) {
        String sql = "SELECT event_type,EXTRACT(EPOCH FROM retain_for)::bigint "
                + "AS retain_for_seconds,enabled,updated_at "
                + "FROM audit.retention_policy WHERE event_type=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventType);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapPolicy(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("audit.findRetentionPolicy", exception);
        }
    }

    @Override
    public int purgeExpired(int limit) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT audit.purge_expired_events(?)")) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Audit purge returned no result");
                }
                return rows.getInt(1);
            }
        } catch (SQLException exception) {
            throw failure("audit.purgeExpired", exception);
        }
    }

    @Override
    public Optional<java.time.Instant> nextRetentionExpiry() {
        String sql = "SELECT min(candidate.created_at + policy.retain_for) "
                + "FROM audit.event candidate JOIN audit.retention_policy policy "
                + "ON policy.event_type=candidate.action WHERE policy.enabled";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getTimestamp(1) == null) return Optional.empty();
            return Optional.of(rows.getTimestamp(1).toInstant());
        } catch (SQLException exception) {
            throw failure("audit.nextRetentionExpiry", exception);
        }
    }

    private AuditEvent map(ResultSet rows) throws SQLException {
        Map<String, String> details = readDetails(rows.getString("details_json"));
        return new AuditEvent(
                rows.getObject("event_id", UUID.class),
                AuditEvent.ActorType.valueOf(rows.getString("actor_type")),
                rows.getString("actor_id"),
                rows.getString("action"),
                rows.getString("resource_type"),
                rows.getString("resource_id"),
                AuditEvent.Result.valueOf(rows.getString("result")),
                details,
                rows.getTimestamp("created_at").toInstant()
        );
    }

    private static AuditRetentionPolicy mapPolicy(ResultSet rows) throws SQLException {
        return new AuditRetentionPolicy(
                rows.getString("event_type"),
                rows.getLong("retain_for_seconds"),
                rows.getBoolean("enabled"),
                rows.getTimestamp("updated_at").toInstant()
        );
    }

    private Map<String, String> readDetails(String source) {
        JsonElement root = json.read(source, JsonElement.class);
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("Audit details must be a JSON object");
        }
        Map<String, String> details = new LinkedHashMap<>();
        root.getAsJsonObject().entrySet().forEach(entry -> {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Audit detail values must be JSON strings");
            }
            details.put(entry.getKey(), value.getAsString());
        });
        return Map.copyOf(details);
    }
}
