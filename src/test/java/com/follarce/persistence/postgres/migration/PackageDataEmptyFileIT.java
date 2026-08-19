package com.follarce.persistence.postgres.migration;

import com.follarce.auth.AuthService;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** V002 regression: package private data FILE entries may be empty. */
@Testcontainers
class PackageDataEmptyFileIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getProperty("cilexec.test.postgres.image", "postgres:17.10-alpine3.23"));

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = adminConnection()) {
            com.follarce.persistence.postgres.PostgresTestBootstrap.createServiceRoles(connection);
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(),
                        com.follarce.persistence.postgres.PostgresTestBootstrap.MIGRATOR_ROLE,
                        com.follarce.persistence.postgres.PostgresTestBootstrap.DEFAULT_PASSWORD)
                .locations("classpath:db/migration")
                .defaultSchema("flyway")
                .schemas("flyway")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @Test
    void storesEmptyFilesAndAllowsOverwritingToEmpty() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcTransactionExecutor transactions = new JdbcTransactionExecutor(dataSource);
        UserAccount owner = new AuthService(transactions,
                Clock.fixed(java.time.Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC))
                .create("empty-file-owner", "owner-password-123".toCharArray(),
                        Set.of(Capability.VFS_READ, Capability.VFS_WRITE));
        UUID ownerId = owner.userId();

        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO object_store.object(object_hash,byte_size,media_type,"
                    + "content) VALUES (sha256(''::bytea),0,'application/octet-stream',''::bytea)");
            statement.execute("INSERT INTO package.release(package_hash,namespace,"
                    + "package_name,package_version,database_object_hash,database_file_hash,"
                    + "package_format_version,metadata_json,imported_by,created_at) VALUES ("
                    + "sha256('empty-pkg'::bytea),'demo','empty-pkg','1.0.0',"
                    + "sha256(''::bytea),sha256(''::bytea),2,'{}'::jsonb,'" + ownerId
                    + "'::uuid,clock_timestamp())");
            statement.execute("INSERT INTO package.release_identity(package_hash,namespace,"
                    + "package_name,package_version,database_file_hash) VALUES ("
                    + "sha256('empty-pkg'::bytea),'demo','empty-pkg','1.0.0',"
                    + "sha256(''::bytea))");
            statement.execute("INSERT INTO package.installation_root("
                    + "installation_id,owner_id,root_package_hash,source) VALUES ('"
                    + UUID.randomUUID() + "'::uuid,'" + ownerId + "'::uuid,"
                    + "sha256('empty-pkg'::bytea),'LOCAL')");
            statement.execute("INSERT INTO package.installation_member("
                    + "installation_id,owner_id,package_hash,dependency_depth,optional) "
                    + "SELECT installation_id,'" + ownerId + "'::uuid,"
                    + "sha256('empty-pkg'::bytea),0,false "
                    + "FROM package.installation_root WHERE owner_id='" + ownerId + "'::uuid");
            statement.execute("INSERT INTO package.data_space("
                    + "space_id,owner_id,package_hash,database_file_hash,logical_bytes) VALUES ('"
                    + UUID.randomUUID() + "'::uuid,'" + ownerId + "'::uuid,"
                    + "sha256('empty-pkg'::bytea),sha256(''::bytea),0)");
            // Creating an empty file must satisfy the relaxed FILE constraint.
            statement.execute("INSERT INTO package.data_entry(space_id,relative_path,"
                    + "entry_type,object_hash,byte_size,state_version) "
                    + "SELECT space_id,'empty.txt','FILE',sha256(''::bytea),0,1 "
                    + "FROM package.data_space WHERE owner_id='" + ownerId + "'::uuid");
            // Overwriting a non-empty file with empty content must also pass.
            statement.execute("INSERT INTO object_store.object(object_hash,byte_size,media_type,"
                    + "content) VALUES (sha256('x'::bytea),1,'text/plain','x'::bytea)");
            statement.execute("INSERT INTO package.data_entry(space_id,relative_path,"
                    + "entry_type,object_hash,byte_size,state_version) "
                    + "SELECT space_id,'note.txt','FILE',sha256('x'::bytea),1,1 "
                    + "FROM package.data_space WHERE owner_id='" + ownerId + "'::uuid");
            statement.execute("UPDATE package.data_entry "
                    + "SET object_hash=sha256(''::bytea),byte_size=0,state_version=2 "
                    + "WHERE relative_path='note.txt'");
        }

        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT count(*) FROM package.data_entry JOIN package.data_space "
                             + "USING (space_id) WHERE package.data_space.owner_id='" + ownerId
                             + "'::uuid AND package.data_entry.byte_size=0 "
                             + "AND package.data_entry.object_hash=sha256(''::bytea)")) {
            rows.next();
            assertEquals(2, rows.getInt(1),
                    "empty.txt and the overwritten note.txt must both be stored as 0-byte files");
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
