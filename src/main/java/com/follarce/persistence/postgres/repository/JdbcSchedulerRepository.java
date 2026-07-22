package com.follarce.persistence.postgres.repository;

import com.follarce.domain.port.SchedulerRepository;
import com.follarce.domain.scheduler.SchedulerClaim;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class JdbcSchedulerRepository extends JdbcRepositorySupport implements SchedulerRepository {
    public JdbcSchedulerRepository(Connection connection) {
        super(connection);
    }

    @Override
    public void enqueue(SchedulerQueueEntry entry) {
        String sql = "INSERT INTO scheduler.queue(process_uid,owner_id,queue_state,ready_at,enqueued_at) "
                + "SELECT ?, owner_id, ?, ?, ? FROM process.process WHERE process_uid=? "
                + "ON CONFLICT (process_uid) DO UPDATE SET queue_state=EXCLUDED.queue_state,"
                + "ready_at=EXCLUDED.ready_at,enqueued_at=EXCLUDED.enqueued_at,claimed_at=NULL,claimed_by=NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, entry.processUid());
            statement.setString(2, entry.status().name());
            statement.setTimestamp(3, java.sql.Timestamp.from(entry.readyAt()));
            statement.setTimestamp(4, java.sql.Timestamp.from(entry.enqueuedAt()));
            statement.setObject(5, entry.processUid());
            requireOne("scheduler.enqueue", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("scheduler.enqueue", exception);
        }
    }

    @Override
    public Optional<SchedulerClaim> claimNext(UUID runnerId, UUID bootId, Instant now,
                                              Duration leaseDuration) {
        String select = "SELECT queue.process_uid, queue.owner_id FROM scheduler.queue AS queue "
                + "JOIN process.process AS process ON process.process_uid=queue.process_uid "
                + "WHERE queue.queue_state='READY' AND queue.ready_at<=? AND process.status='READY' "
                + "ORDER BY queue.enqueued_at, queue.process_uid FOR UPDATE OF queue SKIP LOCKED LIMIT 1";
        try {
            ensureRunner(runnerId, bootId, now);
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                statement.setTimestamp(1, java.sql.Timestamp.from(now));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    UUID processUid = rows.getObject("process_uid", UUID.class);
                    UUID ownerId = rows.getObject("owner_id", UUID.class);
                    long epoch = claimProcess(processUid, now);
                    markQueueClaimed(processUid, runnerId, now);
                    Instant expires = now.plus(leaseDuration);
                    insertLease(processUid, ownerId, runnerId, bootId, epoch, now, expires);
                    return Optional.of(new SchedulerClaim(processUid, ownerId, runnerId, bootId, epoch,
                            now, now, expires));
                }
            }
        } catch (SQLException exception) {
            throw failure("process.claimNext", exception);
        }
    }

    @Override
    public boolean heartbeat(SchedulerClaim claim) {
        String sql = "SELECT scheduler.heartbeat_process(?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, claim.processUid());
            statement.setObject(2, claim.ownerId());
            statement.setObject(3, claim.runnerId());
            statement.setObject(4, claim.bootId());
            statement.setLong(5, claim.executionEpoch());
            statement.setTimestamp(6, java.sql.Timestamp.from(claim.heartbeatAt()));
            statement.setTimestamp(7, java.sql.Timestamp.from(claim.expiresAt()));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw failure("scheduler.heartbeat", exception);
        }
    }

    @Override
    public void release(UUID processUid, long executionEpoch) {
        String release = "SELECT scheduler.release_process(?,?)";
        try (PreparedStatement statement = connection.prepareStatement(release)) {
            statement.setObject(1, processUid);
            statement.setLong(2, executionEpoch);
            try (ResultSet ignored = statement.executeQuery()) {
                // PostgreSQL executes the release before returning the void row.
            }
        } catch (SQLException exception) {
            throw failure("scheduler.release", exception);
        }
    }

    @Override
    public int releaseExpired(Instant now) {
        String sql = "WITH expired AS (DELETE FROM scheduler.lease WHERE expires_at<=? "
                + "RETURNING process_uid,execution_epoch), recovered AS ("
                + "UPDATE process.process p SET status='READY',state_version=state_version+1,updated_at=? "
                + "FROM expired e WHERE p.process_uid=e.process_uid AND p.execution_epoch=e.execution_epoch "
                + "AND p.status='RUNNING' RETURNING p.process_uid), queued AS ("
                + "UPDATE scheduler.queue q SET queue_state='READY',claimed_by=NULL,claimed_at=NULL,"
                + "ready_at=?,enqueued_at=? FROM recovered r WHERE q.process_uid=r.process_uid RETURNING q.process_uid) "
                + "SELECT count(*) FROM queued";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            var timestamp = java.sql.Timestamp.from(now);
            statement.setTimestamp(1, timestamp);
            statement.setTimestamp(2, timestamp);
            statement.setTimestamp(3, timestamp);
            statement.setTimestamp(4, timestamp);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (SQLException exception) {
            throw failure("scheduler.releaseExpired", exception);
        }
    }

    private void ensureRunner(UUID runnerId, UUID bootId, Instant now) throws SQLException {
        String sql = "INSERT INTO scheduler.runner(runner_id,boot_id,runner_kind,status,started_at,heartbeat_at) "
                + "VALUES (?,?,'SCHEDULER','ACTIVE',?,?) ON CONFLICT (runner_id) DO UPDATE "
                + "SET status='ACTIVE',heartbeat_at=EXCLUDED.heartbeat_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runnerId);
            statement.setObject(2, bootId);
            statement.setTimestamp(3, java.sql.Timestamp.from(now));
            statement.setTimestamp(4, java.sql.Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private long claimProcess(UUID processUid, Instant now) throws SQLException {
        String sql = "UPDATE process.process SET status='RUNNING',execution_epoch=execution_epoch+1,"
                + "state_version=state_version+1,updated_at=? WHERE process_uid=? AND status='READY' "
                + "RETURNING execution_epoch";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, java.sql.Timestamp.from(now));
            statement.setObject(2, processUid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Claimed queue row lost READY process", "40001");
                }
                return rows.getLong(1);
            }
        }
    }

    private void markQueueClaimed(UUID processUid, UUID runnerId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE scheduler.queue SET queue_state='CLAIMED',claimed_by=?,claimed_at=? WHERE process_uid=?")) {
            statement.setObject(1, runnerId);
            statement.setTimestamp(2, java.sql.Timestamp.from(now));
            statement.setObject(3, processUid);
            requireOne("scheduler.markClaimed", statement.executeUpdate());
        }
    }

    private void insertLease(UUID processUid, UUID ownerId, UUID runnerId, UUID bootId,
                             long epoch, Instant now, Instant expires) throws SQLException {
        String sql = "INSERT INTO scheduler.lease(process_uid,owner_id,runner_id,boot_id,execution_epoch,"
                + "claimed_at,heartbeat_at,expires_at) VALUES (?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (process_uid) DO UPDATE SET owner_id=EXCLUDED.owner_id,runner_id=EXCLUDED.runner_id,"
                + "boot_id=EXCLUDED.boot_id,execution_epoch=EXCLUDED.execution_epoch,claimed_at=EXCLUDED.claimed_at,"
                + "heartbeat_at=EXCLUDED.heartbeat_at,expires_at=EXCLUDED.expires_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            statement.setObject(2, ownerId);
            statement.setObject(3, runnerId);
            statement.setObject(4, bootId);
            statement.setLong(5, epoch);
            statement.setTimestamp(6, java.sql.Timestamp.from(now));
            statement.setTimestamp(7, java.sql.Timestamp.from(now));
            statement.setTimestamp(8, java.sql.Timestamp.from(expires));
            statement.executeUpdate();
        }
    }
}
