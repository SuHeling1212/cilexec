package com.follarce.application;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.AdminProjectionRepository;
import com.follarce.domain.port.AuditRepository;
import com.follarce.domain.port.AuthRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.port.VfsRepository;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import com.follarce.persistence.postgres.repository.JdbcAdminProjectionRepository;
import com.follarce.persistence.postgres.repository.JdbcAuditRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL role, RLS, transaction and audit checks on a disposable cluster. */
class SystemProjectionPostgresIT {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

    @Test
    void enforcesReadWritePolicyAndCommitsOnlyAuditedUpdates() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder()
                // During Maven verify, the shaded JAR and dependencies both expose the
                // same archive. Select one; the default resolver rejects duplicates.
                .setPgBinaryResolver((system, architecture) -> {
                    String cpu = architecture.toLowerCase().contains("arm")
                            || architecture.toLowerCase().contains("aarch")
                            ? "arm_64" : "x86_64";
                    String archive = "postgres-" + system.toLowerCase(java.util.Locale.ROOT)
                            + "-" + cpu + ".txz";
                    java.io.InputStream binary = SystemProjectionPostgresIT.class
                            .getClassLoader().getResourceAsStream(archive);
                    if (binary == null) throw new java.io.IOException(
                            "No embedded PostgreSQL binary for " + system + "/" + cpu);
                    return binary;
                }).start()) {
            UUID actor = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            UUID group = UUID.randomUUID();
            UUID secondGroup = UUID.randomUUID();
            UUID capability = UUID.randomUUID();
            UUID credential = UUID.randomUUID();
            setup(pg.getPostgresDatabase(), actor, other, group, secondGroup, capability,
                    credential);
            DataSource runtime = runtimeSource(pg);
            String groupPath = path("auth", "group_account", "group_id", group.toString());
            String capabilityPath = path("auth", "capability", "capability_id",
                    capability.toString());
            String credentialPath = path("auth", "user_credential", "user_id", credential.toString());
            try (Connection connection = runtime.getConnection()) {
                beginAs(connection, actor);
                JdbcAdminProjectionRepository repository =
                        new JdbcAdminProjectionRepository(connection);
                repository.describe(actor, "auth", "group_account");
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT set_config('app.cilexec_user_id','" + other
                            + "',true)");
                }
                assertThrows(SecurityException.class,
                        () -> repository.schemas(actor));
                assertThrows(SecurityException.class,
                        () -> repository.describe(actor, "auth", "group_account"));
                connection.rollback();
            }
            try (Connection connection = runtime.getConnection()) {
                beginAs(connection, actor);
                AdminProjectionRepository repository = new JdbcAdminProjectionRepository(connection);
                SystemProjection projection = new SystemProjection(context(connection, repository,
                        new JdbcAuditRepository(connection, new JsonCodec()), true), actor, NOW);
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                             "SELECT count(*) FROM auth.group_account")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1)); // tenant RLS hides both other-owned rows
                }
                assertTrue(projection.read(groupPath).contains("old-name"));
                assertFalse(projection.read(groupPath).contains("hidden-marker"));
                assertThrows(SecurityException.class, () -> projection.read(credentialPath));
                assertThrows(SecurityException.class, () -> projection.list(
                        "/.cilexec/system/db/object_store/object"));
                assertThrows(SecurityException.class, () -> projection.read(
                        path("terminal", "input", "input_id", UUID.randomUUID().toString())));
                assertThrows(FclRuntimeException.class,
                        () -> new SystemProjection(context(connection, repository, null, false),
                                actor, NOW).read(groupPath));
                assertThrows(FclRuntimeException.class,
                        () -> projection.create(groupPath, "{\"group_name\":\"forbidden\"}"));
                assertThrows(SecurityException.class, () -> projection.remove(groupPath));
                assertThrows(SecurityException.class, () -> repository.deleteRow(actor,
                        "audit", "event", "{\"event_id\":\"" + UUID.randomUUID() + "\"}"));
                assertThrows(IllegalArgumentException.class, () -> repository.readRow(actor,
                        "auth", "group_account", "{}"));
                assertThrows(SecurityException.class, () -> repository.writeRow(actor,
                        "auth", "group_account", "{\"group_id\":\"" + UUID.randomUUID()
                                + "\",\"group_name\":\"cannot-create\"}"));
                assertThrows(IllegalArgumentException.class, () -> projection.write(groupPath,
                        "{\"unknown\":\"ignored\"}"));
                assertThrows(IllegalArgumentException.class, () -> projection.write(groupPath,
                        "{\"group_name\":\"first\",\"group_name\":\"second\"}"));
                assertThrows(SecurityException.class, () -> projection.write(groupPath,
                        "{\"status\":\"DISABLED\"}"));
                assertThrows(FclRuntimeException.class, () -> projection.write(groupPath,
                        "{\"group_id\":\"" + UUID.randomUUID()
                                + "\",\"group_name\":\"changed\"}"));
                projection.write(groupPath, "{\"group_name\":\"new-name\"}");
                projection.write(capabilityPath, "{\"description\":\"revised\"}");
                assertTrue(projection.read(capabilityPath).contains("revised"));
                connection.commit();
            }
            try (Connection check = pg.getPostgresDatabase().getConnection();
                 Statement statement = check.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT group_name FROM auth.group_account "
                         + "WHERE group_id='" + group + "'")) {
                assertTrue(rows.next());
                assertEquals("new-name", rows.getString(1));
            }

            try (Connection connection = runtime.getConnection()) {
                beginAs(connection, actor);
                SystemProjection projection = new SystemProjection(context(connection,
                        new JdbcAdminProjectionRepository(connection),
                        new JdbcAuditRepository(connection, new JsonCodec()), true), actor, NOW);
                projection.write(groupPath, "{\"group_name\":\"temporary\"}");
                RuntimeException invalid = assertThrows(RuntimeException.class,
                        () -> projection.write(groupPath,
                                "{\"group_name\":\"\"}")); // database CHECK
                assertEquals(null, invalid.getCause()); // no value-bearing JDBC detail
                connection.rollback();
            }
            try (Connection check = pg.getPostgresDatabase().getConnection();
                 Statement statement = check.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT actor_id,action,resource_type,"
                         + "resource_id,result,details_json::text FROM audit.event "
                         + "WHERE resource_type='auth.group_account'")) {
                assertTrue(rows.next());
                assertEquals(actor.toString(), rows.getString(1));
                assertEquals("projection.update", rows.getString(2));
                assertEquals("auth.group_account", rows.getString(3));
                assertTrue(rows.getString(4).contains(group.toString()));
                assertEquals("SUCCEEDED", rows.getString(5));
                assertEquals("{}", rows.getString(6));
                assertFalse(rows.next());
            }

            try (Connection connection = runtime.getConnection()) {
                beginAs(connection, actor);
                AdminProjectionRepository repository = new JdbcAdminProjectionRepository(connection);
                AuditRepository failingAudit = new AuditRepository() {
                    @Override public void append(AuditEvent event) {
                        throw new IllegalStateException("audit unavailable");
                    }
                    @Override public List<AuditEvent> findByResource(String type, String id,
                                                                     int limit) {
                        return List.of();
                    }
                };
                SystemProjection projection = new SystemProjection(context(connection, repository,
                        failingAudit, true),
                        actor, NOW);
                assertThrows(IllegalStateException.class, () -> projection.write(groupPath,
                        "{\"group_name\":\"must-rollback\"}"));
                connection.commit(); // The rollback already removed the un-audited update.
            }
            try (Connection check = pg.getPostgresDatabase().getConnection();
                 Statement statement = check.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT group_name FROM auth.group_account "
                         + "WHERE group_id='" + group + "'")) {
                assertTrue(rows.next());
                assertEquals("new-name", rows.getString(1));
            }
            String secondPath = path("auth", "group_account", "group_id", secondGroup.toString());
            java.util.concurrent.CompletableFuture<Void> first = java.util.concurrent.CompletableFuture
                    .runAsync(() -> updateConcurrently(runtime, actor, groupPath, "parallel-one"));
            java.util.concurrent.CompletableFuture<Void> second = java.util.concurrent.CompletableFuture
                    .runAsync(() -> updateConcurrently(runtime, actor, secondPath, "parallel-two"));
            java.util.concurrent.CompletableFuture.allOf(first, second).join();
            try (Connection check = pg.getPostgresDatabase().getConnection();
                 Statement statement = check.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT count(*) FROM auth.group_account WHERE group_name IN "
                                 + "('parallel-one','parallel-two')")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            java.util.concurrent.CompletableFuture<Void> sameFirst =
                    java.util.concurrent.CompletableFuture.runAsync(
                            () -> updateConcurrently(runtime, actor, groupPath, "race-one"));
            java.util.concurrent.CompletableFuture<Void> sameSecond =
                    java.util.concurrent.CompletableFuture.runAsync(
                            () -> updateConcurrently(runtime, actor, groupPath, "race-two"));
            java.util.concurrent.CompletableFuture.allOf(sameFirst, sameSecond).join();
            try (Connection check = pg.getPostgresDatabase().getConnection();
                 Statement statement = check.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT group_name FROM auth.group_account "
                         + "WHERE group_id='" + group + "'")) {
                assertTrue(rows.next());
                assertTrue(Set.of("race-one", "race-two").contains(rows.getString(1)));
            }
            try (Connection check = pg.getPostgresDatabase().getConnection();
                 Statement statement = check.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT count(*) FROM audit.event")) {
                assertTrue(rows.next());
                assertEquals(6, rows.getInt(1)); // one event per committed update only
            }
        }
    }

    private static void setup(DataSource postgres, UUID actor, UUID other, UUID group,
                              UUID secondGroup, UUID capability,
                              UUID credential) throws Exception {
        String tenant = UserAccount.roleNameFor(actor);
        try (Connection connection = postgres.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE ROLE cilexec_runtime LOGIN");
            sql.execute("CREATE ROLE " + tenant + " NOLOGIN");
            sql.execute("GRANT " + tenant + " TO cilexec_runtime");
            for (String schema : List.of("auth", "audit", "object_store", "terminal")) {
                sql.execute("CREATE SCHEMA " + schema);
                sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO cilexec_runtime, " + tenant);
            }
            sql.execute("CREATE TABLE auth.group_account (group_id uuid PRIMARY KEY, owner_id uuid "
                    + "NOT NULL, group_name text NOT NULL CHECK (btrim(group_name) <> ''), "
                    + "status text NOT NULL, created_at timestamptz DEFAULT clock_timestamp(), "
                    + "updated_at timestamptz DEFAULT clock_timestamp(), "
                    + "future_private_column text DEFAULT 'hidden-marker')");
            sql.execute("CREATE TABLE auth.user_credential (user_id uuid PRIMARY KEY, "
                    + "password_hash text NOT NULL, updated_at timestamptz DEFAULT clock_timestamp())");
            sql.execute("CREATE TABLE auth.capability (capability_id uuid PRIMARY KEY, "
                    + "capability_key text NOT NULL, description text NOT NULL, "
                    + "system_capability boolean NOT NULL DEFAULT false, "
                    + "created_at timestamptz NOT NULL DEFAULT clock_timestamp())");
            sql.execute("CREATE TABLE object_store.object (object_hash text PRIMARY KEY, "
                    + "content bytea NOT NULL)");
            sql.execute("CREATE TABLE terminal.input (input_id uuid PRIMARY KEY, payload text)");
            sql.execute("CREATE TABLE audit.event (event_id uuid PRIMARY KEY, owner_id uuid, "
                    + "actor_type text NOT NULL, actor_id text NOT NULL, action text NOT NULL, "
                    + "resource_type text NOT NULL, resource_id text NOT NULL, result text NOT NULL, "
                    + "details_json jsonb NOT NULL, created_at timestamptz NOT NULL)");
            sql.execute("CREATE FUNCTION auth.current_cilexec_user_id() RETURNS uuid "
                    + "LANGUAGE sql AS 'SELECT current_setting(''app.cilexec_user_id'')::uuid'");
            sql.execute("CREATE FUNCTION auth.current_cilexec_user_is_system_administrator() "
                    + "RETURNS boolean LANGUAGE sql AS 'SELECT current_user = ''" + tenant
                    + "'' AND current_setting(''app.cilexec_user_id'')::uuid = ''" + actor
                    + "''::uuid'");
            sql.execute("GRANT SELECT, UPDATE ON auth.group_account TO cilexec_runtime");
            sql.execute("GRANT SELECT, UPDATE ON auth.capability TO cilexec_runtime");
            sql.execute("GRANT SELECT ON auth.group_account TO " + tenant);
            sql.execute("GRANT SELECT ON auth.user_credential, object_store.object, "
                    + "terminal.input TO cilexec_runtime");
            sql.execute("GRANT INSERT ON audit.event TO " + tenant);
            sql.execute("ALTER TABLE auth.group_account ENABLE ROW LEVEL SECURITY");
            sql.execute("ALTER TABLE auth.group_account FORCE ROW LEVEL SECURITY");
            sql.execute("CREATE POLICY runtime_all ON auth.group_account TO cilexec_runtime "
                    + "USING (true) WITH CHECK (true)");
            sql.execute("CREATE POLICY tenant_own ON auth.group_account TO " + tenant
                    + " USING (owner_id = auth.current_cilexec_user_id())");
            sql.execute("INSERT INTO auth.group_account(group_id,owner_id,group_name,status) "
                    + "VALUES ('" + group + "','" + other + "','old-name','ACTIVE')");
            sql.execute("INSERT INTO auth.group_account(group_id,owner_id,group_name,status) "
                    + "VALUES ('" + secondGroup + "','" + other + "','second-name','ACTIVE')");
            sql.execute("INSERT INTO auth.user_credential(user_id,password_hash) VALUES ('"
                    + credential + "','pbkdf2-sha256$dummy')");
            sql.execute("INSERT INTO auth.capability(capability_id,capability_key,description) "
                    + "VALUES ('" + capability + "','display-only','old description')");
        }
    }

    private static DataSource runtimeSource(EmbeddedPostgres pg) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL("jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres");
        source.setUser("cilexec_runtime");
        return source;
    }

    private static void updateConcurrently(DataSource runtime, UUID actor, String path,
                                           String name) {
        try (Connection connection = runtime.getConnection()) {
            beginAs(connection, actor);
            SystemProjection projection = new SystemProjection(context(connection,
                    new JdbcAdminProjectionRepository(connection),
                    new JdbcAuditRepository(connection, new JsonCodec()), true), actor, NOW);
            projection.write(path, "{\"group_name\":\"" + name + "\"}");
            connection.commit();
        } catch (Exception failure) {
            throw new IllegalStateException("Concurrent projection update failed", failure);
        }
    }

    private static void beginAs(Connection connection, UUID actor) throws Exception {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE " + UserAccount.roleNameFor(actor));
            statement.execute("SELECT set_config('app.cilexec_user_id','" + actor + "',true)");
        }
    }

    private static String path(String schema, String table, String key, String value) {
        String json = "{\"" + key + "\":\"" + value + "\"}";
        return "/.cilexec/system/db/" + schema + "/" + table + "/"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                        json.getBytes(StandardCharsets.UTF_8)) + ".json";
    }

    private static TransactionContext context(Connection connection,
                                              AdminProjectionRepository repository,
                                              AuditRepository audit, boolean administrator) {
        AuthRepository auth = (AuthRepository) Proxy.newProxyInstance(
                AuthRepository.class.getClassLoader(), new Class<?>[] {AuthRepository.class},
                (proxy, method, args) -> method.getName().equals("capabilities")
                        ? (administrator ? Set.of(Capability.SYSTEM_ADMIN) : Set.of()) : null);
        VfsRepository vfs = (VfsRepository) Proxy.newProxyInstance(
                VfsRepository.class.getClassLoader(), new Class<?>[] {VfsRepository.class},
                (proxy, method, args) -> method.getName().equals("findChild")
                        ? Optional.empty() : null);
        return (TransactionContext) Proxy.newProxyInstance(
                TransactionContext.class.getClassLoader(),
                new Class<?>[] {TransactionContext.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "adminProjection" -> repository;
                        case "auth" -> auth;
                        case "vfs" -> vfs;
                        case "audit" -> audit;
                        case "rollback" -> { connection.rollback(); yield null; }
                        default -> null;
                    };
                });
    }
}
