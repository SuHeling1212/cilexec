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
        assertEquals(4, config.schedulerWorkers());
        assertEquals(8, config.runtimeDatabase().maximumPoolSize());
        assertEquals(Duration.ofMillis(25), config.schedulerIdlePoll());
    }

    @Test
    void rejectsUnboundedWorkerConfiguration() {
        Map<String, String> environment = new HashMap<>();
        environment.put("CILEXEC_SCHEDULER_WORKERS", "8");
        environment.put("CILEXEC_RUNTIME_POOL_MAX", "8");
        assertThrows(ConfigException.class, () -> CilExecConfig.load(environment));
    }
}
