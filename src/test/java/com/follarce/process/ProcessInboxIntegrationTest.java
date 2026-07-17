package com.follarce.process;

import com.follarce.Constants;
import com.follarce.init.FileInit;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.UserUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProcessInboxIntegrationTest {
    @TempDir Path root;

    @BeforeEach
    void initialize() {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
    }

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void offlinePauseIsAppliedOnceAndReceiptIsDurable() {
        writeProcess(process(500, "generation-500", List.of("while true", "{", "}")));

        ProcessRunner.postMessage(500, "ProcessState", ProcessState.PAUSED.name(),
                "pause-once", 1, "sender");
        Map<String, Object> paused = readProcess(500);
        assertEquals(ProcessState.PAUSED.name(), paused.get("ProcessState"));
        assertTrue(ProcessInbox.isApplied(paused, "pause-once"));
        assertTrue(ProcessInbox.list(500, "generation-500").isEmpty());

        ProcessRunner.postMessage(500, "ProcessState", ProcessState.READY.name(),
                "pause-once", 1, "sender");
        assertEquals(ProcessState.PAUSED.name(), readProcess(500).get("ProcessState"));
    }

    @Test
    void committedReceiptSuppressesReplayWhenMessageDeleteWasInterrupted() {
        writeProcess(process(501, "generation-501", List.of("while true", "{", "}")));
        ProcessMessage message = ProcessInbox.publish(501, "generation-501", "set-once",
                1, "sender", "Program.Data.value", "new");
        JsonUtil.updateFile(processPath(501), data -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> program = (Map<String, Object>) data.get("Program");
            @SuppressWarnings("unchecked")
            Map<String, Object> variables = (Map<String, Object>) program.get("Data");
            variables.put("value", "new");
            ProcessInbox.recordApplied(data, message);
        });

        ProcessRunner runner = new ProcessRunner(501, readProcess(501));
        runner.init();
        runner.step();
        assertTrue(ProcessInbox.list(501, "generation-501").isEmpty());
        assertEquals("new", nested(readProcess(501), "Program", "Data", "value"));
        ProcessRunner.terminateProcess(501);
    }

    @Test
    void staleIncarnationMessageCannotReachReusedPid() {
        writeProcess(process(502, "old-generation", List.of("while true", "{", "}")));
        ProcessInbox.publish(502, "old-generation", "old-message", 1, "sender",
                "Program.Data.value", "stale");
        writeProcess(process(502, "new-generation", List.of("value = \"fresh\"")));

        ProcessRunner runner = new ProcessRunner(502, readProcess(502));
        runner.init();
        runner.step();

        assertEquals("fresh", nested(readProcess(502), "Program", "Data", "value"));
        assertEquals(1, ProcessInbox.list(502, "old-generation").size());
        ProcessInbox.removeIncarnation(502, "old-generation");
        ProcessRunner.terminateProcess(502);
    }

    @Test
    void deliveryLedgerNeverRetargetsAStableMessageIdToReusedPid() {
        writeProcess(process(503, "first-generation", List.of("while true", "{", "}")));
        ProcessInbox.publish(503, "first-generation", "stable-control", 1, "sender",
                "ProcessState", ProcessState.PAUSED.name());
        writeProcess(process(503, "second-generation", List.of("value = \"fresh\"")));

        ProcessRunner.postMessage(503, "ProcessState", ProcessState.PAUSED.name(),
                "stable-control", 1, "sender");

        assertEquals(ProcessState.READY.name(), readProcess(503).get("ProcessState"));
        ProcessInbox.removeIncarnation(503, "first-generation");
    }

    @Test
    void offlineTerminationFinishesRelationshipCleanup() {
        Map<String, Object> parent = process(510, "parent-generation", List.of("while true", "{", "}"));
        parent.put("Child", new LinkedHashMap<>(Map.of("511", Map.of("PID", 511, "Generation", "child-generation"))));
        writeProcess(parent);
        Map<String, Object> child = process(511, "child-generation", List.of("while true", "{", "}"));
        child.put("Parent", new LinkedHashMap<>(Map.of("PID", 510, "Generation", "parent-generation")));
        writeProcess(child);

        ProcessRunner.postMessage(511, "__Terminate", ExitReason.KILLED.name(),
                "kill-child", 510, "parent-generation");

        assertFalse(FileUtil.exists(processPath(511)));
        Map<String, Object> savedParent = readProcess(510);
        assertNull(nested(savedParent, "Child", "511"));
        assertNotNull(nested(savedParent, "ExitedChildren", "511"));
    }

    @Test
    void startupRecoveryAppliesPublishedButUnprocessedMessage() {
        writeProcess(process(520, "generation-520", List.of("while true", "{", "}")));
        ProcessInbox.publish(520, "generation-520", "startup-pause", 1, "sender",
                "ProcessState", ProcessState.PAUSED.name());

        RecoveryManager.recoverAll();

        Map<String, Object> recovered = readProcess(520);
        assertEquals(ProcessState.PAUSED.name(), recovered.get("ProcessState"));
        assertTrue(ProcessInbox.isApplied(recovered, "startup-pause"));
        assertTrue(ProcessInbox.list(520, "generation-520").isEmpty());
    }

    @Test
    void startupRecoveryRepublishesDeliveryWhoseInboxMessageWasNotCommitted() {
        writeProcess(process(521, "generation-521", List.of("while true", "{", "}")));
        ProcessMessage message = ProcessInbox.publish(521, "generation-521", "delivery-only",
                1, "sender", "ProcessState", ProcessState.PAUSED.name());
        ProcessInbox.acknowledge(message); // Simulate a crash after the delivery ledger rename.

        RecoveryManager.recoverAll();

        Map<String, Object> recovered = readProcess(521);
        assertEquals(ProcessState.PAUSED.name(), recovered.get("ProcessState"));
        assertTrue(ProcessInbox.isApplied(recovered, "delivery-only"));
    }

    @Test
    void recoveryDiscardsLegacyChildRelationWhenPidNowBelongsToAnotherProcess() {
        Map<String, Object> parent = process(522, "parent-generation", List.of("while true", "{", "}"));
        parent.put("Child", new LinkedHashMap<>(Map.of("523", new LinkedHashMap<>(Map.of("PID", 523)))));
        writeProcess(parent);
        Map<String, Object> replacement = process(523, "replacement-generation", List.of("while true", "{", "}"));
        replacement.put("Parent", new LinkedHashMap<>());
        writeProcess(replacement);
        Map<String, Object> staleChild = process(524, "stale-child-generation", List.of("while true", "{", "}"));
        staleChild.put("Parent", new LinkedHashMap<>(Map.of("PID", 522)));
        writeProcess(staleChild);

        RecoveryManager.recoverAll();

        assertNull(nested(readProcess(522), "Child", "523"));
        assertNull(nested(readProcess(524), "Parent", "PID"));
    }

    private static Map<String, Object> process(int pid, String generation, List<String> lines) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", "P" + pid);
        process.put("Owner", "local");
        process.put("EffectiveUser", "local");
        process.put("ProcessGeneration", generation);
        process.put("PathAliases", new LinkedHashMap<>());
        process.put("PID", pid);
        process.put("Status", true);
        process.put("ProcessState", ProcessState.READY.name());
        process.put("Priority", Constants.PRIORITY_NORMAL);
        process.put("Parent", new LinkedHashMap<>());
        process.put("Child", new LinkedHashMap<>());
        process.put("ExitedChildren", new LinkedHashMap<>());
        process.put("Execution", new LinkedHashMap<>(Map.of("SchemaVersion", 1, "NextAttemptOrdinal", 0L)));
        Map<String, Object> code = new LinkedHashMap<>();
        code.put("Code", lines);
        code.put("runningCodeLine", 0);
        code.put("BlockStack", new java.util.ArrayList<>());
        Map<String, Object> program = new LinkedHashMap<>();
        program.put("Data", new LinkedHashMap<String, Object>());
        program.put("Code", code);
        process.put("Program", program);
        return process;
    }

    private static void writeProcess(Map<String, Object> process) {
        int pid = ((Number) process.get("PID")).intValue();
        JsonUtil.writeFile(processPath(pid), JsonUtil.toMetaJson(process));
    }

    private static Map<String, Object> readProcess(int pid) {
        return JsonUtil.parseToMapStrict(FileUtil.read(processPath(pid)));
    }

    private static String processPath(int pid) {
        return Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> map, String... path) {
        Object current = map;
        for (String part : path) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }
}
