package com.follarce.persistence.postgres.repository;

import com.follarce.domain.process.Continuation;
import com.follarce.persistence.postgres.mapper.JsonCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.ROOT_FUNCTION;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.databaseFrameId;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.databaseScopeId;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.exceptionFrameId;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.frameForScope;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.require;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.rootFrameId;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.rootScopeId;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.validateControlScopes;
import static com.follarce.persistence.postgres.repository.JdbcProcessProjectionStore.variableId;

/** Reads query projections and rebuilds authoritative current variable maps. */
final class JdbcProcessProjectionReader {
    private final Connection connection;
    private final JsonCodec json;

    JdbcProcessProjectionReader(Connection connection, JsonCodec json) {
        this.connection = connection;
        this.json = json;
    }

    Continuation load(UUID processUid, Continuation envelope) throws SQLException {
        List<Continuation.CallFrame> calls = readCallFrames(processUid, envelope);
        readAndValidateScopes(processUid, envelope, calls);
        VariableProjection variables = readVariables(processUid, envelope);
        List<Continuation.ScopeFrame> scopes = rebuildScopes(envelope, variables.scopes());
        List<Continuation.ExceptionFrame> exceptions = readExceptionFrames(processUid, envelope);
        Optional<Continuation.WaitState> wait = readWaitState(processUid);
        validateControlScopes(envelope.controlStack(), scopes);
        return new Continuation(envelope.programId(), envelope.programHash(),
                envelope.programCounter(), calls, scopes, exceptions, envelope.controlStack(),
                wait, variables.globals(), envelope.packageBindings(), envelope.languageVersion(),
                envelope.runtimeFormatVersion());
    }

    private List<Continuation.CallFrame> readCallFrames(UUID processUid,
                                                        Continuation envelope)
            throws SQLException {
        String sql = "SELECT frame_id,frame_depth,function_name,return_program_counter,"
                + "frame_state::text FROM process.call_frame WHERE process_uid=? "
                + "ORDER BY frame_depth";
        List<Continuation.CallFrame> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            try (ResultSet rows = statement.executeQuery()) {
                int expectedDepth = 0;
                while (rows.next()) {
                    int depth = rows.getInt("frame_depth");
                    require(depth == expectedDepth, "Non-contiguous call frame depth");
                    UUID storedId = rows.getObject("frame_id", UUID.class);
                    if (depth == 0) {
                        require(rootFrameId(processUid).equals(storedId)
                                        && ROOT_FUNCTION.equals(rows.getString("function_name")),
                                "Invalid root call frame projection");
                    } else {
                        Continuation.CallFrame frame = json.read(
                                rows.getString("frame_state"), Continuation.CallFrame.class);
                        require(databaseFrameId(processUid, frame.frameId()).equals(storedId),
                                "Call frame ID projection mismatch");
                        require(frame.functionName().equals(rows.getString("function_name"))
                                        && frame.returnAddress()
                                        == rows.getInt("return_program_counter"),
                                "Call frame fields disagree with frame state");
                        result.add(frame);
                    }
                    expectedDepth++;
                }
            }
        }
        require(result.equals(envelope.callStack()),
                "Call frame projection disagrees with continuation envelope");
        return List.copyOf(result);
    }

    private void readAndValidateScopes(UUID processUid, Continuation envelope,
                                       List<Continuation.CallFrame> calls) throws SQLException {
        String sql = "SELECT scope_id,frame_id,parent_scope_id,scope_depth FROM process.scope "
                + "WHERE process_uid=? ORDER BY scope_depth";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            try (ResultSet rows = statement.executeQuery()) {
                int expectedDepth = 0;
                while (rows.next()) {
                    int depth = rows.getInt("scope_depth");
                    require(depth == expectedDepth, "Non-contiguous scope depth");
                    if (depth == 0) {
                        require(rootScopeId(processUid).equals(rows.getObject("scope_id", UUID.class))
                                        && rootFrameId(processUid)
                                        .equals(rows.getObject("frame_id", UUID.class)),
                                "Invalid root scope projection");
                    } else {
                        require(depth <= envelope.scopeStack().size(),
                                "Unexpected scope projection");
                        Continuation.ScopeFrame scope = envelope.scopeStack().get(depth - 1);
                        require(databaseScopeId(processUid, scope.scopeId())
                                        .equals(rows.getObject("scope_id", UUID.class)),
                                "Scope ID projection mismatch");
                        UUID expectedFrame = frameForScope(processUid, scope.scopeId(), calls);
                        require(expectedFrame.equals(rows.getObject("frame_id", UUID.class)),
                                "Scope frame projection mismatch");
                        UUID expectedParent = scope.parentScopeId()
                                .map(parent -> databaseScopeId(processUid, parent))
                                .orElseGet(() -> rootScopeId(processUid));
                        require(expectedParent.equals(rows.getObject("parent_scope_id", UUID.class)),
                                "Scope parent projection mismatch");
                    }
                    expectedDepth++;
                }
                require(expectedDepth == envelope.scopeStack().size() + 1,
                        "Scope projection count mismatch");
            }
        }
    }

    private VariableProjection readVariables(UUID processUid, Continuation envelope)
            throws SQLException {
        Map<String, Continuation.PersistedValue> globals = new LinkedHashMap<>();
        Map<UUID, Map<String, Continuation.PersistedValue>> scopes = new HashMap<>();
        Map<UUID, UUID> databaseToDomainScope = new HashMap<>();
        for (Continuation.ScopeFrame scope : envelope.scopeStack()) {
            UUID databaseId = databaseScopeId(processUid, scope.scopeId());
            databaseToDomainScope.put(databaseId, scope.scopeId());
            scopes.put(scope.scopeId(), new LinkedHashMap<>());
        }
        String sql = "SELECT variable_id,scope_id,variable_name,value_type,value_json::text "
                + "FROM process.variable WHERE process_uid=? ORDER BY scope_id,variable_name";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID scopeId = rows.getObject("scope_id", UUID.class);
                    String name = rows.getString("variable_name");
                    require(variableId(processUid, scopeId, name)
                                    .equals(rows.getObject("variable_id", UUID.class)),
                            "Variable ID projection mismatch");
                    String type = rows.getString("value_type");
                    Continuation.PersistedValue value;
                    if ("null".equals(type)) {
                        require(rows.getString("value_json") == null,
                                "Null variable must not have JSON content");
                        value = new Continuation.PersistedValue("null",
                                nullPayload(envelope, processUid, scopeId, name));
                    } else {
                        String valueJson = rows.getString("value_json");
                        require(valueJson != null, "Non-null variable is missing JSON content");
                        value = json.read(valueJson, Continuation.PersistedValue.class);
                        require(type.equals(value.type()), "Variable type projection mismatch");
                    }
                    Map<String, Continuation.PersistedValue> destination;
                    if (rootScopeId(processUid).equals(scopeId)) destination = globals;
                    else {
                        UUID domainScope = databaseToDomainScope.get(scopeId);
                        require(domainScope != null, "Variable references an unknown scope");
                        destination = scopes.get(domainScope);
                    }
                    require(destination.put(name, value) == null,
                            "Duplicate variable projection");
                }
            }
        }
        Map<UUID, Map<String, Continuation.PersistedValue>> immutableScopes = new HashMap<>();
        scopes.forEach((scope, values) -> immutableScopes.put(scope, Map.copyOf(values)));
        return new VariableProjection(Map.copyOf(globals), Map.copyOf(immutableScopes));
    }

    private String nullPayload(Continuation envelope, UUID processUid, UUID databaseScope,
                               String name) {
        Continuation.PersistedValue source;
        if (rootScopeId(processUid).equals(databaseScope)) {
            source = envelope.globalVariables().get(name);
        } else {
            source = envelope.scopeStack().stream()
                    .filter(scope -> databaseScopeId(processUid, scope.scopeId())
                            .equals(databaseScope))
                    .map(scope -> scope.variables().get(name))
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
        }
        return source != null && "null".equals(source.type())
                ? source.canonicalPayload() : "null";
    }

    private List<Continuation.ScopeFrame> rebuildScopes(
            Continuation envelope,
            Map<UUID, Map<String, Continuation.PersistedValue>> variables) {
        List<Continuation.ScopeFrame> result = new ArrayList<>();
        for (Continuation.ScopeFrame scope : envelope.scopeStack()) {
            Map<String, Continuation.PersistedValue> values = variables.get(scope.scopeId());
            require(values != null, "Normalized variables are missing a scope");
            result.add(new Continuation.ScopeFrame(scope.scopeId(), scope.parentScopeId(), values));
        }
        return List.copyOf(result);
    }

    private List<Continuation.ExceptionFrame> readExceptionFrames(
            UUID processUid, Continuation envelope) throws SQLException {
        String sql = "SELECT exception_frame_id,frame_depth,handler_program_counter,"
                + "exception_type,state_json::text FROM process.exception_frame "
                + "WHERE process_uid=? ORDER BY frame_depth";
        List<Continuation.ExceptionFrame> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            try (ResultSet rows = statement.executeQuery()) {
                int expectedDepth = 0;
                while (rows.next()) {
                    require(rows.getInt("frame_depth") == expectedDepth,
                            "Non-contiguous exception frame depth");
                    require(exceptionFrameId(processUid, expectedDepth)
                                    .equals(rows.getObject("exception_frame_id", UUID.class)),
                            "Exception frame ID projection mismatch");
                    Continuation.ExceptionFrame frame = json.read(
                            rows.getString("state_json"), Continuation.ExceptionFrame.class);
                    require(frame.handlerAddress() == rows.getInt("handler_program_counter"),
                            "Exception handler projection mismatch");
                    String type = rows.getString("exception_type");
                    require(java.util.Objects.equals(type,
                                    frame.pendingException().map(
                                            Continuation.PersistedValue::type).orElse(null)),
                            "Exception type projection mismatch");
                    result.add(frame);
                    expectedDepth++;
                }
            }
        }
        require(result.equals(envelope.exceptionStack()),
                "Exception projection disagrees with continuation envelope");
        return List.copyOf(result);
    }

    private Optional<Continuation.WaitState> readWaitState(UUID processUid) throws SQLException {
        String sql = "SELECT wait_kind,wait_object_id,wait_payload::text "
                + "FROM process.wait_state WHERE process_uid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                Continuation.WaitState wait = json.read(
                        rows.getString("wait_payload"), Continuation.WaitState.class);
                require(wait.kind().name().equals(rows.getString("wait_kind")),
                        "Wait kind projection mismatch");
                require(java.util.Objects.equals(wait.targetId().orElse(null),
                                rows.getObject("wait_object_id", UUID.class)),
                        "Wait target projection mismatch");
                require(!rows.next(), "Multiple wait state projections exist");
                return Optional.of(wait);
            }
        }
    }

    private record VariableProjection(
            Map<String, Continuation.PersistedValue> globals,
            Map<UUID, Map<String, Continuation.PersistedValue>> scopes
    ) {
    }
}
