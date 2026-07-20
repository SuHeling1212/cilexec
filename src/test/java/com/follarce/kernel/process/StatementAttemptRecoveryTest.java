package com.follarce.kernel.process;

import com.follarce.kernel.api.function.EffectPolicy;
import com.follarce.kernel.process.EffectRecoveryRequiredException;
import com.follarce.kernel.process.StatementAttemptManager;
import com.follarce.kernel.util.JsonUtil;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StatementAttemptRecoveryTest {
    @Test
    void completedResultIsReplayedWithoutCallingProviderAgain() {
        Map<String, Object> process = process("generation-a");
        AtomicReference<Map<String, Object>> durable = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        StatementAttemptManager first = manager(process, durable);

        first.begin(3, "value = clock.read()");
        assertEquals(41, first.invoke("clock.read", EffectPolicy.RECORDED_RESULT, List.of(),
                ignored -> calls.incrementAndGet() + 40));
        assertEquals(1, calls.get());

        Map<String, Object> restored = JsonUtil.deepCopy(durable.get());
        StatementAttemptManager replay = manager(restored, durable);
        replay.begin(3, "value = clock.read()");
        assertEquals(41, replay.invoke("clock.read", EffectPolicy.RECORDED_RESULT, List.of(),
                ignored -> calls.incrementAndGet() + 40));
        assertEquals(1, calls.get());
    }

    @Test
    void unsafePreparedEffectBecomesInDoubtUntilOperatorSuppliesResult() {
        Map<String, Object> process = process("generation-b");
        AtomicReference<Map<String, Object>> durable = new AtomicReference<>();
        StatementAttemptManager first = manager(process, durable);
        first.begin(8, "answer = remote.post()");

        assertThrows(SimulatedCrash.class, () -> first.invoke(
                "remote.post", EffectPolicy.MANUAL_RECOVERY, List.of("payload"), ignored -> {
                    throw new SimulatedCrash();
                }));

        Map<String, Object> restored = JsonUtil.deepCopy(durable.get());
        StatementAttemptManager replay = manager(restored, durable);
        replay.begin(8, "answer = remote.post()");
        EffectRecoveryRequiredException inDoubt = assertThrows(EffectRecoveryRequiredException.class,
                () -> replay.invoke("remote.post", EffectPolicy.MANUAL_RECOVERY,
                        List.of("payload"), ignored -> fail("unsafe effect was repeated")));
        assertNotNull(inDoubt.getEffectId());

        Map<String, Object> blocked = JsonUtil.deepCopy(durable.get());
        assertTrue(StatementAttemptManager.resolve(blocked, "result", "accepted"));
        StatementAttemptManager resolved = manager(blocked, durable);
        resolved.begin(8, "answer = remote.post()");
        assertEquals("accepted", resolved.invoke("remote.post", EffectPolicy.MANUAL_RECOVERY,
                List.of("payload"), ignored -> fail("resolved effect was repeated")));
    }

    @Test
    void eachLoopIterationGetsANewAttemptIdentity() {
        Map<String, Object> process = process("generation-c");
        AtomicReference<Map<String, Object>> durable = new AtomicReference<>();
        StatementAttemptManager manager = manager(process, durable);

        manager.begin(2, "i = i + 1");
        String first = activeId(process);
        manager.commit();
        manager.begin(2, "i = i + 1");
        String second = activeId(process);

        assertNotEquals(first, second);
    }

    @SuppressWarnings("unchecked")
    private static String activeId(Map<String, Object> process) {
        Map<String, Object> execution = (Map<String, Object>) process.get("Execution");
        return ((Map<String, Object>) execution.get("ActiveAttempt")).get("Id").toString();
    }

    private static StatementAttemptManager manager(Map<String, Object> process,
                                                   AtomicReference<Map<String, Object>> durable) {
        return new StatementAttemptManager(process,
                () -> durable.set(JsonUtil.deepCopy(process)));
    }

    private static Map<String, Object> process(String generation) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("ProcessGeneration", generation);
        process.put("Owner", "local");
        process.put("EffectiveUser", "local");
        process.put("PathAliases", new LinkedHashMap<>());
        process.put("Execution", new LinkedHashMap<>(Map.of(
                "SchemaVersion", 1,
                "NextAttemptOrdinal", 0L)));
        return process;
    }

    private static final class SimulatedCrash extends RuntimeException {}
}
