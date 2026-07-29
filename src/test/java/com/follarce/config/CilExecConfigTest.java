package com.follarce.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CilExecConfigTest {
    @Test
    void defaultsReserveRuntimePoolCapacity() {
        CilExecConfig config = CilExecConfig.load(Map.of());
        assertEquals("cilexec", config.instanceName());
        assertEquals(10, config.schedulerWorkers());
        assertEquals(12, config.runtimeDatabase().maximumPoolSize());
        assertEquals(6, config.effectWorkers());
        assertEquals(6, config.effectDatabase().maximumPoolSize());
        assertEquals(Duration.ofMillis(25), config.schedulerErrorBackoff());
        assertEquals(Duration.ofMillis(25), config.effectErrorBackoff());
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
}
