package com.follarce.persistence.postgres.repository;

import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.scheduler.SchedulerClaim;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import com.follarce.persistence.postgres.mapper.JsonCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable process aggregate and its normalized recovery projections. */
public final class JdbcProcessRepository extends JdbcRepositorySupport
        implements ProcessRepository {
    private static final String COLUMNS = "process_uid,pid,owner_id,program_id,status,"
            + "program_counter,state_version,execution_epoch,wait_reason,wait_object_id,"
            + "runtime_format_version,language_version,continuation_json::text AS continuation_json,"
            + "parent_process_uid,created_at,updated_at";

    private final JsonCodec json;
    private final JdbcProcessProjectionStore projections;

    public JdbcProcessRepository(Connection connection, JsonCodec json) {
        super(connection);
        this.json = json;
        projections = new JdbcProcessProjectionStore(connection, json);
    }

    @Override
    public long allocatePid() {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT nextval('process.pid_sequence')");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("PID sequence returned no value");
            }
            return rows.getLong(1);
        } catch (SQLException exception) {
            throw failure("process.allocatePid", exception);
        }
    }

    @Override
    public Optional<CilProcess> findByUid(UUID processUid) {
        return find("process.findByUid", "SELECT " + COLUMNS
                + " FROM process.process WHERE process_uid=? FOR UPDATE",
                statement -> statement.setObject(1, processUid));
    }

    @Override
    public Optional<CilProcess> findByPid(long pid) {
        return find("process.findByPid", "SELECT " + COLUMNS
                + " FROM process.process WHERE pid=? FOR UPDATE",
                statement -> statement.setLong(1, pid));
    }

    @Override
    public List<CilProcess> findAll() {
        String sql = "SELECT " + COLUMNS + " FROM process.process ORDER BY pid FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<CilProcess> processes = new ArrayList<>();
            while (rows.next()) processes.add(map(readRow(rows)));
            return List.copyOf(processes);
        } catch (SQLException exception) {
            throw failure("process.findAll", exception);
        }
    }

    @Override
    public List<CilProcess> findChildren(UUID parentProcessUid) {
        String sql = "SELECT " + COLUMNS + " FROM process.process "
                + "WHERE parent_process_uid=? ORDER BY pid FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parentProcessUid);
            try (ResultSet rows = statement.executeQuery()) {
                List<CilProcess> children = new ArrayList<>();
                while (rows.next()) children.add(map(readRow(rows)));
                return List.copyOf(children);
            }
        } catch (SQLException exception) {
            throw failure("process.findChildren", exception);
        }
    }

    @Override
    public void insert(CilProcess process) {
        validateStatusWait(process);
        String sql = "INSERT INTO process.process(process_uid,pid,owner_id,program_id,"
                + "parent_process_uid,status,program_counter,state_version,execution_epoch,"
                + "wait_reason,wait_object_id,runtime_format_version,language_version,"
                + "continuation_json,created_at,updated_at,terminated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, process.identity().processUid());
            statement.setLong(2, process.identity().pid());
            statement.setObject(3, process.ownerId());
            statement.setObject(4, process.continuation().programId());
            JdbcValues.nullableUuid(statement, 5, process.parentProcessUid());
            statement.setString(6, process.status().name());
            statement.setInt(7, process.continuation().programCounter());
            statement.setLong(8, process.stateVersion());
            statement.setLong(9, process.executionEpoch());
            optionalWaitReason(statement, 10, process.continuation().waitState());
            optionalWaitObject(statement, 11, process.continuation().waitState());
            statement.setInt(12, runtimeFormat(process.continuation()));
            statement.setString(13, process.continuation().languageVersion());
            statement.setObject(14, JdbcValues.json(json.write(process.continuation())));
            statement.setTimestamp(15, java.sql.Timestamp.from(process.createdAt()));
            statement.setTimestamp(16, java.sql.Timestamp.from(process.updatedAt()));
            if (process.isTerminal()) {
                statement.setTimestamp(17, java.sql.Timestamp.from(process.updatedAt()));
            } else {
                statement.setNull(17, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            requireOne("process.insert", statement.executeUpdate());
            projections.replace(process);
            projections.insertParentRelationships(process);
            projections.appendEvent(process, "PROCESS_CREATED");
        } catch (SQLException exception) {
            throw failure("process.insert", exception);
        }
    }

    @Override
    public UpdateResult update(CilProcess process, long expectedStateVersion,
                               long expectedExecutionEpoch) {
        return updateInternal(process, expectedStateVersion, expectedExecutionEpoch,
                Optional.empty());
    }

    @Override
    public UpdateResult updateClaimed(CilProcess process, long expectedStateVersion,
                                      SchedulerClaim claim) {
        if (!process.identity().processUid().equals(claim.processUid())
                || !process.ownerId().equals(claim.ownerId())
                || process.executionEpoch() != claim.executionEpoch()) {
            throw new IllegalArgumentException("Scheduler claim does not match the process");
        }
        return updateInternal(process, expectedStateVersion, claim.executionEpoch(),
                Optional.of(claim));
    }

    private UpdateResult updateInternal(CilProcess process, long expectedStateVersion,
                                        long expectedExecutionEpoch,
                                        Optional<SchedulerClaim> claim) {
        validateStatusWait(process);
        String sql = "UPDATE process.process SET status=?,program_id=?,program_counter=?,"
                + "state_version=?,execution_epoch=?,wait_reason=?,wait_object_id=?,"
                + "runtime_format_version=?,language_version=?,continuation_json=?,"
                + "parent_process_uid=?,updated_at=?,terminated_at=CASE WHEN ? IN "
                + "('TERMINATED','FAILED','FAILED_RECOVERY') THEN ? ELSE terminated_at END "
                + "WHERE process_uid=? AND state_version=? AND execution_epoch=?"
                + (claim.isPresent()
                ? " AND scheduler.claim_authorizes_commit(?,?,?,?,?)" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, process.status().name());
            statement.setObject(2, process.continuation().programId());
            statement.setInt(3, process.continuation().programCounter());
            statement.setLong(4, process.stateVersion());
            statement.setLong(5, process.executionEpoch());
            optionalWaitReason(statement, 6, process.continuation().waitState());
            optionalWaitObject(statement, 7, process.continuation().waitState());
            statement.setInt(8, runtimeFormat(process.continuation()));
            statement.setString(9, process.continuation().languageVersion());
            statement.setObject(10, JdbcValues.json(json.write(process.continuation())));
            JdbcValues.nullableUuid(statement, 11, process.parentProcessUid());
            statement.setTimestamp(12, java.sql.Timestamp.from(process.updatedAt()));
            statement.setString(13, process.status().name());
            statement.setTimestamp(14, java.sql.Timestamp.from(process.updatedAt()));
            statement.setObject(15, process.identity().processUid());
            statement.setLong(16, expectedStateVersion);
            statement.setLong(17, expectedExecutionEpoch);
            if (claim.isPresent()) {
                SchedulerClaim guard = claim.orElseThrow();
                statement.setObject(18, guard.processUid());
                statement.setObject(19, guard.ownerId());
                statement.setObject(20, guard.runnerId());
                statement.setObject(21, guard.bootId());
                statement.setLong(22, guard.executionEpoch());
            }
            if (statement.executeUpdate() == 1) {
                projections.replace(process);
                projections.appendEvent(process, "STATE_COMMITTED");
                return UpdateResult.UPDATED;
            }
        } catch (SQLException exception) {
            throw failure("process.commitStatement", exception);
        }
        return classifyConflict(process.identity().processUid(), expectedStateVersion,
                expectedExecutionEpoch, claim.isPresent());
    }

    private UpdateResult classifyConflict(UUID processUid, long expectedStateVersion,
                                          long expectedExecutionEpoch,
                                          boolean claimedCommit) {
        String sql = "SELECT state_version,execution_epoch FROM process.process "
                + "WHERE process_uid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong("execution_epoch") != expectedExecutionEpoch) {
                    return UpdateResult.EPOCH_FENCED;
                }
                if (rows.getLong("state_version") != expectedStateVersion) {
                    return UpdateResult.VERSION_CONFLICT;
                }
                return claimedCommit ? UpdateResult.EPOCH_FENCED
                        : UpdateResult.VERSION_CONFLICT;
            }
        } catch (SQLException exception) {
            throw failure("process.classifyConflict", exception);
        }
    }

    private Optional<CilProcess> find(String operation, String sql, Binder binder) {
        try {
            ProcessRow row;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    row = readRow(rows);
                }
            }
            return Optional.of(map(row));
        } catch (SQLException exception) {
            throw failure(operation, exception);
        }
    }

    private ProcessRow readRow(ResultSet rows) throws SQLException {
        return new ProcessRow(
                rows.getObject("process_uid", UUID.class),
                rows.getLong("pid"),
                rows.getObject("owner_id", UUID.class),
                CilProcess.Status.valueOf(rows.getString("status")),
                rows.getLong("state_version"),
                rows.getLong("execution_epoch"),
                json.read(rows.getString("continuation_json"), Continuation.class),
                JdbcValues.optionalUuid(rows, "parent_process_uid"),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getObject("program_id", UUID.class),
                rows.getInt("program_counter"),
                rows.getString("wait_reason"),
                rows.getObject("wait_object_id", UUID.class),
                rows.getInt("runtime_format_version"),
                rows.getString("language_version")
        );
    }

    private CilProcess map(ProcessRow row) throws SQLException {
        Continuation envelope = row.envelope();
        requireProjection(envelope.programId().equals(row.programId()),
                "Program ID disagrees with continuation envelope");
        requireProjection(envelope.programCounter() == row.programCounter(),
                "Program counter disagrees with continuation envelope");
        requireProjection(runtimeFormat(envelope) == row.runtimeFormatVersion(),
                "Runtime format disagrees with continuation envelope");
        requireProjection(envelope.languageVersion().equals(row.languageVersion()),
                "Language version disagrees with continuation envelope");
        validateWaitColumns(envelope.waitState(), row.waitReason(), row.waitObjectId());
        Continuation restored = projections.load(row.processUid(), envelope);
        validateWaitColumns(restored.waitState(), row.waitReason(), row.waitObjectId());
        CilProcess process = new CilProcess(
                new ProcessIdentity(row.processUid(), row.pid()),
                row.ownerId(),
                row.status(),
                row.stateVersion(),
                row.executionEpoch(),
                restored,
                row.parentProcessUid(),
                row.createdAt(),
                row.updatedAt()
        );
        validateStatusWait(process);
        return process;
    }

    private static void validateStatusWait(CilProcess process) {
        Optional<Continuation.WaitState> wait = process.continuation().waitState();
        boolean waitingStatus = switch (process.status()) {
            case WAITING_IPC, WAITING_TIMER, WAITING_EFFECT, WAITING_INPUT -> true;
            default -> false;
        };
        if (process.status() == CilProcess.Status.PAUSED) {
            wait.ifPresent(state -> requireProjection(waitKindMatchesStatus(
                            CilProcess.statusFor(wait), state.kind()),
                    "Paused process contains an invalid wait state"));
            return;
        }
        if (!waitingStatus) {
            requireProjection(wait.isEmpty(), "Non-waiting process contains a wait state");
        } else {
            requireProjection(wait.isPresent() && waitKindMatchesStatus(
                            process.status(), wait.orElseThrow().kind()),
                    "Process status and wait state disagree");
        }
    }

    private static boolean waitKindMatchesStatus(CilProcess.Status status,
                                                 Continuation.WaitKind kind) {
        return switch (status) {
            case WAITING_IPC -> kind == Continuation.WaitKind.IPC
                    || kind == Continuation.WaitKind.CHILD
                    || kind == Continuation.WaitKind.PROCESS;
            case WAITING_TIMER -> kind == Continuation.WaitKind.TIMER;
            case WAITING_EFFECT -> kind == Continuation.WaitKind.EFFECT;
            case WAITING_INPUT -> kind == Continuation.WaitKind.INPUT;
            default -> false;
        };
    }

    private static void validateWaitColumns(Optional<Continuation.WaitState> wait,
                                            String reason, UUID objectId) {
        if (wait.isEmpty()) {
            requireProjection(reason == null && objectId == null,
                    "Process wait columns exist without a wait state");
            return;
        }
        Continuation.WaitState state = wait.orElseThrow();
        requireProjection(state.kind().name().equals(reason),
                "Process wait reason disagrees with continuation envelope");
        requireProjection(java.util.Objects.equals(state.targetId().orElse(null), objectId),
                "Process wait object disagrees with continuation envelope");
    }

    private static int runtimeFormat(Continuation continuation) {
        try {
            int value = Integer.parseInt(continuation.runtimeFormatVersion());
            if (value < 1) throw new NumberFormatException("not positive");
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Runtime format version must be a positive integer",
                    failure);
        }
    }

    private static void optionalWaitReason(PreparedStatement statement, int index,
                                           Optional<Continuation.WaitState> wait)
            throws SQLException {
        if (wait.isPresent()) statement.setString(index, wait.orElseThrow().kind().name());
        else statement.setNull(index, Types.VARCHAR);
    }

    private static void optionalWaitObject(PreparedStatement statement, int index,
                                           Optional<Continuation.WaitState> wait)
            throws SQLException {
        JdbcValues.nullableUuid(statement, index, wait.flatMap(Continuation.WaitState::targetId));
    }

    private static void requireProjection(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private record ProcessRow(
            UUID processUid,
            long pid,
            UUID ownerId,
            CilProcess.Status status,
            long stateVersion,
            long executionEpoch,
            Continuation envelope,
            Optional<UUID> parentProcessUid,
            Instant createdAt,
            Instant updatedAt,
            UUID programId,
            int programCounter,
            String waitReason,
            UUID waitObjectId,
            int runtimeFormatVersion,
            String languageVersion
    ) {
    }
}
