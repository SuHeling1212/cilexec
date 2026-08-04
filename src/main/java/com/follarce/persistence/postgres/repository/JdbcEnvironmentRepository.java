package com.follarce.persistence.postgres.repository;

import com.follarce.domain.port.EnvironmentRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class JdbcEnvironmentRepository extends JdbcRepositorySupport
        implements EnvironmentRepository {
    public JdbcEnvironmentRepository(Connection connection) {
        super(connection);
    }

    @Override public Optional<String> findUser(UUID ownerId, String name) {
        return findValue("environment.findUser",
                "SELECT variable_value FROM auth.environment_variable WHERE owner_id=? AND variable_name=?",
                statement -> { statement.setObject(1, ownerId); statement.setString(2, name); });
    }

    @Override public Map<String, String> findUsers(UUID ownerId) {
        return findMap("environment.findUsers",
                "SELECT variable_name,variable_value FROM auth.environment_variable WHERE owner_id=? ORDER BY variable_name",
                statement -> statement.setObject(1, ownerId));
    }

    @Override public void saveUser(UUID ownerId, String name, String value, Instant at) {
        execute("environment.saveUser", """
                INSERT INTO auth.environment_variable(owner_id,variable_name,variable_value,updated_at)
                VALUES (?,?,?,?) ON CONFLICT (owner_id,variable_name) DO UPDATE
                SET variable_value=EXCLUDED.variable_value,updated_at=EXCLUDED.updated_at
                """, statement -> {
            statement.setObject(1, ownerId); statement.setString(2, name);
            statement.setString(3, value); statement.setTimestamp(4, java.sql.Timestamp.from(at));
        });
    }

    @Override public boolean deleteUser(UUID ownerId, String name) {
        return delete("environment.deleteUser",
                "DELETE FROM auth.environment_variable WHERE owner_id=? AND variable_name=?",
                statement -> { statement.setObject(1, ownerId); statement.setString(2, name); });
    }

    @Override public Optional<String> findShared(String name) {
        return findValue("environment.findShared",
                "SELECT variable_value FROM auth.shared_environment_variable WHERE variable_name=?",
                statement -> statement.setString(1, name));
    }

    @Override public Map<String, String> findShared() {
        return findMap("environment.findShared", """
                SELECT variable_name,variable_value FROM auth.shared_environment_variable
                ORDER BY variable_name
                """, statement -> { });
    }

    @Override public void saveShared(String name, String value, UUID actorId, Instant at) {
        execute("environment.saveShared", """
                INSERT INTO auth.shared_environment_variable(variable_name,variable_value,set_by,updated_at)
                VALUES (?,?,?,?) ON CONFLICT (variable_name) DO UPDATE
                SET variable_value=EXCLUDED.variable_value,set_by=EXCLUDED.set_by,
                    updated_at=EXCLUDED.updated_at
                """, statement -> {
            statement.setString(1, name); statement.setString(2, value);
            statement.setObject(3, actorId); statement.setTimestamp(4, java.sql.Timestamp.from(at));
        });
    }

    @Override public boolean deleteShared(String name) {
        return delete("environment.deleteShared",
                "DELETE FROM auth.shared_environment_variable WHERE variable_name=?",
                statement -> statement.setString(1, name));
    }

    @Override public SharedPolicy sharedPolicy() {
        java.sql.Array names = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT policy_mode,variable_names FROM auth.shared_environment_policy WHERE singleton" );
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Shared environment policy is missing");
            names = rows.getArray(2);
            if (names == null) throw new IllegalStateException(
                    "Shared environment policy names are missing");
            String[] values = (String[]) names.getArray();
            return new SharedPolicy(mode(rows.getString(1)),
                    java.util.Arrays.stream(values).collect(
                            java.util.stream.Collectors.toUnmodifiableSet()));
        } catch (SQLException exception) {
            throw failure("environment.sharedPolicy", exception);
        } finally {
            if (names != null) try { names.free(); } catch (SQLException ignored) { }
        }
    }

    private static SharedPolicy.Mode mode(String value) {
        try {
            return SharedPolicy.Mode.valueOf(value);
        } catch (IllegalArgumentException unknownMode) {
            throw new com.follarce.persistence.postgres.error.PersistenceFailure(
                    com.follarce.persistence.postgres.error.PersistenceFailure.Kind.GENERAL,
                    false, "environment.sharedPolicy: unknown policy mode '" + value + "'",
                    unknownMode);
        }
    }

    @Override public void saveSharedPolicy(SharedPolicy policy, UUID actorId, Instant at) {
        java.sql.Array names = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE auth.shared_environment_policy
                SET policy_mode=?,variable_names=?,set_by=?,updated_at=? WHERE singleton
                """)) {
            names = connection.createArrayOf("text", policy.names().stream().sorted().toArray());
            statement.setString(1, policy.mode().name()); statement.setArray(2, names);
            statement.setObject(3, actorId); statement.setTimestamp(4, java.sql.Timestamp.from(at));
            if (statement.executeUpdate() != 1) throw new IllegalStateException(
                    "Shared environment policy is missing");
        } catch (SQLException exception) {
            throw failure("environment.saveSharedPolicy", exception);
        } finally {
            if (names != null) try { names.free(); } catch (SQLException ignored) { }
        }
    }

    private Optional<String> findValue(String operation, String sql, Binder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException exception) { throw failure(operation, exception); }
    }

    private Map<String, String> findMap(String operation, String sql, Binder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                Map<String, String> result = new LinkedHashMap<>();
                while (rows.next()) result.put(rows.getString(1), rows.getString(2));
                return Map.copyOf(result);
            }
        } catch (SQLException exception) { throw failure(operation, exception); }
    }

    private void execute(String operation, String sql, Binder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement); statement.executeUpdate();
        } catch (SQLException exception) { throw failure(operation, exception); }
    }

    private boolean delete(String operation, String sql, Binder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement); return statement.executeUpdate() == 1;
        } catch (SQLException exception) { throw failure(operation, exception); }
    }

    @FunctionalInterface private interface Binder { void bind(PreparedStatement statement) throws SQLException; }
}
