package com.follarce.persistence.postgres.repository;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import com.follarce.persistence.postgres.mapper.JsonCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.ROOT_FUNCTION;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.databaseFrameId;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.databaseScopeId;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.exceptionFrameId;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.frameForScope;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.require;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.requireVariableName;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.rootFrameId;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.rootScopeId;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.variableId;

/** Writes a complete current-state projection after an aggregate CAS succeeds. */
final class JdbcProcessProjectionWriter {
    private final Connection connection;
    private final JsonCodec json;

    JdbcProcessProjectionWriter(Connection connection, JsonCodec json) {
        this.connection = connection;
        this.json = json;
    }

    void replace(CilProcess process) throws SQLException {
        clear(process.identity().processUid());
        insertCallFrames(process);
        insertScopes(process);
        insertVariables(process);
        insertExceptionFrames(process);
        insertWaitState(process);
        insertWaitRelationship(process);
    }

    void insertParentRelationships(CilProcess process) throws SQLException {
        if (process.parentProcessUid().isEmpty()) return;
        UUID child = process.identity().processUid();
        UUID parent = process.parentProcessUid().orElseThrow();
        insertRelationship(child, process.ownerId(), parent, "PARENT", process.createdAt());
        insertRelationship(parent, process.ownerId(), child, "CHILD", process.createdAt());
    }

    void appendEvent(CilProcess process, String eventType) throws SQLException {
        String sql = "INSERT INTO process.event(event_id,process_uid,owner_id,event_type,"
                + "state_version,details_json,created_at) VALUES (?,?,?,?,?,?,?)";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", process.status().name());
        details.put("programCounter", process.continuation().programCounter());
        details.put("executionEpoch", process.executionEpoch());
        details.put("runtimeFormatVersion", process.continuation().runtimeFormatVersion());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, process.identity().processUid());
            statement.setObject(3, process.ownerId());
            statement.setString(4, eventType);
            statement.setLong(5, process.stateVersion());
            statement.setObject(6, JdbcValues.json(json.write(details)));
            statement.setTimestamp(7, java.sql.Timestamp.from(process.updatedAt()));
            require(statement.executeUpdate() == 1, "Process event was not appended");
        }
    }

    private void clear(UUID processUid) throws SQLException {
        executeDelete("DELETE FROM process.wait_state WHERE process_uid=?", processUid);
        executeDelete("DELETE FROM process.exception_frame WHERE process_uid=?", processUid);
        executeDelete("DELETE FROM process.variable WHERE process_uid=?", processUid);
        executeDelete("DELETE FROM process.scope WHERE process_uid=?", processUid);
        executeDelete("DELETE FROM process.call_frame WHERE process_uid=?", processUid);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM process.relationship WHERE process_uid=? "
                        + "AND relationship_type='WAITS_FOR'")) {
            statement.setObject(1, processUid);
            statement.executeUpdate();
        }
    }

    private void executeDelete(String sql, UUID processUid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            statement.executeUpdate();
        }
    }

    private void insertCallFrames(CilProcess process) throws SQLException {
        String sql = "INSERT INTO process.call_frame(frame_id,process_uid,owner_id,frame_depth,"
                + "function_name,return_program_counter,frame_state,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCallFrame(statement, rootFrameId(process.identity().processUid()), process,
                    0, ROOT_FUNCTION, Optional.empty(), Map.of("root", true));
            statement.executeUpdate();
            List<Continuation.CallFrame> frames = process.continuation().callStack();
            for (int index = 0; index < frames.size(); index++) {
                Continuation.CallFrame frame = frames.get(index);
                bindCallFrame(statement, databaseFrameId(process.identity().processUid(),
                                frame.frameId()), process, index + 1, frame.functionName(),
                        Optional.of(frame.returnAddress()), frame);
                statement.executeUpdate();
            }
        }
    }

    private void bindCallFrame(PreparedStatement statement, UUID frameId, CilProcess process,
                               int depth, String functionName, Optional<Integer> returnAddress,
                               Object state) throws SQLException {
        statement.setObject(1, frameId);
        statement.setObject(2, process.identity().processUid());
        statement.setObject(3, process.ownerId());
        statement.setInt(4, depth);
        statement.setString(5, functionName);
        if (returnAddress.isPresent()) statement.setInt(6, returnAddress.orElseThrow());
        else statement.setNull(6, Types.INTEGER);
        statement.setObject(7, JdbcValues.json(json.write(state)));
        statement.setTimestamp(8, java.sql.Timestamp.from(process.updatedAt()));
    }

    private void insertScopes(CilProcess process) throws SQLException {
        String sql = "INSERT INTO process.scope(scope_id,process_uid,owner_id,frame_id,"
                + "parent_scope_id,scope_depth,scope_kind,created_at) VALUES (?,?,?,?,?,?,?,?)";
        UUID processUid = process.identity().processUid();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindScope(statement, rootScopeId(processUid), process, rootFrameId(processUid),
                    Optional.empty(), 0, "GLOBAL");
            statement.executeUpdate();

            Map<UUID, IndexedScope> remaining = new LinkedHashMap<>();
            List<Continuation.ScopeFrame> scopes = process.continuation().scopeStack();
            for (int index = 0; index < scopes.size(); index++) {
                Continuation.ScopeFrame scope = scopes.get(index);
                if (remaining.put(scope.scopeId(), new IndexedScope(index, scope)) != null) {
                    throw new IllegalStateException("Duplicate continuation scope " + scope.scopeId());
                }
            }
            Set<UUID> inserted = new HashSet<>();
            while (!remaining.isEmpty()) {
                boolean progressed = false;
                var iterator = remaining.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    Continuation.ScopeFrame scope = entry.getValue().scope();
                    if (scope.parentScopeId().isPresent()
                            && !inserted.contains(scope.parentScopeId().orElseThrow())) {
                        continue;
                    }
                    UUID frameId = frameForScope(processUid, scope.scopeId(),
                            process.continuation().callStack());
                    Optional<UUID> parentId = Optional.of(scope.parentScopeId()
                            .map(parent -> databaseScopeId(processUid, parent))
                            .orElseGet(() -> rootScopeId(processUid)));
                    String kind = scope.parentScopeId().isEmpty() ? "GLOBAL"
                            : frameId.equals(rootFrameId(processUid)) ? "BLOCK" : "FUNCTION";
                    bindScope(statement, databaseScopeId(processUid, scope.scopeId()), process,
                            frameId, parentId, entry.getValue().index() + 1, kind);
                    statement.executeUpdate();
                    inserted.add(scope.scopeId());
                    iterator.remove();
                    progressed = true;
                }
                if (!progressed) {
                    throw new IllegalStateException("Continuation scope parents contain a cycle");
                }
            }
        }
    }

    private void bindScope(PreparedStatement statement, UUID scopeId, CilProcess process,
                           UUID frameId, Optional<UUID> parentScopeId, int depth, String kind)
            throws SQLException {
        statement.setObject(1, scopeId);
        statement.setObject(2, process.identity().processUid());
        statement.setObject(3, process.ownerId());
        statement.setObject(4, frameId);
        JdbcValues.nullableUuid(statement, 5, parentScopeId);
        statement.setInt(6, depth);
        statement.setString(7, kind);
        statement.setTimestamp(8, java.sql.Timestamp.from(process.updatedAt()));
    }

    private void insertVariables(CilProcess process) throws SQLException {
        UUID processUid = process.identity().processUid();
        insertVariableMap(process, rootScopeId(processUid),
                process.continuation().globalVariables());
        for (Continuation.ScopeFrame scope : process.continuation().scopeStack()) {
            insertVariableMap(process, databaseScopeId(processUid, scope.scopeId()),
                    scope.variables());
        }
    }

    private void insertVariableMap(CilProcess process, UUID scopeId,
                                   Map<String, Continuation.PersistedValue> variables)
            throws SQLException {
        String sql = "INSERT INTO process.variable(variable_id,process_uid,owner_id,scope_id,"
                + "variable_name,value_type,value_json,value_object_hash,updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (var entry : variables.entrySet()) {
                String name = requireVariableName(entry.getKey());
                Continuation.PersistedValue value = entry.getValue();
                statement.setObject(1, variableId(process.identity().processUid(), scopeId, name));
                statement.setObject(2, process.identity().processUid());
                statement.setObject(3, process.ownerId());
                statement.setObject(4, scopeId);
                statement.setString(5, name);
                statement.setString(6, value.type());
                if ("null".equals(value.type())) statement.setNull(7, Types.OTHER);
                else statement.setObject(7, JdbcValues.json(json.write(value)));
                statement.setNull(8, Types.BINARY);
                statement.setTimestamp(9, java.sql.Timestamp.from(process.updatedAt()));
                statement.executeUpdate();
            }
        }
    }

    private void insertExceptionFrames(CilProcess process) throws SQLException {
        String sql = "INSERT INTO process.exception_frame(exception_frame_id,process_uid,"
                + "owner_id,frame_depth,handler_program_counter,finally_program_counter,"
                + "exception_type,state_json) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            List<Continuation.ExceptionFrame> frames = process.continuation().exceptionStack();
            for (int index = 0; index < frames.size(); index++) {
                Continuation.ExceptionFrame frame = frames.get(index);
                statement.setObject(1, exceptionFrameId(process.identity().processUid(), index));
                statement.setObject(2, process.identity().processUid());
                statement.setObject(3, process.ownerId());
                statement.setInt(4, index);
                statement.setInt(5, frame.handlerAddress());
                statement.setNull(6, Types.INTEGER);
                if (frame.pendingException().isPresent()) {
                    statement.setString(7, frame.pendingException().orElseThrow().type());
                } else statement.setNull(7, Types.VARCHAR);
                statement.setObject(8, JdbcValues.json(json.write(frame)));
                statement.executeUpdate();
            }
        }
    }

    private void insertWaitState(CilProcess process) throws SQLException {
        if (process.continuation().waitState().isEmpty()) return;
        Continuation.WaitState wait = process.continuation().waitState().orElseThrow();
        String sql = "INSERT INTO process.wait_state(process_uid,owner_id,wait_kind,"
                + "wait_object_id,wait_payload,entered_at) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, process.identity().processUid());
            statement.setObject(2, process.ownerId());
            statement.setString(3, wait.kind().name());
            JdbcValues.nullableUuid(statement, 4, wait.targetId());
            statement.setObject(5, JdbcValues.json(json.write(wait)));
            statement.setTimestamp(6, java.sql.Timestamp.from(process.updatedAt()));
            statement.executeUpdate();
        }
    }

    private void insertWaitRelationship(CilProcess process) throws SQLException {
        Optional<Continuation.WaitState> state = process.continuation().waitState();
        if (state.isEmpty() || state.orElseThrow().targetPid().isEmpty()) return;
        Continuation.WaitState wait = state.orElseThrow();
        if (wait.kind() != Continuation.WaitKind.CHILD
                && wait.kind() != Continuation.WaitKind.PROCESS) return;
        String sql = "INSERT INTO process.relationship(process_uid,owner_id,"
                + "related_process_uid,relationship_type,created_at) "
                + "SELECT ?,?,process_uid,'WAITS_FOR',? FROM process.process "
                + "WHERE pid=? AND owner_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, process.identity().processUid());
            statement.setObject(2, process.ownerId());
            statement.setTimestamp(3, java.sql.Timestamp.from(process.updatedAt()));
            statement.setLong(4, wait.targetPid().orElseThrow());
            statement.setObject(5, process.ownerId());
            require(statement.executeUpdate() == 1, "Wait target process does not exist");
        }
    }

    private void insertRelationship(UUID processUid, UUID ownerId, UUID relatedProcessUid,
                                    String type, Instant at) throws SQLException {
        String sql = "INSERT INTO process.relationship(process_uid,owner_id,related_process_uid,"
                + "relationship_type,created_at) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            statement.setObject(2, ownerId);
            statement.setObject(3, relatedProcessUid);
            statement.setString(4, type);
            statement.setTimestamp(5, java.sql.Timestamp.from(at));
            statement.executeUpdate();
        }
    }

    private record IndexedScope(int index, Continuation.ScopeFrame scope) {
    }
}
