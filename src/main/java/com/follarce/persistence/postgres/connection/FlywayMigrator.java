package com.follarce.persistence.postgres.connection;

import com.follarce.config.DatabaseConfig;
import com.follarce.config.SecretFileLoader;
import com.follarce.config.LocalDatabaseCredentials;
import com.follarce.version.ReleaseVersion;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Production migration entry point; it is only constructed with the migrator Role. */
public final class FlywayMigrator {
    private final DatabaseConfig database;

    public FlywayMigrator(DatabaseConfig database) {
        this.database = database;
    }

    public MigrateResult migrate() {
        requireMigratableDatabase();
        MigrateResult result = flyway().migrate();
        if (!result.success) {
            throw new IllegalStateException("Flyway migration did not complete successfully");
        }
        return result;
    }

    /** validateOnMigrate is enabled, so migrate() already validates; kept for existing callers. */
    public void validate() {
        flyway().validate();
    }

    /**
     * Refuses old, unknown, newer, or failed persistent data before Flyway can apply anything.
     * The inspection connection is read-only in effect: it only queries catalog and history.
     */
    private void requireMigratableDatabase() {
        int requiredSchema = ReleaseVersion.schemaNumber(ReleaseVersion.current());
        try (Connection connection = inspectionConnection()) {
            DatabaseFormatFence.requireMigratable(
                    DatabaseFormatFence.inspect(connection), requiredSchema);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Cannot inspect the database format before migration", failure);
        }
    }

    private Connection inspectionConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", database.username());
        properties.setProperty("password", password());
        properties.setProperty("ApplicationName", database.applicationName() + "-format-fence");
        properties.setProperty("tcpKeepAlive", "true");
        properties.setProperty("connectTimeout", "15");
        if (database.verifyFullTls()) {
            properties.setProperty("sslmode", "verify-full");
            properties.setProperty("sslrootcert",
                    database.sslRootCertificateFile().orElseThrow().toString());
        } else {
            properties.setProperty("sslmode", "disable");
        }
        return DriverManager.getConnection(database.jdbcUrl(), properties);
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(database.jdbcUrl(), database.username(), password())
                .locations("classpath:db/migration")
                .defaultSchema("flyway")
                .schemas("flyway")
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .initSql("SET statement_timeout = "
                        + database.statementTimeout().toMillis())
                .baselineOnMigrate(false)
                .load();
    }

    private String password() {
        if (database.passwordFile() == null) {
            return LocalDatabaseCredentials.password(database.username());
        }
        try (SecretFileLoader.SecretValue secret = SecretFileLoader.read(database.passwordFile())) {
            return secret.exposeForDriver();
        }
    }
}
