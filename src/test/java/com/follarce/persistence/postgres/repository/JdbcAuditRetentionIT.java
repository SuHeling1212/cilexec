package com.follarce.persistence.postgres.repository;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.audit.AuditRetentionPolicy;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAuditRetentionIT {
    private static final Instant NOW = Instant.parse("2026-07-22T09:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres:18.0-alpine3.22");

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE cilexec_owner NOLOGIN");
            statement.execute("CREATE ROLE cilexec_migrator NOLOGIN");
            statement.execute("CREATE ROLE cilexec_runtime NOLOGIN");
            statement.execute("CREATE ROLE cilexec_effect_worker NOLOGIN");
            statement.execute("CREATE ROLE cilexec_readonly NOLOGIN");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("flyway")
                .schemas("flyway")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @Test
    void detailsRequireStringValuesAndRuntimeCannotDeleteRowsDirectly() throws Exception {
        UUID invalidId = UUID.randomUUID();
        try (Connection connection = runtimeConnection()) {
            SQLException invalid = assertThrows(SQLException.class,
                    () -> insertRaw(connection, invalidId, "{\"attempts\":3}"));
            assertEquals("23514", invalid.getSQLState());
            connection.rollback();
        }

        UUID eventId = UUID.randomUUID();
        String resourceId = UUID.randomUUID().toString();
        try (Connection connection = runtimeConnection()) {
            JdbcAuditRepository repository = new JdbcAuditRepository(connection, new JsonCodec());
            repository.append(event(eventId, "audit.contract", resourceId, NOW,
                    Map.of("attempts", "3")));
            assertEquals(Map.of("attempts", "3"),
                    repository.findByResource("audit.event", resourceId, 1)
                            .getFirst().details());
            connection.commit();
        }

        try (Connection connection = runtimeConnection(); PreparedStatement delete =
                connection.prepareStatement("DELETE FROM audit.event WHERE event_id=?")) {
            delete.setObject(1, eventId);
            SQLException denied = assertThrows(SQLException.class, delete::executeUpdate);
            assertEquals("42501", denied.getSQLState());
            connection.rollback();
        }

        try (Connection connection = adminConnection()) {
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM audit.event WHERE event_id=?", eventId));
            assertFalse(privilege(connection,
                    "SELECT has_table_privilege('cilexec_runtime','audit.event','DELETE')"));
            assertTrue(privilege(connection, "SELECT has_function_privilege("
                    + "'cilexec_runtime','audit.purge_expired_events(integer)',"
                    + "'EXECUTE')"));
        }
    }

    @Test
    void retentionMatchesExactActionsHonorsDisableAndPurgesInBoundedBatches()
            throws Exception {
        String resourceId = UUID.randomUUID().toString();
        UUID oldest = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        UUID otherAction = UUID.randomUUID();
        long retainSeconds = Duration.ofDays(5).toSeconds();
        Instant retentionNow = Instant.now().truncatedTo(ChronoUnit.MICROS);

        try (Connection connection = runtimeConnection()) {
            JdbcAuditRepository repository = new JdbcAuditRepository(connection, new JsonCodec());
            AuditRetentionPolicy enabled = new AuditRetentionPolicy(
                    "auth.login", retainSeconds, true, retentionNow);
            repository.saveRetentionPolicy(enabled);
            assertEquals(enabled, repository.findRetentionPolicy("auth.login").orElseThrow());

            repository.append(event(oldest, "auth.login", resourceId,
                    retentionNow.minus(Duration.ofDays(20)), Map.of()));
            repository.append(event(second, "auth.login", resourceId,
                    retentionNow.minus(Duration.ofDays(10)), Map.of()));
            repository.append(event(fresh, "auth.login", resourceId,
                    retentionNow.minus(Duration.ofDays(1)), Map.of()));
            repository.append(event(otherAction, "auth.logout", resourceId,
                    retentionNow.minus(Duration.ofDays(30)), Map.of()));

            assertEquals(1, repository.purgeExpired(1));
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM audit.event WHERE event_id=?", oldest));

            AuditRetentionPolicy disabled = new AuditRetentionPolicy(
                    "auth.login", retainSeconds, false, retentionNow.plusSeconds(1));
            repository.saveRetentionPolicy(disabled);
            assertEquals(0, repository.purgeExpired(10));

            repository.saveRetentionPolicy(new AuditRetentionPolicy(
                    "auth.login", retainSeconds, true, retentionNow.plusSeconds(2)));
            assertEquals(1, repository.purgeExpired(10));
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM audit.event WHERE event_id=?", second));
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM audit.event WHERE event_id=?", fresh));
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM audit.event WHERE event_id=?", otherAction));
            connection.commit();
        }
    }

    private static AuditEvent event(UUID eventId, String action, String resourceId,
                                    Instant at, Map<String, String> details) {
        return new AuditEvent(eventId, AuditEvent.ActorType.RUNTIME, "runtime", action,
                "audit.event", resourceId, AuditEvent.Result.SUCCEEDED, details, at);
    }

    private static void insertRaw(Connection connection, UUID eventId, String details)
            throws SQLException {
        String sql = "INSERT INTO audit.event(event_id,actor_type,actor_id,action,resource_type,"
                + "resource_id,result,details_json,created_at) "
                + "VALUES (?,'RUNTIME','runtime','audit.invalid','audit.event','invalid',"
                + "'FAILED',CAST(? AS jsonb),?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, eventId);
            statement.setString(2, details);
            statement.setTimestamp(3, java.sql.Timestamp.from(NOW));
            statement.executeUpdate();
        }
    }

    private static int count(Connection connection, String sql, Object value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static boolean privilege(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static Connection runtimeConnection() throws Exception {
        Connection connection = adminConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE cilexec_runtime");
        }
        return connection;
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
