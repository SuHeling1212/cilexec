package com.follarce.persistence.postgres.repository;

import com.follarce.domain.effect.EffectAttempt;
import com.follarce.domain.effect.EffectPayload;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.port.EffectRepository;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.vfs.ObjectHash;
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

/** PostgreSQL effect journal and per-invocation attempt history. */
public final class JdbcEffectRepository extends JdbcRepositorySupport implements EffectRepository {
    private final JsonCodec json;

    public JdbcEffectRepository(Connection connection, JsonCodec json) {
        super(connection);
        this.json = json;
    }

    @Override
    public void registerWorker(UUID workerId, UUID bootId, Instant now) {
        String sql = "INSERT INTO scheduler.runner(runner_id,boot_id,runner_kind,status,"
                + "started_at,heartbeat_at) VALUES (?,?,'EFFECT','ACTIVE',?,?) "
                + "ON CONFLICT (runner_id) DO UPDATE SET status='ACTIVE',"
                + "heartbeat_at=EXCLUDED.heartbeat_at,stopped_at=NULL "
                + "WHERE scheduler.runner.boot_id=EXCLUDED.boot_id "
                + "AND scheduler.runner.runner_kind='EFFECT'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, workerId);
            statement.setObject(2, bootId);
            statement.setTimestamp(3, java.sql.Timestamp.from(now));
            statement.setTimestamp(4, java.sql.Timestamp.from(now));
            requireOne("effect.registerWorker", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("effect.registerWorker", exception);
        }
    }

    @Override
    public void save(EffectRequest effect) {
        String sql = "INSERT INTO effect.effect(effect_id,process_uid,owner_id,effect_type,"
                + "idempotency_key,idempotent,remote_status_queryable,retry_policy_json,status,"
                + "request_json,request_object_hash,claimed_by,prepared_at,claimed_at,executing_at,"
                + "result_json,result_object_hash,failure_code,failure_message,updated_at) "
                + "SELECT ?,?,owner_id,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? FROM process.process "
                + "WHERE process_uid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindEffect(effect, statement);
            statement.setObject(20, effect.processUid());
            requireOne("effect.save", statement.executeUpdate());
            notifyWork("cilexec_effect_work", "effect.notify");
        } catch (SQLException exception) {
            throw failure("effect.save", exception);
        }
    }

    @Override
    public Optional<EffectRequest> findById(UUID effectId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM effect.effect WHERE effect_id=?")) {
            statement.setObject(1, effectId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapEffect(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("effect.findById", exception);
        }
    }

    @Override
    public List<EffectRequest> claimPending(UUID workerId, Instant now, int limit) {
        String sql = "WITH pending AS (SELECT effect_id FROM effect.effect "
                + "WHERE status='PREPARED' ORDER BY prepared_at,effect_id "
                + "FOR UPDATE SKIP LOCKED LIMIT ?) UPDATE effect.effect AS candidate "
                + "SET status='CLAIMED',claimed_by=?,claimed_at=?,updated_at=? FROM pending "
                + "WHERE candidate.effect_id=pending.effect_id RETURNING candidate.*";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setObject(2, workerId);
            statement.setTimestamp(3, java.sql.Timestamp.from(now));
            statement.setTimestamp(4, java.sql.Timestamp.from(now));
            try (ResultSet rows = statement.executeQuery()) {
                List<EffectRequest> effects = new ArrayList<>();
                while (rows.next()) {
                    effects.add(mapEffect(rows));
                }
                return List.copyOf(effects);
            }
        } catch (SQLException exception) {
            throw failure("effect.claimPending", exception);
        }
    }

    @Override
    public boolean update(EffectRequest effect, EffectRequest.Status expectedStatus) {
        String sql = "UPDATE effect.effect SET status=?,idempotency_key=?,idempotent=?,"
                + "remote_status_queryable=?,retry_policy_json=?,claimed_by=?,claimed_at=?,"
                + "executing_at=?,result_json=?,result_object_hash=?,"
                + "completed_at=CASE WHEN ?='COMPLETED' THEN ? "
                + "ELSE completed_at END,failure_code=CASE WHEN ? IN ('FAILED','UNKNOWN') "
                + "THEN ? ELSE NULL END,failure_message=?,updated_at=? "
                + "WHERE effect_id=? AND status=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, effect.status().name());
            optionalString(statement, 2, effect.policy().idempotencyKey());
            statement.setBoolean(3, effect.policy().idempotent());
            statement.setBoolean(4, effect.policy().remotelyQueryable());
            statement.setObject(5, JdbcValues.json(json.write(effect.policy())));
            JdbcValues.nullableUuid(statement, 6, effect.claimedBy());
            JdbcValues.nullableInstant(statement, 7, effect.claimedAt());
            JdbcValues.nullableInstant(statement, 8, effect.executionStartedAt());
            optionalJson(statement, 9, effect.result().map(json::write));
            optionalHash(statement, 10, effect.resultObjectHash());
            statement.setString(11, effect.status().name());
            statement.setTimestamp(12, java.sql.Timestamp.from(effect.updatedAt()));
            statement.setString(13, effect.status().name());
            statement.setString(14, effect.status().name());
            optionalString(statement, 15, effect.failureReason());
            statement.setTimestamp(16, java.sql.Timestamp.from(effect.updatedAt()));
            statement.setObject(17, effect.effectId());
            statement.setString(18, expectedStatus.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("effect.update", exception);
        }
    }

    @Override
    public boolean resolveUnknownManually(EffectRequest effect) {
        if (effect.status() != EffectRequest.Status.COMPLETED
                && effect.status() != EffectRequest.Status.FAILED) {
            throw new IllegalArgumentException(
                    "Manual UNKNOWN resolution must complete or fail the effect");
        }
        String sql = "SELECT effect.resolve_unknown(?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, effect.effectId());
            statement.setString(2, effect.status().name());
            optionalJson(statement, 3, effect.result().map(json::write));
            optionalHash(statement, 4, effect.resultObjectHash());
            optionalString(statement, 5, effect.failureReason());
            statement.setTimestamp(6, java.sql.Timestamp.from(effect.updatedAt()));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw failure("effect.resolveUnknownManually", exception);
        }
    }

    @Override
    public List<EffectRequest> claimRecoverableUnknown(UUID workerId, Instant now, int limit) {
        String sql = "WITH recoverable AS (SELECT candidate.effect_id "
                + "FROM effect.effect AS candidate LEFT JOIN scheduler.runner AS previous "
                + "ON previous.runner_id=candidate.claimed_by "
                + "WHERE candidate.status='UNKNOWN' "
                + "AND candidate.retry_policy_json->>'unknownAction' "
                + "IN ('QUERY_REMOTE','RETRY_IDEMPOTENT') "
                + "AND (previous.runner_id IS NULL OR previous.status IN ('STOPPED','FENCED')) "
                + "ORDER BY candidate.updated_at,candidate.effect_id "
                + "FOR UPDATE OF candidate SKIP LOCKED LIMIT ?) "
                + "UPDATE effect.effect AS candidate SET claimed_by=?,claimed_at=?,updated_at=? "
                + "FROM recoverable WHERE candidate.effect_id=recoverable.effect_id "
                + "RETURNING candidate.*";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setObject(2, workerId);
            statement.setTimestamp(3, java.sql.Timestamp.from(now));
            statement.setTimestamp(4, java.sql.Timestamp.from(now));
            try (ResultSet rows = statement.executeQuery()) {
                List<EffectRequest> effects = new ArrayList<>();
                while (rows.next()) effects.add(mapEffect(rows));
                return List.copyOf(effects);
            }
        } catch (SQLException exception) {
            throw failure("effect.claimRecoverableUnknown", exception);
        }
    }

    @Override
    public int nextAttemptNumber(UUID effectId) {
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT effect_id FROM effect.effect WHERE effect_id=? FOR UPDATE")) {
            lock.setObject(1, effectId);
            try (ResultSet rows = lock.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("Unknown effect " + effectId);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(max(attempt_number),0)+1 FROM effect.attempt "
                            + "WHERE effect_id=?")) {
                statement.setObject(1, effectId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException("Attempt sequence returned no value");
                    }
                    return rows.getInt(1);
                }
            }
        } catch (SQLException exception) {
            throw failure("effect.nextAttemptNumber", exception);
        }
    }

    @Override
    public void saveAttempt(EffectAttempt attempt) {
        String sql = "INSERT INTO effect.attempt(attempt_id,effect_id,owner_id,attempt_number,"
                + "runner_id,status,started_at,finished_at,remote_reference,result_json,error_code,"
                + "error_message) SELECT ?,?,owner_id,?,?,?,?,?,?,?,?,? FROM effect.effect "
                + "WHERE effect_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, attempt.attemptId());
            statement.setObject(2, attempt.effectId());
            statement.setInt(3, attempt.attemptNumber());
            statement.setObject(4, attempt.runnerId());
            statement.setString(5, attempt.status().name());
            statement.setTimestamp(6, java.sql.Timestamp.from(attempt.startedAt()));
            JdbcValues.nullableInstant(statement, 7, attempt.finishedAt());
            optionalString(statement, 8, attempt.remoteReference());
            optionalJson(statement, 9, attempt.result().map(json::write));
            optionalString(statement, 10, attempt.errorCode());
            optionalString(statement, 11, attempt.errorMessage());
            statement.setObject(12, attempt.effectId());
            requireOne("effect.saveAttempt", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("effect.saveAttempt", exception);
        }
    }

    @Override
    public Optional<EffectAttempt> findAttempt(UUID attemptId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM effect.attempt WHERE attempt_id=?")) {
            statement.setObject(1, attemptId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapAttempt(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("effect.findAttempt", exception);
        }
    }

    @Override
    public List<EffectAttempt> findAttempts(UUID effectId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM effect.attempt WHERE effect_id=? ORDER BY attempt_number")) {
            statement.setObject(1, effectId);
            try (ResultSet rows = statement.executeQuery()) {
                List<EffectAttempt> attempts = new ArrayList<>();
                while (rows.next()) {
                    attempts.add(mapAttempt(rows));
                }
                return List.copyOf(attempts);
            }
        } catch (SQLException exception) {
            throw failure("effect.findAttempts", exception);
        }
    }

    @Override
    public boolean updateAttempt(EffectAttempt attempt, EffectAttempt.Status expectedStatus) {
        String sql = "UPDATE effect.attempt SET status=?,finished_at=?,remote_reference=?,"
                + "result_json=?,error_code=?,error_message=? WHERE attempt_id=? AND status=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attempt.status().name());
            JdbcValues.nullableInstant(statement, 2, attempt.finishedAt());
            optionalString(statement, 3, attempt.remoteReference());
            optionalJson(statement, 4, attempt.result().map(json::write));
            optionalString(statement, 5, attempt.errorCode());
            optionalString(statement, 6, attempt.errorMessage());
            statement.setObject(7, attempt.attemptId());
            statement.setString(8, expectedStatus.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("effect.updateAttempt", exception);
        }
    }

    private void bindEffect(EffectRequest effect, PreparedStatement statement)
            throws SQLException {
        statement.setObject(1, effect.effectId());
        statement.setObject(2, effect.processUid());
        statement.setString(3, effect.effectType());
        optionalString(statement, 4, effect.policy().idempotencyKey());
        statement.setBoolean(5, effect.policy().idempotent());
        statement.setBoolean(6, effect.policy().remotelyQueryable());
        statement.setObject(7, JdbcValues.json(json.write(effect.policy())));
        statement.setString(8, effect.status().name());
        optionalJson(statement, 9, effect.requestJson().map(json::write));
        optionalHash(statement, 10, effect.requestObjectHash());
        JdbcValues.nullableUuid(statement, 11, effect.claimedBy());
        statement.setTimestamp(12, java.sql.Timestamp.from(effect.createdAt()));
        JdbcValues.nullableInstant(statement, 13, effect.claimedAt());
        JdbcValues.nullableInstant(statement, 14, effect.executionStartedAt());
        optionalJson(statement, 15, effect.result().map(json::write));
        optionalHash(statement, 16, effect.resultObjectHash());
        if (effect.failureReason().isPresent()) {
            statement.setString(17, effect.status().name());
        } else {
            statement.setNull(17, java.sql.Types.VARCHAR);
        }
        optionalString(statement, 18, effect.failureReason());
        statement.setTimestamp(19, java.sql.Timestamp.from(effect.updatedAt()));
    }

    private EffectRequest mapEffect(ResultSet rows) throws SQLException {
        String requestJson = rows.getString("request_json");
        byte[] requestHash = rows.getBytes("request_object_hash");
        String resultJson = rows.getString("result_json");
        byte[] resultHash = rows.getBytes("result_object_hash");
        return new EffectRequest(
                rows.getObject("effect_id", UUID.class),
                rows.getObject("process_uid", UUID.class),
                rows.getString("effect_type"),
                payload(requestJson, requestHash, "request"),
                json.read(rows.getString("retry_policy_json"), EffectRequest.Policy.class),
                EffectRequest.Status.valueOf(rows.getString("status")),
                JdbcValues.optionalUuid(rows, "claimed_by"),
                JdbcValues.optionalInstant(rows, "claimed_at"),
                JdbcValues.optionalInstant(rows, "executing_at"),
                resultJson == null && resultHash == null ? Optional.empty()
                        : Optional.of(payload(resultJson, resultHash, "result")),
                JdbcValues.optionalString(rows, "failure_message"),
                rows.getTimestamp("prepared_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant()
        );
    }

    private EffectPayload payload(String valueJson, byte[] objectHash, String field) {
        if ((valueJson == null) == (objectHash == null)) {
            throw new IllegalStateException("Effect " + field
                    + " must contain exactly one representation");
        }
        if (valueJson != null) {
            return EffectPayload.json(json.read(valueJson, Continuation.PersistedValue.class));
        }
        return EffectPayload.object(JdbcValues.hash(objectHash));
    }

    private EffectAttempt mapAttempt(ResultSet rows) throws SQLException {
        String resultJson = rows.getString("result_json");
        return new EffectAttempt(
                rows.getObject("attempt_id", UUID.class),
                rows.getObject("effect_id", UUID.class),
                rows.getInt("attempt_number"),
                rows.getObject("runner_id", UUID.class),
                EffectAttempt.Status.valueOf(rows.getString("status")),
                rows.getTimestamp("started_at").toInstant(),
                JdbcValues.optionalInstant(rows, "finished_at"),
                JdbcValues.optionalString(rows, "remote_reference"),
                resultJson == null ? Optional.empty()
                        : Optional.of(json.read(resultJson, Continuation.PersistedValue.class)),
                JdbcValues.optionalString(rows, "error_code"),
                JdbcValues.optionalString(rows, "error_message")
        );
    }

    private static void optionalString(PreparedStatement statement, int index,
                                       Optional<String> value) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.get());
        } else {
            statement.setNull(index, java.sql.Types.VARCHAR);
        }
    }

    private static void optionalJson(PreparedStatement statement, int index,
                                     Optional<String> value) throws SQLException {
        if (value.isPresent()) {
            statement.setObject(index, JdbcValues.json(value.get()));
        } else {
            statement.setNull(index, java.sql.Types.OTHER);
        }
    }

    private static void optionalHash(PreparedStatement statement, int index,
                                     Optional<ObjectHash> value) throws SQLException {
        if (value.isPresent()) {
            statement.setBytes(index, JdbcValues.hash(value.orElseThrow()));
        } else {
            statement.setNull(index, java.sql.Types.BINARY);
        }
    }
}
