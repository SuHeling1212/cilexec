package com.follarce.persistence.postgres.repository;

import com.follarce.domain.port.TerminalRepository;
import com.follarce.domain.terminal.TerminalSession;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcTerminalRepository extends JdbcRepositorySupport implements TerminalRepository {
    public JdbcTerminalRepository(Connection connection) {
        super(connection);
    }

    @Override
    public void saveSession(TerminalSession session) {
        saveSession(session, "HOST");
    }

    @Override
    public void saveApiSession(TerminalSession session) {
        saveSession(session, "API");
    }

    private void saveSession(TerminalSession session, String terminalType) {
        String sql = "INSERT INTO terminal.session(session_id,owner_id,status,next_input_sequence,opened_at,"
                + "last_activity_at,closed_at,terminal_type) VALUES (?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (session_id) DO UPDATE "
                + "SET status=EXCLUDED.status,next_input_sequence=EXCLUDED.next_input_sequence,"
                + "last_activity_at=EXCLUDED.last_activity_at,closed_at=EXCLUDED.closed_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, session.sessionId());
            statement.setObject(2, session.ownerId());
            statement.setString(3, session.status().name());
            statement.setLong(4, session.nextInputSequence());
            statement.setTimestamp(5, java.sql.Timestamp.from(session.createdAt()));
            statement.setTimestamp(6, java.sql.Timestamp.from(session.lastActivityAt()));
            JdbcValues.nullableInstant(statement, 7, session.closedAt());
            statement.setString(8, terminalType);
            requireOne("terminal.saveSession", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("terminal.saveSession", exception);
        }
    }

    @Override
    public Optional<TerminalSession> findSession(UUID sessionId) {
        return findSession(sessionId, false);
    }

    @Override
    public Optional<TerminalSession> findSessionForUpdate(UUID sessionId) {
        return findSession(sessionId, true);
    }

    private Optional<TerminalSession> findSession(UUID sessionId, boolean lock) {
        String sql = "SELECT * FROM terminal.session WHERE session_id=?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new TerminalSession(
                        rows.getObject("session_id", UUID.class),
                        rows.getObject("owner_id", UUID.class),
                        TerminalSession.Status.valueOf(rows.getString("status")),
                        rows.getLong("next_input_sequence"),
                        rows.getTimestamp("opened_at").toInstant(),
                        rows.getTimestamp("last_activity_at").toInstant(),
                        JdbcValues.optionalInstant(rows, "closed_at")
                ));
            }
        } catch (SQLException exception) {
            throw failure(lock ? "terminal.findSessionForUpdate" : "terminal.findSession",
                    exception);
        }
    }

    @Override
    public Optional<TerminalSession> findOpenSession(UUID ownerId) {
        try {
            lockOwnerOpenHostSession(ownerId);
        } catch (SQLException exception) {
            throw failure("terminal.findOpenSession", exception);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM terminal.session WHERE owner_id=? AND status='OPEN' "
                        + "AND terminal_type='HOST' "
                        + "ORDER BY last_activity_at DESC,session_id LIMIT 1")) {
            statement.setObject(1, ownerId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new TerminalSession(
                        rows.getObject("session_id", UUID.class),
                        rows.getObject("owner_id", UUID.class),
                        TerminalSession.Status.valueOf(rows.getString("status")),
                        rows.getLong("next_input_sequence"),
                        rows.getTimestamp("opened_at").toInstant(),
                        rows.getTimestamp("last_activity_at").toInstant(),
                        JdbcValues.optionalInstant(rows, "closed_at")));
            }
        } catch (SQLException exception) {
            throw failure("terminal.findOpenSession", exception);
        }
    }

    /**
     * Serializes the openOrResume find-then-create sequence per owner. The caller runs
     * inside one user transaction, so two concurrent logins by the same user cannot both
     * observe "no open session" and insert two HOST sessions (there is no unique partial
     * index for them in the frozen baseline). The transaction-scoped advisory lock is
     * released automatically when the transaction commits or rolls back.
     */
    private void lockOwnerOpenHostSession(UUID ownerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 42))")) {
            statement.setString(1, ownerId.toString());
            statement.execute();
        }
    }

    @Override
    public String workingDirectory(UUID sessionId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT working_directory FROM terminal.session WHERE session_id=?")) {
            statement.setObject(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Unknown terminal session");
                return rows.getString(1);
            }
        } catch (SQLException exception) {
            throw failure("terminal.workingDirectory", exception);
        }
    }

    @Override
    public boolean changeWorkingDirectory(UUID sessionId, String expected,
                                          String replacement, java.time.Instant at) {
        String sql = "UPDATE terminal.session SET working_directory=?,last_activity_at=? "
                + "WHERE session_id=? AND status='OPEN' AND working_directory=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, replacement);
            statement.setTimestamp(2, java.sql.Timestamp.from(at));
            statement.setObject(3, sessionId);
            statement.setString(4, expected);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("terminal.changeWorkingDirectory", exception);
        }
    }

    @Override
    public List<String> findCommandHistory(UUID ownerId, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("History limit must be positive");
        String sql = "SELECT command_text FROM (SELECT history_id,command_text "
                + "FROM terminal.command_history WHERE owner_id=? "
                + "ORDER BY history_id DESC LIMIT ?) AS recent ORDER BY history_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, ownerId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> history = new ArrayList<>();
                while (rows.next()) history.add(rows.getString(1));
                return List.copyOf(history);
            }
        } catch (SQLException exception) {
            throw failure("terminal.findCommandHistory", exception);
        }
    }

    @Override
    public void appendCommandHistory(UUID ownerId, String command,
                                     java.time.Instant submittedAt, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("History limit must be positive");
        String insert = "INSERT INTO terminal.command_history(owner_id,command_text,submitted_at) "
                + "SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM terminal.command_history current "
                + "WHERE current.owner_id=? AND current.history_id=(SELECT max(latest.history_id) "
                + "FROM terminal.command_history latest WHERE latest.owner_id=?) "
                + "AND current.command_text=?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setObject(1, ownerId);
            statement.setString(2, command);
            statement.setTimestamp(3, java.sql.Timestamp.from(submittedAt));
            statement.setObject(4, ownerId);
            statement.setObject(5, ownerId);
            statement.setString(6, command);
            if (statement.executeUpdate() == 0) return;
        } catch (SQLException exception) {
            throw failure("terminal.appendCommandHistory", exception);
        }

        String prune = "DELETE FROM terminal.command_history WHERE owner_id=? "
                + "AND history_id NOT IN (SELECT history_id FROM terminal.command_history "
                + "WHERE owner_id=? ORDER BY history_id DESC LIMIT ?)";
        try (PreparedStatement statement = connection.prepareStatement(prune)) {
            statement.setObject(1, ownerId);
            statement.setObject(2, ownerId);
            statement.setInt(3, limit);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("terminal.pruneCommandHistory", exception);
        }
    }

    @Override
    public void appendInput(TerminalSession.Input input) {
        String sql = "INSERT INTO terminal.input(input_id,session_id,owner_id,input_sequence,submitted_text,"
                + "submitted_at,accepted_at,target_process_uid) "
                + "SELECT ?,session_id,owner_id,?,?,?,?,? FROM terminal.session WHERE session_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, input.inputId());
            statement.setLong(2, input.sequence());
            statement.setString(3, input.committedText());
            statement.setTimestamp(4, java.sql.Timestamp.from(input.submittedAt()));
            JdbcValues.nullableInstant(statement, 5, input.acceptedAt());
            JdbcValues.nullableUuid(statement, 6, input.targetProcessUid());
            statement.setObject(7, input.sessionId());
            requireOne("terminal.appendInput", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("terminal.appendInput", exception);
        }
    }

    @Override
    public void saveAttachment(TerminalSession.Attachment attachment) {
        if (attachment.detachedAt().isEmpty()) {
            lockSession(attachment.sessionId());
            detachPreviousAttachment(attachment);
        }
        String sql = "INSERT INTO terminal.attachment(attachment_id,session_id,process_uid,owner_id,status,"
                + "attached_at,detached_at) SELECT ?,?, ?,owner_id,?,?,? FROM terminal.session WHERE session_id=? "
                + "ON CONFLICT (session_id,process_uid) DO UPDATE SET status=EXCLUDED.status,"
                + "attachment_id=EXCLUDED.attachment_id,attached_at=EXCLUDED.attached_at,"
                + "detached_at=EXCLUDED.detached_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, attachment.attachmentId());
            statement.setObject(2, attachment.sessionId());
            statement.setObject(3, attachment.processUid());
            statement.setString(4, attachment.detachedAt().isPresent() ? "DETACHED" : "ATTACHED");
            statement.setTimestamp(5, java.sql.Timestamp.from(attachment.attachedAt()));
            JdbcValues.nullableInstant(statement, 6, attachment.detachedAt());
            statement.setObject(7, attachment.sessionId());
            requireOne("terminal.saveAttachment", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("terminal.saveAttachment", exception);
        }
    }

    private void lockSession(UUID sessionId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM terminal.session WHERE session_id=? FOR UPDATE")) {
            statement.setObject(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
            }
        } catch (SQLException exception) {
            throw failure("terminal.lockSession", exception);
        }
    }

    private void detachPreviousAttachment(TerminalSession.Attachment replacement) {
        String sql = "UPDATE terminal.attachment SET status='DETACHED',detached_at=? "
                + "WHERE session_id=? AND process_uid<>? AND status='ATTACHED'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, java.sql.Timestamp.from(replacement.attachedAt()));
            statement.setObject(2, replacement.sessionId());
            statement.setObject(3, replacement.processUid());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("terminal.detachPreviousAttachment", exception);
        }
    }

    @Override
    public Optional<TerminalSession.Attachment> findAttachment(UUID sessionId, UUID processUid) {
        String sql = "SELECT attachment_id,session_id,process_uid,attached_at,detached_at "
                + "FROM terminal.attachment WHERE session_id=? AND process_uid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sessionId);
            statement.setObject(2, processUid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new TerminalSession.Attachment(
                        rows.getObject("attachment_id", UUID.class),
                        rows.getObject("session_id", UUID.class),
                        rows.getObject("process_uid", UUID.class),
                        rows.getTimestamp("attached_at").toInstant(),
                        JdbcValues.optionalInstant(rows, "detached_at")));
            }
        } catch (SQLException exception) {
            throw failure("terminal.findAttachment", exception);
        }
    }

    @Override
    public Optional<TerminalSession.Attachment> findActiveAttachment(UUID sessionId) {
        String sql = "SELECT attachment_id,session_id,process_uid,attached_at,detached_at "
                + "FROM terminal.attachment WHERE session_id=? AND status='ATTACHED' "
                + "ORDER BY attached_at DESC,attachment_id LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new TerminalSession.Attachment(
                        rows.getObject("attachment_id", UUID.class),
                        rows.getObject("session_id", UUID.class),
                        rows.getObject("process_uid", UUID.class),
                        rows.getTimestamp("attached_at").toInstant(),
                        JdbcValues.optionalInstant(rows, "detached_at")));
            }
        } catch (SQLException exception) {
            throw failure("terminal.findActiveAttachment", exception);
        }
    }

    @Override
    public Optional<TerminalSession.Input> acceptPendingInput(UUID processUid,
                                                               java.time.Instant at) {
        String sql = "WITH pending AS (SELECT input.input_id FROM terminal.input AS input "
                + "JOIN terminal.attachment AS attachment "
                + "ON attachment.session_id=input.session_id AND attachment.owner_id=input.owner_id "
                + "WHERE attachment.process_uid=? AND attachment.status='ATTACHED' "
                + "AND input.accepted_at IS NULL AND input.target_process_uid IS NULL "
                + "ORDER BY input.submitted_at,input.input_sequence,input.input_id "
                + "FOR UPDATE OF input SKIP LOCKED LIMIT 1) "
                + "UPDATE terminal.input AS input SET accepted_at=?,target_process_uid=? "
                + "FROM pending WHERE input.input_id=pending.input_id RETURNING input.*";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            statement.setTimestamp(2, java.sql.Timestamp.from(at));
            statement.setObject(3, processUid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new TerminalSession.Input(
                        rows.getObject("input_id", UUID.class),
                        rows.getObject("session_id", UUID.class),
                        rows.getLong("input_sequence"),
                        rows.getString("submitted_text"),
                        rows.getTimestamp("submitted_at").toInstant(),
                        JdbcValues.optionalUuid(rows, "target_process_uid"),
                        JdbcValues.optionalInstant(rows, "accepted_at")));
            }
        } catch (SQLException exception) {
            throw failure("terminal.acceptPendingInput", exception);
        }
    }

    @Override
    public void requestInterrupt(TerminalSession.Interrupt interrupt) {
        String sql = "UPDATE process.process SET interrupt_requested=?,updated_at=? WHERE process_uid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, interrupt.handledAt().isEmpty());
            statement.setTimestamp(2, java.sql.Timestamp.from(
                    interrupt.handledAt().orElse(interrupt.requestedAt())));
            statement.setObject(3, interrupt.processUid());
            requireOne("terminal.requestInterrupt", statement.executeUpdate());
            if (interrupt.handledAt().isEmpty()) {
                notifyWork("cilexec_interrupt_work", "terminal.interrupt.notify");
            }
        } catch (SQLException exception) {
            throw failure("terminal.requestInterrupt", exception);
        }
    }

    @Override
    public boolean consumeInterrupt(UUID processUid) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE process.process SET interrupt_requested=false "
                        + "WHERE process_uid=? AND interrupt_requested=true")) {
            statement.setObject(1, processUid);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("terminal.consumeInterrupt", exception);
        }
    }

    @Override
    public boolean removeClosedSession(UUID sessionId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM terminal.session WHERE session_id=? AND status='CLOSED'")) {
            statement.setObject(1, sessionId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("terminal.removeClosedSession", exception);
        }
    }
}
