package com.follarce.persistence.postgres.connection;

import com.follarce.config.DatabaseConfig;
import com.follarce.config.DockerSecretLoader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Creates bounded role-specific pools; callers own and close the result. */
public final class DataSourceFactory {
    private static final long LEAK_DETECTION_THRESHOLD_MS = 300_000;

    private DataSourceFactory() {
    }

    public static HikariDataSource create(DatabaseConfig database) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(database.jdbcUrl());
        hikari.setUsername(database.username());
        try (DockerSecretLoader.SecretValue secret = DockerSecretLoader.read(database.passwordFile())) {
            hikari.setPassword(secret.exposeForDriver());
        }
        hikari.setPoolName(database.applicationName());
        // Pool invariants are enforced by CilExecConfig at load time; the factory respects
        // the configured maximum as-is.
        hikari.setMaximumPoolSize(database.maximumPoolSize());
        hikari.setMinimumIdle(database.minimumIdle());
        hikari.setConnectionTimeout(database.connectionTimeout().toMillis());
        hikari.setValidationTimeout(database.validationTimeout().toMillis());
        hikari.setLeakDetectionThreshold(LEAK_DETECTION_THRESHOLD_MS);
        hikari.setAutoCommit(false);
        hikari.setReadOnly(false);
        hikari.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        hikari.setRegisterMbeans(false);
        hikari.setInitializationFailTimeout(database.connectionTimeout().toMillis());
        hikari.addDataSourceProperty("ApplicationName", database.applicationName());
        hikari.addDataSourceProperty("tcpKeepAlive", "true");
        hikari.addDataSourceProperty("reWriteBatchedInserts", "true");
        hikari.addDataSourceProperty("assumeMinServerVersion", "17");
        hikari.addDataSourceProperty("TimeZone", "UTC");
        return new HikariDataSource(hikari);
    }
}
