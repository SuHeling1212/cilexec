package com.follarce.persistence.postgres.repository;

import com.follarce.domain.audit.AuditEvent;
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
    public int purgeBeforeByAdministrator(UUID administratorId, java.time.Instant before,
                                          Integer limit) {
        String sql = "SELECT audit.admin_purge_before(?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, administratorId);
            statement.setTimestamp(2, java.sql.Timestamp.from(before));
            if (limit != null) statement.setInt(3, limit); else statement.setNull(3, java.sql.Types.INTEGER);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Audit purge returned no result");
                }
                return rows.getInt(1);
            }
        } catch (SQLException exception) {
            throw failure("audit.purgeBeforeByAdministrator", exception);
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
