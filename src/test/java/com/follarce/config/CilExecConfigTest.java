package com.follarce.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CilExecConfigTest {
    @Test
    void defaultsReserveRuntimePoolCapacity() {
        Map<String, String> environment = Map.of("CILEXEC_RUNTIME_POOL_MAX", "20");
        CilExecConfig config = CilExecConfig.load(environment);
        assertEquals("cilexec", config.instanceName());
        assertEquals(10, config.schedulerWorkers());
        assertEquals(20, config.runtimeDatabase().maximumPoolSize());
        assertEquals(6, config.effectWorkers());
        assertEquals(6, config.effectDatabase().maximumPoolSize());
        assertEquals(Duration.ofMillis(25), config.schedulerErrorBackoff());
        assertEquals(Duration.ofMillis(25), config.effectErrorBackoff());
        assertEquals(Duration.ofSeconds(30), config.runtimeDatabase().statementTimeout());
        assertEquals(Duration.ofSeconds(10), config.healthDatabaseProbeInterval());
        assertEquals("cilexec_exporter", config.exporterDatabase().username());
        assertTrue(config.exporterDatabase().readOnly());
        assertFalse(config.runtimeDatabase().readOnly());
    }

    @Test
    void shippedDefaultPoolSizeRejectsCombinedWorkerDemand() {
        assertThrows(ConfigException.class, () -> CilExecConfig.load(Map.of(
                "CILEXEC_RUNTIME_POOL_MAX", "12")));
    }

    @Test
    void effectErrorBackoffIsConfiguredIndependently() {
        CilExecConfig config = CilExecConfig.load(Map.of(
                "CILEXEC_EFFECT_ERROR_BACKOFF", "PT0.004S"));

        assertEquals(Duration.ofMillis(4), config.effectErrorBackoff());
    }

    @Test
    void rejectsUnboundedWorkerConfiguration() {
        Map<String, String> environment = new HashMap<>();
        environment.put("CILEXEC_SCHEDULER_WORKERS", "8");
        environment.put("CILEXEC_RUNTIME_POOL_MAX", "8");
        assertThrows(ConfigException.class, () -> CilExecConfig.load(environment));
    }

    @Test
    void rejectsSafetyLimitViolations() {
        assertThrows(ConfigException.class, () -> CilExecConfig.load(Map.of(
                "CILEXEC_SCHEDULER_WORKERS", "257",
                "CILEXEC_RUNTIME_POOL_MAX", "512")));
        assertThrows(ConfigException.class, () -> CilExecConfig.load(Map.of(
                "CILEXEC_DATABASE_STATEMENT_TIMEOUT", "PT11M")));
        assertThrows(ConfigException.class, () -> CilExecConfig.load(Map.of(
                "CILEXEC_HEALTH_DATABASE_PROBE_INTERVAL", "PT0.5S")));
        assertThrows(ConfigException.class, () -> CilExecConfig.load(Map.of(
                "CILEXEC_EFFECT_POOL_MAX", "513")));
    }

    @Test
    void remoteDatabaseRequiresDedicatedRootCaAndVerifyFullTls() {
        assertThrows(ConfigException.class, () -> CilExecConfig.load(Map.of(
                "CILEXEC_DATABASE_URL", "jdbc:postgresql://db.example.com/cilexec")));

        CilExecConfig config = CilExecConfig.load(Map.of(
                "CILEXEC_DATABASE_URL", "jdbc:postgresql://db.example.com/cilexec",
                "CILEXEC_DATABASE_SSL_ROOT_CERTIFICATE_FILE", "/run/certs/postgres-ca.pem"));

        assertTrue(config.runtimeDatabase().verifyFullTls());
        assertEquals(java.nio.file.Path.of("/run/certs/postgres-ca.pem"),
                config.exporterDatabase().sslRootCertificateFile().orElseThrow());
    }

    @Test
    void rejectsCredentialsAndTlsOverridesInJdbcUrl() {
        assertThrows(ConfigException.class, () -> CilExecConfig.load(Map.of(
                "CILEXEC_DATABASE_URL",
                "jdbc:postgresql://localhost/cilexec?password=secret")));
        assertThrows(ConfigException.class, () -> CilExecConfig.load(Map.of(
                "CILEXEC_DATABASE_URL",
                "jdbc:postgresql://localhost/cilexec?sslmode=disable")));
        assertThrows(ConfigException.class, () -> CilExecConfig.load(Map.of(
                "CILEXEC_DATABASE_URL",
                "jdbc:postgresql://user@localhost/cilexec")));
    }
}
