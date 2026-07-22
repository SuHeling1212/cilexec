package com.follarce.persistence.postgres.repository;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.AuthRepository;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class JdbcAuthRepository extends JdbcRepositorySupport implements AuthRepository {
    public JdbcAuthRepository(Connection connection) {
        super(connection);
    }

    @Override
    public Optional<UserAccount> findUser(UUID userId) {
        return find("auth.findUserById", "SELECT * FROM auth.user_account WHERE user_id=?",
                statement -> statement.setObject(1, userId));
    }

    @Override
    public Optional<UserAccount> findUser(String username) {
        return find("auth.findUserByName", "SELECT * FROM auth.user_account WHERE lower(username)=lower(?)",
                statement -> statement.setString(1, username));
    }

    @Override
    public void saveUser(UserAccount user) {
        String sql = "INSERT INTO auth.user_account(user_id,username,postgres_role_name,status,"
                + "credential_version,created_at,updated_at,disabled_at) VALUES (?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (user_id) DO UPDATE SET username=EXCLUDED.username,status=EXCLUDED.status,"
                + "credential_version=EXCLUDED.credential_version,updated_at=EXCLUDED.updated_at,"
                + "disabled_at=EXCLUDED.disabled_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, user.userId());
            statement.setString(2, user.username());
            statement.setString(3, user.postgresRoleName());
            statement.setString(4, user.status().name());
            statement.setLong(5, user.credentialVersion());
            statement.setTimestamp(6, java.sql.Timestamp.from(user.createdAt()));
            statement.setTimestamp(7, java.sql.Timestamp.from(java.time.Instant.now()));
            JdbcValues.nullableInstant(statement, 8, user.disabledAt());
            requireOne("auth.saveUser", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("auth.saveUser", exception);
        }
    }

    @Override
    public String provisionPrincipal(UUID userId, char[] password) {
        if (password == null || password.length < 16) {
            throw new IllegalArgumentException("Database login password must contain at least 16 characters");
        }
        char[] copy = password.clone();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT auth.provision_principal(?,?)")) {
            statement.setObject(1, userId);
            statement.setString(2, new String(copy));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Principal provisioner returned no role");
                return rows.getString(1);
            }
        } catch (SQLException exception) {
            throw failure("auth.provisionPrincipal", exception);
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    @Override
    public void disablePrincipal(UUID userId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT auth.disable_principal(?)")) {
            statement.setObject(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Principal disabler returned no role");
            }
        } catch (SQLException exception) {
            throw failure("auth.disablePrincipal", exception);
        }
    }

    @Override
    public Set<Capability> capabilities(UUID userId) {
        String sql = "SELECT capability_key FROM auth.effective_capabilities(?) "
                + "ORDER BY capability_key";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                Set<Capability> result = new LinkedHashSet<>();
                while (rows.next()) {
                    result.add(Capability.valueOf(rows.getString(1).toUpperCase(java.util.Locale.ROOT)));
                }
                return Set.copyOf(result);
            }
        } catch (SQLException exception) {
            throw failure("auth.capabilities", exception);
        }
    }

    @Override
    public void replaceCapabilities(UUID userId, Set<Capability> capabilities) {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM auth.user_capability WHERE user_id=?")) {
            delete.setObject(1, userId);
            delete.executeUpdate();
        } catch (SQLException exception) {
            throw failure("auth.clearCapabilities", exception);
        }
        String sql = "INSERT INTO auth.user_capability(user_id,owner_id,capability_id,granted_by) "
                + "SELECT ?,?,capability_id,? FROM auth.capability WHERE capability_key=?";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Capability capability : capabilities) {
                insert.setObject(1, userId);
                insert.setObject(2, userId);
                insert.setObject(3, userId);
                insert.setString(4, capability.name().toLowerCase(java.util.Locale.ROOT));
                insert.addBatch();
            }
            int[] affected = insert.executeBatch();
            for (int count : affected) requireOne("auth.assignCapability", count);
        } catch (SQLException exception) {
            throw failure("auth.replaceCapabilities", exception);
        }
    }

    private Optional<UserAccount> find(String operation, String sql, Binder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new UserAccount(
                        rows.getObject("user_id", UUID.class),
                        rows.getString("username"),
                        rows.getString("postgres_role_name"),
                        UserAccount.Status.valueOf(rows.getString("status")),
                        rows.getTimestamp("created_at").toInstant(),
                        JdbcValues.optionalInstant(rows, "disabled_at"),
                        rows.getLong("credential_version")
                ));
            }
        } catch (SQLException exception) {
            throw failure(operation, exception);
        }
    }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement statement) throws SQLException; }
}
