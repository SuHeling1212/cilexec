package com.follarce.persistence.postgres.repository;

import com.follarce.domain.port.TimerRepository;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.timer.ProcessTimer;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcTimerRepository extends JdbcRepositorySupport implements TimerRepository {
    private final JsonCodec json;

    public JdbcTimerRepository(Connection connection, JsonCodec json) {
        super(connection);
        this.json = json;
    }

    @Override
    public void save(ProcessTimer timer) {
        String sql = "INSERT INTO process.timer(timer_id,process_uid,owner_id,wake_at,status,claimed_by,"
                + "claimed_at,created_at,fired_at,cancelled_at,payload_json) "
                + "SELECT ?,?,owner_id,?,?,?,?,?,?,?,? FROM process.process WHERE process_uid=? "
                + "ON CONFLICT (timer_id) DO UPDATE SET status=EXCLUDED.status,claimed_by=EXCLUDED.claimed_by,"
                + "claimed_at=EXCLUDED.claimed_at,fired_at=EXCLUDED.fired_at,cancelled_at=EXCLUDED.cancelled_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, timer.timerId());
            statement.setObject(2, timer.processUid());
            statement.setTimestamp(3, java.sql.Timestamp.from(timer.wakeAt()));
            statement.setString(4, timer.status().name());
            JdbcValues.nullableUuid(statement, 5, timer.claimedBy());
            JdbcValues.nullableInstant(statement, 6, timer.claimedAt());
            statement.setTimestamp(7, java.sql.Timestamp.from(timer.createdAt()));
            JdbcValues.nullableInstant(statement, 8, timer.firedAt());
            if (timer.status() == ProcessTimer.Status.CANCELLED) {
                statement.setTimestamp(9, java.sql.Timestamp.from(timer.claimedAt().orElse(timer.createdAt())));
            } else {
                statement.setNull(9, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            }
            statement.setObject(10, JdbcValues.json(json.write(timer.payload().orElse(null))));
            statement.setObject(11, timer.processUid());
            requireOne("timer.save", statement.executeUpdate());
            if (timer.status() == ProcessTimer.Status.SCHEDULED) {
                notifyWork("cilexec_timer_work", "timer.notify");
            }
        } catch (SQLException exception) {
            throw failure("timer.save", exception);
        }
    }

    @Override
    public Optional<ProcessTimer> findById(UUID timerId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM process.timer WHERE timer_id=?")) {
            statement.setObject(1, timerId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("timer.findById", exception);
        }
    }

    @Override
    public List<ProcessTimer> claimDue(UUID runnerId, Instant now, int limit) {
        String sql = "WITH due AS (SELECT timer_id FROM process.timer WHERE status='SCHEDULED' "
                + "AND wake_at<=? ORDER BY wake_at,timer_id FOR UPDATE SKIP LOCKED LIMIT ?) "
                + "UPDATE process.timer timer SET status='CLAIMED',claimed_by=?,claimed_at=? "
                + "FROM due WHERE timer.timer_id=due.timer_id RETURNING timer.*";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, java.sql.Timestamp.from(now));
            statement.setInt(2, limit);
            statement.setObject(3, runnerId);
            statement.setTimestamp(4, java.sql.Timestamp.from(now));
            try (ResultSet rows = statement.executeQuery()) {
                List<ProcessTimer> timers = new ArrayList<>();
                while (rows.next()) timers.add(map(rows));
                return List.copyOf(timers);
            }
        } catch (SQLException exception) {
            throw failure("timer.claimDue", exception);
        }
    }

    @Override
    public Optional<Instant> nextScheduledWakeAt() {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT min(wake_at) FROM process.timer WHERE status='SCHEDULED'");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getTimestamp(1) == null) return Optional.empty();
            return Optional.of(rows.getTimestamp(1).toInstant());
        } catch (SQLException exception) {
            throw failure("timer.nextScheduledWakeAt", exception);
        }
    }

    @Override
    public boolean update(ProcessTimer timer, ProcessTimer.Status expectedStatus) {
        String sql = "UPDATE process.timer SET status=?,claimed_by=?,claimed_at=?,fired_at=?,"
                + "cancelled_at=CASE WHEN ?='CANCELLED' THEN clock_timestamp() ELSE cancelled_at END "
                + "WHERE timer_id=? AND status=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, timer.status().name());
            JdbcValues.nullableUuid(statement, 2, timer.claimedBy());
            JdbcValues.nullableInstant(statement, 3, timer.claimedAt());
            JdbcValues.nullableInstant(statement, 4, timer.firedAt());
            statement.setString(5, timer.status().name());
            statement.setObject(6, timer.timerId());
            statement.setString(7, expectedStatus.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("timer.update", exception);
        }
    }

    private ProcessTimer map(ResultSet rows) throws SQLException {
        String payload = rows.getString("payload_json");
        Optional<Continuation.PersistedValue> value = payload == null || "null".equals(payload)
                ? Optional.empty()
                : Optional.of(json.read(payload, Continuation.PersistedValue.class));
        return new ProcessTimer(
                rows.getObject("timer_id", UUID.class),
                rows.getObject("process_uid", UUID.class),
                rows.getTimestamp("wake_at").toInstant(),
                ProcessTimer.Status.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                JdbcValues.optionalUuid(rows, "claimed_by"),
                JdbcValues.optionalInstant(rows, "claimed_at"),
                JdbcValues.optionalInstant(rows, "fired_at"),
                value
        );
    }
}
