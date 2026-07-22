package com.follarce.persistence.postgres.repository;

import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.persistence.postgres.mapper.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Coordinates normalized continuation reads and writes on one physical connection. */
final class JdbcProcessProjectionStore {
    static final String ROOT_FUNCTION = "$root";

    private final JdbcProcessProjectionWriter writer;
    private final JdbcProcessProjectionReader reader;

    JdbcProcessProjectionStore(Connection connection, JsonCodec json) {
        writer = new JdbcProcessProjectionWriter(connection, json);
        reader = new JdbcProcessProjectionReader(connection, json);
    }

    void replace(CilProcess process) throws SQLException {
        validate(process.continuation());
        writer.replace(process);
    }

    Continuation load(UUID processUid, Continuation envelope) throws SQLException {
        validate(envelope);
        return reader.load(processUid, envelope);
    }

    void insertParentRelationships(CilProcess process) throws SQLException {
        writer.insertParentRelationships(process);
    }

    void appendEvent(CilProcess process, String eventType) throws SQLException {
        writer.appendEvent(process, eventType);
    }

    private static void validate(Continuation continuation) {
        Set<UUID> scopes = new LinkedHashSet<>();
        for (Continuation.ScopeFrame scope : continuation.scopeStack()) {
            require(scopes.add(scope.scopeId()), "Duplicate continuation scope ID");
        }
        for (Continuation.ScopeFrame scope : continuation.scopeStack()) {
            scope.parentScopeId().ifPresent(parent -> require(scopes.contains(parent),
                    "Continuation scope parent is missing"));
            scope.variables().keySet().forEach(JdbcProcessProjectionStore::requireVariableName);
        }
        Set<UUID> calls = new HashSet<>();
        for (Continuation.CallFrame call : continuation.callStack()) {
            require(calls.add(call.frameId()), "Duplicate continuation call frame ID");
            require(scopes.contains(call.scopeId()), "Call frame scope is missing");
        }
        for (Continuation.ExceptionFrame frame : continuation.exceptionStack()) {
            require(scopes.contains(frame.scopeId()), "Exception frame scope is missing");
        }
        validateControlScopes(continuation.controlStack(), continuation.scopeStack());
        continuation.globalVariables().keySet()
                .forEach(JdbcProcessProjectionStore::requireVariableName);
    }

    static void validateControlScopes(List<Continuation.ControlFrame> controls,
                                      List<Continuation.ScopeFrame> scopes) {
        Set<UUID> scopeIds = new HashSet<>();
        scopes.forEach(scope -> scopeIds.add(scope.scopeId()));
        for (Continuation.ControlFrame control : controls) {
            require(scopeIds.contains(control.scopeId()), "Control frame scope is missing");
        }
    }

    static UUID frameForScope(UUID processUid, UUID scopeId,
                              List<Continuation.CallFrame> calls) {
        return calls.stream()
                .filter(frame -> frame.scopeId().equals(scopeId))
                .map(frame -> databaseFrameId(processUid, frame.frameId()))
                .findFirst()
                .orElseGet(() -> rootFrameId(processUid));
    }

    static UUID databaseFrameId(UUID processUid, UUID frameId) {
        return stableId(processUid, "call", frameId.toString());
    }

    static UUID databaseScopeId(UUID processUid, UUID scopeId) {
        return stableId(processUid, "scope", scopeId.toString());
    }

    static UUID rootFrameId(UUID processUid) {
        return stableId(processUid, "call", "root");
    }

    static UUID rootScopeId(UUID processUid) {
        return stableId(processUid, "scope", "root");
    }

    static UUID variableId(UUID processUid, UUID scopeId, String name) {
        return stableId(processUid, "variable", scopeId + ":" + name);
    }

    static UUID exceptionFrameId(UUID processUid, int depth) {
        return stableId(processUid, "exception", Integer.toString(depth));
    }

    private static UUID stableId(UUID processUid, String kind, String value) {
        return UUID.nameUUIDFromBytes((processUid + ":" + kind + ":" + value)
                .getBytes(StandardCharsets.UTF_8));
    }

    static String requireVariableName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Persisted variable name must not be blank");
        }
        return name;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
