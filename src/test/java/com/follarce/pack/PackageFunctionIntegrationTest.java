package com.follarce.pack;

import com.follarce.Constants;
import com.follarce.function.FunctionRegistry;
import com.follarce.function.PackageFunctionProvider;
import com.follarce.init.FileInit;
import com.follarce.process.ProcessRunner;
import com.follarce.process.ProcessState;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.UserUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackageFunctionIntegrationTest {
    @TempDir Path root;

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void fclProcessInstallsIntoItsEffectiveUsersRootWithTransactionalEffect() throws Exception {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        UserUtil.createUser("alice", "pw", false);
        PackageManager.getInstance().initialize();
        FunctionRegistry.registerProvider(new PackageFunctionProvider());

        Path source = PackageTestFixtures.source(root, "provider-source", "tests.pack", "provider", "1.0.0",
                "value", "func value() { return 9 }", List.of(), Map.of());
        Path repository = root.resolve("user/alice/app/repository");
        Files.createDirectories(repository);
        PackageBuilder.build(source, repository.resolve("provider.pack"));

        int pid = 811;
        Map<String, Object> process = process(pid,
                List.of("result = package.install(\"/user/alice/app/repository/provider.pack\")"));
        JsonUtil.writeFile(Constants.SYSTEM_PROCESS_PATH + pid + ".proc", JsonUtil.toJson(process));
        ProcessRunner runner = new ProcessRunner(pid, process);
        try {
            runner.init();
            assertEquals(ProcessRunner.StepResult.COMPLETED, runner.step());

            Map<String, Object> persisted = JsonUtil.parseToMapStrict(
                    FileUtil.read(Constants.SYSTEM_PROCESS_PATH + pid + ".proc"));
            @SuppressWarnings("unchecked")
            Map<String, Object> program = (Map<String, Object>) persisted.get("Program");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) program.get("Data");
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) data.get("result");
            assertEquals("installed", result.get("status"));
            assertEquals("completed", result.get("postHookStatus"));
            assertEquals(1, PackageManager.getInstance().list("alice").size());
        } finally {
            ProcessRunner.terminateProcess(pid);
        }
    }

    private static Map<String, Object> process(int pid, List<String> lines) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", "package-provider-" + pid);
        process.put("Owner", "alice");
        process.put("EffectiveUser", "alice");
        process.put("PID", pid);
        process.put("Path", "/user/alice/app/install.fcl");
        process.put("Status", true);
        process.put("ProcessState", ProcessState.NEW.name());
        process.put("Parent", new LinkedHashMap<>());
        process.put("Child", new LinkedHashMap<>());

        Map<String, Object> code = new LinkedHashMap<>();
        code.put("Code", new ArrayList<>(lines));
        code.put("runningCodeLine", 0);
        code.put("BlockStack", new ArrayList<>());
        Map<String, Object> program = new LinkedHashMap<>();
        program.put("Data", new LinkedHashMap<>());
        program.put("Code", code);
        process.put("Program", program);
        return process;
    }
}
