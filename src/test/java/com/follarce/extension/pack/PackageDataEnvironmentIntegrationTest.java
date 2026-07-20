package com.follarce.extension.pack;

import com.follarce.bootstrap.BuiltinProviderIndex;
import com.follarce.bootstrap.init.FileInit;
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

class PackageDataEnvironmentIntegrationTest {
    @TempDir Path root;

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void eachPackageSeesItsOwnDataEnvironmentAndCanWriteAnotherPackageData() throws Exception {
        FileInit.init(root.toFile());
        BuiltinProviderIndex.install();
        UserUtil.setCurrentUser("local");
        UserUtil.createUser("alice", "pw", false);

        Path repository = root.resolve("user/alice/app/repository");
        Files.createDirectories(repository);
        buildPackage(repository, "alpha", "alpha.pack",
                "func dataPath() { return path.getEnvVar(\"PACKAGE_DATA\") }\n"
                        + "func writeInto(target) { file.createFile(target, \"from-alpha.txt\"); "
                        + "file.write(target + \"/from-alpha.txt\", \"shared\"); return true }");
        buildPackage(repository, "beta", "beta.pack",
                "func dataPath() { return path.getEnvVar(\"PACKAGE_DATA\") }\n"
                        + "func readOwn() { dataDir = path.getEnvVar(\"PACKAGE_DATA\"); "
                        + "return file.read(dataDir + \"/from-alpha.txt\") }");

        PackageManager manager = PackageManager.getInstance();
        manager.initialize();
        manager.install("alice", "/user/alice/app/repository/alpha.pack", "alpha", null,
                "install-alpha", 921, "generation-921");
        manager.install("alice", "/user/alice/app/repository/beta.pack", "beta", null,
                "install-beta", 921, "generation-921");

        int pid = 921;
        Map<String, Object> process = process(pid, List.of(
                "import alpha.* as alpha",
                "import beta.* as beta",
                "alphaData = alpha.dataPath()",
                "betaData = beta.dataPath()",
                "written = alpha.writeInto(betaData)",
                "shared = beta.readOwn()"
        ));
        JsonUtil.writeFile(Constants.SYSTEM_PROCESS_PATH + pid + ".proc", JsonUtil.toJson(process));
        ProcessRunner runner = new ProcessRunner(pid, process);
        try {
            runner.init();
            ProcessRunner.StepResult result = ProcessRunner.StepResult.COMPLETED;
            for (int i = 0; i < 120 && result != ProcessRunner.StepResult.TERMINATED; i++) {
                result = runner.step();
            }
            assertEquals(ProcessRunner.StepResult.TERMINATED, result);

            Map<String, Object> persisted = JsonUtil.parseToMapStrict(
                    FileUtil.read(Constants.SYSTEM_PROCESS_PATH + pid + ".proc"));
            @SuppressWarnings("unchecked")
            Map<String, Object> program = (Map<String, Object>) persisted.get("Program");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) program.get("Data");

            assertEquals("/user/alice/app/data/package/packages/tests.env/alpha/",
                    data.get("alphaData"));
            assertEquals("/user/alice/app/data/package/packages/tests.env/beta/",
                    data.get("betaData"));
            assertEquals(true, data.get("written"));
            assertEquals("shared", data.get("shared"));
        } finally {
            ProcessRunner.terminateProcess(pid);
        }
    }

    private void buildPackage(Path repository, String name, String fileName, String source)
            throws Exception {
        Path packageSource = PackageTestFixtures.source(root, name + "-source",
                "tests.env", name, "1.0.0", "dataPath", source, List.of(), Map.of());
        PackageBuilder.build(packageSource, repository.resolve(fileName));
    }

    private static Map<String, Object> process(int pid, List<String> lines) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", "package-data-environment");
        process.put("Owner", "alice");
        process.put("EffectiveUser", "alice");
        process.put("PID", pid);
        process.put("Path", "/user/alice/app/package-data-test.fcl");
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
