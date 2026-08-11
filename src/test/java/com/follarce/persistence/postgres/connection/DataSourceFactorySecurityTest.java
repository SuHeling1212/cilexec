package com.follarce.persistence.postgres.connection;

import com.follarce.config.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataSourceFactorySecurityTest {
    @Test
    void configuresVerifyFullWithDedicatedRootCertificate() {
        DatabaseConfig database = config("jdbc:postgresql://db.example.com/cilexec",
                Optional.of(Path.of("/run/certs/postgres-ca.pem")));
        HikariConfig hikari = new HikariConfig();

        DataSourceFactory.configureTls(hikari, database);

        assertEquals("verify-full", hikari.getDataSourceProperties().get("sslmode"));
        assertEquals("/run/certs/postgres-ca.pem",
                hikari.getDataSourceProperties().get("sslrootcert"));
    }

    @Test
    void explicitlyDisablesTlsOnlyForLoopbackWithoutCa() {
        DatabaseConfig database = config("jdbc:postgresql://127.0.0.1/cilexec",
                Optional.empty());
        HikariConfig hikari = new HikariConfig();

        DataSourceFactory.configureTls(hikari, database);

        assertEquals("disable", hikari.getDataSourceProperties().get("sslmode"));
    }

    private static DatabaseConfig config(String url, Optional<Path> rootCertificate) {
        return new DatabaseConfig(url, "cilexec_runtime", Path.of("/run/secrets/password"),
                rootCertificate, 2, 0, Duration.ofSeconds(2), Duration.ofSeconds(1),
                Duration.ofSeconds(30), "test", false);
    }
}
