package com.follarce.extension.pack;

import com.follarce.bootstrap.init.FileInit;
import com.follarce.extension.pack.PackageArchive;
import com.follarce.extension.pack.PackageBuilder;
import com.follarce.extension.pack.PackageManager;
import com.follarce.kernel.Constants;
import com.follarce.kernel.process.ProcessRunner;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageVersionAliasIntegrationTest {
    @TempDir Path root;

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void oneProcessUsesTwoPackageVersionsWithTheirOwnDependencyGraphs() throws Exception {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        UserUtil.createUser("alice", "pw", false);
        PackageManager manager = PackageManager.getInstance();
        manager.initialize();

        Path repository = root.resolve("user/alice/app/repository");
        Files.createDirectories(repository);
        PackageArchive dependencyV1 = buildDependency(repository, "1.0.0", 101);
        PackageArchive dependencyV2 = buildDependency(repository, "2.0.0", 202);
        PackageArchive rootV1 = buildRoot(repository, "1.0.0", dependencyV1);
        PackageArchive rootV2 = buildRoot(repository, "2.0.0", dependencyV2);
        assertNotEquals(rootV1.hash(), rootV2.hash());

        manager.install("alice", "/user/alice/app/repository/root-1.pack", "root-v1", null,
                "install-root-v1", 851, "generation-851");
        manager.install("alice", "/user/alice/app/repository/root-2.pack", "root-v2", null,
                "install-root-v2", 851, "generation-851");

        int pid = 851;
        Map<String, Object> process = process(pid, List.of(
                "import root-v1.* as versionOne",
                "import root-v2.* as versionTwo",
                "one = versionOne.rootValue()",
                "two = versionTwo.rootValue()"
        ));
        JsonUtil.writeFile(Constants.SYSTEM_PROCESS_PATH + pid + ".proc", JsonUtil.toJson(process));
        ProcessRunner runner = new ProcessRunner(pid, process);
        try {
            runner.init();
            ProcessRunner.StepResult result = ProcessRunner.StepResult.COMPLETED;
            for (int i = 0; i < 80 && result != ProcessRunner.StepResult.TERMINATED; i++) {
                result = runner.step();
            }
            assertEquals(ProcessRunner.StepResult.TERMINATED, result);

            Map<String, Object> persisted = JsonUtil.parseToMapStrict(
                    FileUtil.read(Constants.SYSTEM_PROCESS_PATH + pid + ".proc"));
            @SuppressWarnings("unchecked")
            Map<String, Object> program = (Map<String, Object>) persisted.get("Program");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) program.get("Data");
            assertEquals(101, ((Number) data.get("one")).intValue());
            assertEquals(202, ((Number) data.get("two")).intValue());

            @SuppressWarnings("unchecked")
            List<String> imports = (List<String>) program.get("imports");
            assertTrue(imports.stream().anyMatch(value -> value.contains("#fcl-as=versionOne;")));
            assertTrue(imports.stream().anyMatch(value -> value.contains("#fcl-as=versionTwo;")));
        } finally {
            ProcessRunner.terminateProcess(pid);
        }
    }

    private PackageArchive buildDependency(Path repository, String version, int value) throws Exception {
        String suffix = version.substring(0, 1);
        Path source = PackageTestFixtures.source(root, "dependency-" + suffix + "-source",
                "tests.alias", "dependency", version, "depValue",
                "func depValue() { return " + value + " }", List.of(), Map.of());
        Path pack = repository.resolve("dependency-" + suffix + ".pack");
        PackageBuilder.build(source, pack);
        return PackageArchive.read(pack);
    }

    private PackageArchive buildRoot(Path repository,
                                     String version,
                                     PackageArchive dependency) throws Exception {
        String suffix = version.substring(0, 1);
        Path source = PackageTestFixtures.source(root, "root-" + suffix + "-source",
                "tests.alias", "root", version, "rootValue",
                "import dep.*\nfunc rootValue() { value = depValue(); return value }",
                List.of(PackageTestFixtures.dependency("dep", dependency)), Map.of());
        Path pack = repository.resolve("root-" + suffix + ".pack");
        PackageBuilder.build(source, pack);
        return PackageArchive.read(pack);
    }

    private static Map<String, Object> process(int pid, List<String> lines) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", "package-alias-" + pid);
        process.put("Owner", "alice");
        process.put("EffectiveUser", "alice");
        process.put("PID", pid);
        process.put("Path", "/user/alice/app/alias-test.fcl");
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
