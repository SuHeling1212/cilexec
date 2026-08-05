package com.follarce.persistence.postgres.repository;

import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.persistence.postgres.error.PersistenceFailure;
import com.follarce.persistence.postgres.error.SqlStateClassifier;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import com.follarce.persistence.postgres.mapper.JsonCodec;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Semantic recovery after PostgreSQL has completed its own WAL recovery. */
public final class RecoveryCoordinator {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RecoveryCoordinator.class);

    private final DataSource dataSource;
    private final JsonCodec json = new JsonCodec();

    public RecoveryCoordinator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public RecoveryReport recover(UUID currentBootId, Instant now) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int fencedRunners = execute(connection,
                    "UPDATE scheduler.runner SET status='FENCED',heartbeat_at=? "
                            + "WHERE boot_id<>? AND status IN ('STARTING','ACTIVE','DRAINING')",
                    now, currentBootId);
            int releasedLeases = execute(connection,
                    "DELETE FROM scheduler.lease WHERE boot_id<>?", currentBootId);

            ProcessRecoveryResult processRecovery = recoverProcesses(connection, now);
            rebuildReadyQueue(connection, now);

            int recoveredDeliveries = execute(connection,
                    "UPDATE ipc.delivery SET status='PENDING',reserved_by=NULL,reserved_at=NULL "
                            + "WHERE status='RESERVED' AND consumed_at IS NULL");
            int recoveredTimers = execute(connection,
                    "UPDATE process.timer SET status='SCHEDULED',claimed_by=NULL,claimed_at=NULL "
                            + "WHERE status='CLAIMED' AND fired_at IS NULL");
            int resetEffects = execute(connection,
                    "UPDATE effect.effect SET status='PREPARED',claimed_by=NULL,claimed_at=NULL,"
                            + "updated_at=? WHERE status='CLAIMED'", now);
            int unknownEffects = execute(connection,
                    "UPDATE effect.effect SET status='UNKNOWN',failure_code='RUNTIME_CRASH',"
                            + "failure_message='Runtime stopped after effect execution began',"
                            + "updated_at=? WHERE status='EXECUTING'", now);
            int unknownAttempts = execute(connection,
                    "UPDATE effect.attempt AS attempt SET status='UNKNOWN',finished_at=?,"
                            + "error_code='RUNTIME_CRASH',"
                            + "error_message='Runtime stopped after effect execution began' "
                            + "FROM scheduler.runner AS runner "
                            + "WHERE attempt.runner_id=runner.runner_id AND runner.boot_id<>? "
                            + "AND attempt.status IN ('CLAIMED','EXECUTING')",
                    now, currentBootId);
            connection.commit();
            return new RecoveryReport(fencedRunners, releasedLeases,
                    processRecovery.recovered(), processRecovery.failed(), recoveredDeliveries,
                    recoveredTimers, resetEffects, unknownEffects, unknownAttempts);
        } catch (SQLException exception) {
            throw SqlStateClassifier.classify("runtime.semanticRecovery", exception);
        }
    }

    private ProcessRecoveryResult recoverProcesses(Connection connection, Instant now)
            throws SQLException {
        List<UUID> candidates = lockRecoverableProcesses(connection);
        JdbcProcessRepository processes = new JdbcProcessRepository(connection, json);
        int recovered = 0;
        int failed = 0;
        for (UUID processUid : candidates) {
            CilProcess process;
            try {
                process = processes.findByUid(processUid)
                        .orElseThrow(() -> new IllegalStateException(
                                "Recovery candidate disappeared"));
            } catch (RuntimeException failure) {
                if (failure instanceof PersistenceFailure) throw failure;
                if (isDeterministicCorruption(failure)) {
                    markFailedRecovery(connection, processUid, now, failure);
                    failed++;
                } else {
                    LOG.warn("Skipping recovery of process {} after {}",
                            processUid, failureReason(failure));
                }
                continue;
            }
            try {
                Instant transitionAt = now.isBefore(process.updatedAt())
                        ? process.updatedAt() : now;
                CilProcess recoveredProcess = switch (process.status()) {
                    case RUNNING -> process.transitionTo(CilProcess.Status.READY, transitionAt);
                    case TERMINATING -> process.transitionTo(CilProcess.Status.TERMINATED,
                            transitionAt);
                    default -> null;
                };
                if (recoveredProcess == null) continue;
                ProcessRepository.UpdateResult result = processes.update(recoveredProcess,
                        process.stateVersion(), process.executionEpoch());
                if (result != ProcessRepository.UpdateResult.UPDATED) {
                    LOG.warn("Skipping recovery of process {} after rejected compare-and-set ({})",
                            processUid, result);
                    continue;
                }
                if (recoveredProcess.status() == CilProcess.Status.TERMINATED) {
                    execute(connection,
                            "DELETE FROM process.timer WHERE process_uid=? AND fired_at IS NULL",
                            processUid);
                }
                recovered++;
            } catch (RuntimeException failure) {
                if (failure instanceof PersistenceFailure) throw failure;
                if (isDeterministicCorruption(failure)) {
                    markFailedRecovery(connection, processUid, now, failure);
                    failed++;
                } else {
                    LOG.warn("Skipping recovery of process {} after {}",
                            processUid, failureReason(failure));
                }
            }
        }
        return new ProcessRecoveryResult(recovered, failed);
    }

    private static List<UUID> lockRecoverableProcesses(Connection connection)
            throws SQLException {
        String sql = "SELECT process_uid FROM process.process WHERE status NOT IN "
                + "('TERMINATED','FAILED','FAILED_RECOVERY') ORDER BY process_uid FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<UUID> processUids = new ArrayList<>();
            while (rows.next()) {
                processUids.add(rows.getObject("process_uid", UUID.class));
            }
            return List.copyOf(processUids);
        }
    }

    private void markFailedRecovery(Connection connection, UUID processUid, Instant now,
                                    RuntimeException failure) throws SQLException {
        String reason = failureReason(failure);
        RecoveryShell shell = loadRecoveryShell(connection, processUid);
        Instant failedAt = now.isBefore(shell.updatedAt()) ? shell.updatedAt() : now;
        Continuation safeContinuation = new Continuation(shell.programId(), shell.programHash(),
                shell.programCounter(), List.of(), List.of(), List.of(), List.of(),
                Optional.empty(), Map.of(), Map.of(), shell.languageVersion(),
                Integer.toString(shell.runtimeFormatVersion()));
        CilProcess failedProcess = new CilProcess(
                new ProcessIdentity(processUid, shell.pid()), shell.ownerId(),
                CilProcess.Status.FAILED_RECOVERY, shell.stateVersion() + 1,
                shell.executionEpoch(), safeContinuation, shell.parentProcessUid(),
                shell.createdAt(), failedAt);
        String update = "UPDATE process.process SET status='FAILED_RECOVERY',"
                + "state_version=state_version+1,wait_reason=NULL,wait_object_id=NULL,"
                + "updated_at=?,terminated_at=?,failure_code='CONTINUATION_CORRUPT',"
                + "failure_message=?,continuation_json=? WHERE process_uid=? "
                + "AND state_version=? AND execution_epoch=?";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setTimestamp(1, java.sql.Timestamp.from(failedAt));
            statement.setTimestamp(2, java.sql.Timestamp.from(failedAt));
            statement.setString(3, reason);
            statement.setObject(4, JdbcValues.json(json.write(safeContinuation)));
            statement.setObject(5, processUid);
            statement.setLong(6, shell.stateVersion());
            statement.setLong(7, shell.executionEpoch());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Recovery candidate disappeared", "40001");
            }
        }
        new JdbcProcessProjectionStore(connection, json).replace(failedProcess);
        execute(connection, "DELETE FROM scheduler.queue WHERE process_uid=?", processUid);
        appendFailureEvent(connection, processUid, failedAt, reason);
    }

    private static RecoveryShell loadRecoveryShell(Connection connection, UUID processUid)
            throws SQLException {
        String sql = "SELECT p.pid,p.owner_id,p.program_id,p.parent_process_uid,"
                + "p.program_counter,p.state_version,p.execution_epoch,p.runtime_format_version,"
                + "p.language_version,p.created_at,p.updated_at,program.program_hash "
                + "FROM process.process AS p JOIN program.program AS program "
                + "ON program.program_id=p.program_id AND program.owner_id=p.owner_id "
                + "WHERE p.process_uid=? FOR UPDATE OF p";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Recovery candidate disappeared", "40001");
                }
                return new RecoveryShell(rows.getLong("pid"),
                        rows.getObject("owner_id", UUID.class),
                        rows.getObject("program_id", UUID.class),
                        Optional.ofNullable(rows.getObject("parent_process_uid", UUID.class)),
                        rows.getInt("program_counter"), rows.getLong("state_version"),
                        rows.getLong("execution_epoch"),
                        rows.getInt("runtime_format_version"),
                        rows.getString("language_version"),
                        rows.getTimestamp("created_at").toInstant(),
                        rows.getTimestamp("updated_at").toInstant(),
                        JdbcValues.hash(rows.getBytes("program_hash")));
            }
        }
    }

    private void appendFailureEvent(Connection connection, UUID processUid, Instant now,
                                    String reason) throws SQLException {
        String sql = "INSERT INTO process.event(event_id,process_uid,owner_id,event_type,"
                + "state_version,details_json,created_at) SELECT ?,process_uid,owner_id,"
                + "'RECOVERY_VALIDATION_FAILED',state_version,?,? FROM process.process "
                + "WHERE process_uid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, JdbcValues.json(json.write(Map.of(
                    "failureCode", "CONTINUATION_CORRUPT", "reason", reason))));
            statement.setTimestamp(3, java.sql.Timestamp.from(now));
            statement.setObject(4, processUid);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Failed recovery event was not appended", "40001");
            }
        }
    }

    private static void rebuildReadyQueue(Connection connection, Instant now)
            throws SQLException {
        execute(connection,
                "UPDATE scheduler.queue AS queue SET queue_state='REMOVED',"
                        + "claimed_by=NULL,claimed_at=NULL FROM process.process AS process "
                        + "WHERE process.process_uid=queue.process_uid "
                        + "AND process.status<>'READY'");
        execute(connection,
                "INSERT INTO scheduler.queue(process_uid,owner_id,queue_state,ready_at,enqueued_at) "
                        + "SELECT process_uid,owner_id,'READY',?,? FROM process.process "
                        + "WHERE status='READY' ON CONFLICT (process_uid) DO UPDATE SET "
                        + "queue_state='READY',ready_at=EXCLUDED.ready_at,"
                        + "enqueued_at=LEAST(scheduler.queue.enqueued_at,EXCLUDED.enqueued_at),"
                        + "claimed_by=NULL,claimed_at=NULL",
                now, now);
    }

    private static String failureReason(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return failure.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Only failures that are guaranteed to repeat identically are treated as durable
     * corruption. IllegalStateException is deliberately excluded: a one-off state race
     * during recovery must not sentence a process to permanent FAILED_RECOVERY — a
     * genuinely corrupt continuation surfaces again as a visible FCL failure when the
     * process next runs.
     */
    private static boolean isDeterministicCorruption(RuntimeException failure) {
        return failure instanceof IllegalArgumentException
                || failure instanceof ClassCastException
                || failure instanceof com.google.gson.JsonParseException;
    }

    private static int execute(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                if (value instanceof Instant instant) {
                    statement.setTimestamp(index + 1, java.sql.Timestamp.from(instant));
                } else {
                    statement.setObject(index + 1, value);
                }
            }
            return statement.executeUpdate();
        }
    }

    private record ProcessRecoveryResult(int recovered, int failed) {
    }

    private record RecoveryShell(
            long pid,
            UUID ownerId,
            UUID programId,
            Optional<UUID> parentProcessUid,
            int programCounter,
            long stateVersion,
            long executionEpoch,
            int runtimeFormatVersion,
            String languageVersion,
            Instant createdAt,
            Instant updatedAt,
            ObjectHash programHash
    ) {
    }

    public record RecoveryReport(
            int fencedRunners,
            int releasedLeases,
            int recoveredProcesses,
            int failedRecoveryProcesses,
            int recoveredDeliveries,
            int recoveredTimers,
            int resetEffects,
            int unknownEffects,
            int unknownAttempts
    ) {
    }
}
