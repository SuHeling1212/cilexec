package com.follarce.process;

import com.follarce.Constants;
import com.follarce.init.FileInit;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportManagerTest {
    @TempDir Path root;

    @Test
    void relativePackageImportRecursivelyLoadsOnlyFclFilesInStableOrder() {
        FileInit.init(root.toFile());
        createPackageFixture();
        ImportManager manager = new ImportManager(
                () -> "local", Map::of,
                () -> Constants.SYSTEM_APP_PACKAGE_PATH + "main.fcl");
        List<String> code = new ArrayList<>();

        List<String> imported = manager.handleImport("import json.*", code);

        assertEquals(List.of(
                Constants.SYSTEM_APP_PACKAGE_PATH + "json/payload/a.fcl",
                Constants.SYSTEM_APP_PACKAGE_PATH + "json/payload/nested/middle.fcl",
                Constants.SYSTEM_APP_PACKAGE_PATH + "json/payload/z.fcl"
        ), imported);
        assertEquals(List.of("a = 1", "middle = 2", "z = 3"), code);
        assertFalse(imported.stream().anyMatch(path -> path.endsWith("manifest.json")));
        assertFalse(imported.stream().anyMatch(path -> path.contains("/hooks/")));
    }

    @Test
    void absolutePackageImportDoesNotDependOnCurrentScriptDirectory() {
        FileInit.init(root.toFile());
        createPackageFixture();
        ImportManager manager = new ImportManager(
                () -> "local", Map::of,
                () -> "/user/local/app/main.fcl");
        List<String> code = new ArrayList<>();

        List<String> imported = manager.handleImport(
                "import /system/app/package/json.*", code);

        assertEquals(3, imported.size());
        assertEquals(List.of("a = 1", "middle = 2", "z = 3"), code);
    }

    @Test
    void processRunnerUsesItsScriptDirectoryForRelativePackageImports() {
        FileInit.init(root.toFile());
        createPackageFixture();
        int pid = 801;
        Map<String, Object> process = process(pid, List.of("import json.*", "done = true"));
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
            List<String> imports = (List<String>) program.get("imports");
            assertEquals(3, imports.size());
            assertTrue(imports.contains(Constants.SYSTEM_APP_PACKAGE_PATH + "json/payload/a.fcl"));
        } finally {
            ProcessRunner.terminateProcess(pid);
        }
    }

    @Test
    void aliasedPackageImportNamespacesDefinitionsAndInternalCalls() {
        FileInit.init(root.toFile());
        createFunctionPackageFixture("tools", "helper");
        ImportManager manager = new ImportManager(
                () -> "local", Map::of,
                () -> Constants.SYSTEM_APP_PACKAGE_PATH + "main.fcl");
        List<String> code = new ArrayList<>();

        List<String> imported = manager.handleImport("import tools.* as toolsV1", code);
        imported.forEach(manager::addImportedFile);

        assertEquals(List.of(
                "func toolsV1.helper() { return 1 }",
                "func toolsV1.value() { return toolsV1.helper() }",
                "func toolsV1.literal() { return \"helper()\" } // helper()"
        ), code);
        assertTrue(manager.handleImport("import tools.* as toolsV1", code).isEmpty());
    }

    @Test
    void aliasCannotBeReboundOrUseAnInvalidIdentifier() {
        FileInit.init(root.toFile());
        createFunctionPackageFixture("first", "firstValue");
        createFunctionPackageFixture("second", "secondValue");
        ImportManager manager = new ImportManager(
                () -> "local", Map::of,
                () -> Constants.SYSTEM_APP_PACKAGE_PATH + "main.fcl");

        List<String> first = manager.handleImport("import first.* as shared", new ArrayList<>());
        first.forEach(manager::addImportedFile);

        IllegalArgumentException rebound = assertThrows(IllegalArgumentException.class,
                () -> manager.handleImport("import second.* as shared", new ArrayList<>()));
        assertTrue(rebound.getMessage().contains("already bound"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.handleImport("import first.* as bad-alias", new ArrayList<>()));
    }

    private void createPackageFixture() {
        FileUtil.createDirectory(Constants.SYSTEM_APP_PACKAGE_PATH, "json");
        String packagePath = Constants.SYSTEM_APP_PACKAGE_PATH + "json/";
        writeFile(packagePath, "manifest.json", "{\"name\":\"json\"}");
        FileUtil.createDirectory(packagePath, "payload");
        String payloadPath = packagePath + "payload/";
        writeFile(payloadPath, "z.fcl", "z = 3");
        writeFile(payloadPath, "a.fcl", "a = 1");
        FileUtil.createDirectory(payloadPath, "nested");
        writeFile(payloadPath + "nested/", "middle.fcl", "middle = 2");
        writeFile(payloadPath + "nested/", "resource.txt", "ignored");
        FileUtil.createDirectory(packagePath, "hooks");
        writeFile(packagePath + "hooks/", "pre-install.fcl", "hookRan = true");
    }

    private void createFunctionPackageFixture(String name, String helperName) {
        FileUtil.createDirectory(Constants.SYSTEM_APP_PACKAGE_PATH, name);
        String packagePath = Constants.SYSTEM_APP_PACKAGE_PATH + name + "/";
        FileUtil.createDirectory(packagePath, "payload");
        writeFile(packagePath + "payload/", "main.fcl",
                "func " + helperName + "() { return 1 }\n"
                        + "func value() { return " + helperName + "() }\n"
                        + "func literal() { return \"" + helperName + "()\" } // "
                        + helperName + "()");
    }

    private void writeFile(String directory, String name, String content) {
        FileUtil.createFile(directory, name);
        FileUtil.write(directory + name, content);
    }

    private Map<String, Object> process(int pid, List<String> lines) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", "package-import-" + pid);
        process.put("Owner", "local");
        process.put("PID", pid);
        process.put("Path", Constants.SYSTEM_APP_PACKAGE_PATH + "main.fcl");
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
