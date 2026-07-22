package com.follarce.persistence.postgres.connection;

import com.follarce.config.DatabaseConfig;
import com.follarce.config.DockerSecretLoader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Creates bounded role-specific pools; callers own and close the result. */
public final class DataSourceFactory {
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
        hikari.setMaximumPoolSize(database.maximumPoolSize());
        hikari.setMinimumIdle(database.minimumIdle());
        hikari.setConnectionTimeout(database.connectionTimeout().toMillis());
        hikari.setValidationTimeout(database.validationTimeout().toMillis());
        hikari.setAutoCommit(false);
        hikari.setReadOnly(false);
        hikari.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        hikari.setRegisterMbeans(false);
        hikari.setInitializationFailTimeout(database.connectionTimeout().toMillis());
        hikari.addDataSourceProperty("ApplicationName", database.applicationName());
        hikari.addDataSourceProperty("tcpKeepAlive", "true");
        hikari.addDataSourceProperty("reWriteBatchedInserts", "true");
        hikari.addDataSourceProperty("assumeMinServerVersion", "17");
        return new HikariDataSource(hikari);
    }
}
