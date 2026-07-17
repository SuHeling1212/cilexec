package com.follarce.process;

import com.follarce.Constants;
import com.follarce.function.FunctionRegistry;
import com.follarce.function.PathFunctionProvider;
import com.follarce.function.ProcessFunctionProvider;
import com.follarce.function.UserFunctionProvider;
import com.follarce.function.NetworkFunctionProvider;
import com.follarce.function.EffectPolicy;
import com.follarce.init.FileInit;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.UserUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProcessPersistenceContextTest {
    @TempDir Path root;

    @BeforeAll
    static void registerProviders() {
        FunctionRegistry.registerProvider(new ProcessFunctionProvider());
        FunctionRegistry.registerProvider(new UserFunctionProvider());
        FunctionRegistry.registerProvider(new PathFunctionProvider());
        FunctionRegistry.registerProvider(new NetworkFunctionProvider());
    }

    @BeforeEach
    void initialize() {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        assertEquals("User created: bob", UserUtil.createUser("bob", "pw", false, "create-bob"));
    }

    @AfterEach
    void cleanup() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void effectiveUserSurvivesRunnerReconstruction() {
        writeProcess(process(600, List.of(
                "switchUser(\"bob\", \"pw\")",
                "who = getCurrentUser()"), "local", Map.of()));
        ProcessRunner first = runner(600);
        first.step();
        assertEquals("bob", readProcess(600).get("EffectiveUser"));
        assertEquals("local", readProcess(600).get("Owner"));
        first.stopProcess();

        ProcessRunner restored = runner(600);
        restored.step();
        assertEquals("bob", nested(readProcess(600), "Program", "Data", "who"));
        ProcessRunner.terminateProcess(600);
    }

    @Test
    void processAliasPersistsAndOverridesGlobalAlias() {
        writeProcess(process(601, List.of(
                "setAlias(\"temp\", \"/user/bob/app\")",
                "resolved = resolve(\"@temp/tool\")"), "local", Map.of()));
        ProcessRunner first = runner(601);
        first.step();
        first.stopProcess();

        ProcessRunner restored = runner(601);
        restored.step();
        assertEquals("/user/bob/app/tool", nested(readProcess(601), "Program", "Data", "resolved"));
        assertEquals("/user/bob/app", nested(readProcess(601), "PathAliases", "temp"));
        ProcessRunner.terminateProcess(601);
    }

    @Test
    void forkInheritsCredentialsAndAliasesButGetsNewGeneration() {
        Map<String, Object> parent = process(602, List.of("child = fork()"), "bob",
                Map.of("work", "/user/bob/app"));
        writeProcess(parent);
        ProcessRunner runner = runner(602);
        runner.step();
        int childPid = ((Number) nested(readProcess(602), "Program", "Data", "child")).intValue();
        Map<String, Object> child = readProcess(childPid);

        assertEquals("bob", child.get("EffectiveUser"));
        assertEquals("/user/bob/app", nested(child, "PathAliases", "work"));
        assertNotEquals(readProcess(602).get("ProcessGeneration"), child.get("ProcessGeneration"));
        assertEquals(readProcess(602).get("ProcessGeneration"), nested(child, "Parent", "Generation"));
        ProcessRunner.terminateProcess(childPid);
        ProcessRunner.terminateProcess(602);
    }

    @Test
    void interruptedExternalEffectBlocksUntilExplicitResolution() {
        String line = "answer = network.httpPost(\"https://example.invalid\", \"{}\")";
        Map<String, Object> process = process(603, List.of(line), "local", Map.of());
        StatementAttemptManager attempt = new StatementAttemptManager(process, () -> {});
        attempt.begin(0, line);
        assertThrows(SimulatedCrash.class, () -> attempt.invoke(
                "network.httpPost", EffectPolicy.MANUAL_RECOVERY,
                List.of("https://example.invalid", "{}"), ignored -> {
                    throw new SimulatedCrash();
                }));
        writeProcess(process);

        ProcessRunner runner = runner(603);
        assertEquals(ProcessRunner.StepResult.BLOCKED, runner.step());
        assertEquals(BlockReason.EFFECT_RECOVERY.name(), readProcess(603).get("BlockReason"));
        assertNotNull(readProcess(603).get("_effectRecovery"));

        assertTrue(ProcessRunner.resolveEffect(603, "result", "confirmed"));
        assertEquals(ProcessRunner.StepResult.COMPLETED, runner.step());
        assertEquals("confirmed", nested(readProcess(603), "Program", "Data", "answer"));
        ProcessRunner.terminateProcess(603);
    }

    private static ProcessRunner runner(int pid) {
        ProcessRunner runner = new ProcessRunner(pid, readProcess(pid));
        runner.init();
        return runner;
    }

    private static Map<String, Object> process(int pid, List<String> lines,
                                                String effectiveUser, Map<String, String> aliases) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", "P" + pid);
        process.put("Owner", "local");
        process.put("EffectiveUser", effectiveUser);
        process.put("ProcessGeneration", "generation-" + pid);
        process.put("PathAliases", new LinkedHashMap<>(aliases));
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
        JsonUtil.writeFile(processPath(((Number) process.get("PID")).intValue()),
                JsonUtil.toMetaJson(process));
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

    private static final class SimulatedCrash extends RuntimeException {}
}
