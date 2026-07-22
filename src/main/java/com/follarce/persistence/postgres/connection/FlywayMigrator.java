package com.follarce.persistence.postgres.connection;

import com.follarce.config.DatabaseConfig;
import com.follarce.config.DockerSecretLoader;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** Production migration entry point; it is only constructed with the migrator Role. */
public final class FlywayMigrator {
    private final DatabaseConfig database;

    public FlywayMigrator(DatabaseConfig database) {
        this.database = database;
    }

    public MigrateResult migrate() {
        return flyway().migrate();
    }

    public void validate() {
        flyway().validate();
    }

    private Flyway flyway() {
        try (DockerSecretLoader.SecretValue secret = DockerSecretLoader.read(database.passwordFile())) {
            return Flyway.configure()
                    .dataSource(database.jdbcUrl(), database.username(), secret.exposeForDriver())
                    .locations("classpath:db/migration")
                    .defaultSchema("flyway")
                    .schemas("flyway")
                    .validateMigrationNaming(true)
                    .validateOnMigrate(true)
                    .cleanDisabled(true)
                    .baselineOnMigrate(false)
                    .load();
        }
    }
}
