package com.follarce.package_manager;

import com.follarce.fcl.FclBuiltins;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramLinker;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclSuspension;
import com.follarce.fcl.FclStepResult;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketEditorPackageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void marketSourceBuildsAValidImmutablePackageDatabase() throws Exception {
        Path output = temporaryDirectory.resolve("editor.db");
        PackageDescriptor descriptor = new PackageBuilder().build(
                Path.of("market/sources/editor"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        SqlitePackageReader reader = new SqlitePackageReader();

        assertEquals("cilexec/editor/1.0.4", descriptor.coordinate());
        assertEquals(com.follarce.domain.packageinfo.PackageKind.APPLICATION, descriptor.kind());
        assertEquals(List.of("run"), descriptor.entrypoints().stream()
                .map(value -> value.name()).toList());
        assertEquals(List.of("main"), descriptor.modules());
        assertEquals(List.of("open"), descriptor.exports().stream()
                .map(value -> value.name()).toList());
        String module = new String(reader.readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
        assertTrue(module.contains("func open(editorPath)"));
        assertTrue(module.contains("func run()"));
    }

    @Test
    void marketEditorLogicEditsAndSavesThroughFcl() throws Exception {
        Path output = temporaryDirectory.resolve("editor.db");
        new PackageBuilder().build(Path.of("market/sources/editor"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        String module = new String(new SqlitePackageReader().readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
        List<String> keys = List.of("a", "ENTER", "b", "CTRL_O", "CTRL_X");
        AtomicInteger keyIndex = new AtomicInteger();
        AtomicInteger executedSteps = new AtomicInteger();
        AtomicInteger firstInputStep = new AtomicInteger(-1);
        AtomicReference<String> saved = new AtomicReference<>();
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("term", "getSize", arguments -> java.util.Map.of(
                        "width", 80L, "height", 24L), "size")
                .register("term", "sanitize", arguments ->
                        com.follarce.terminal.TerminalSanitizer.sanitize(
                                String.valueOf(arguments.getFirst())))
                .register("file", "exists", arguments -> false)
                .register("file", "read", arguments -> "")
                .register("file", "write", arguments -> {
                    saved.set((String) arguments.get(1));
                    return true;
                })
                .register("io", "print", arguments -> null)
                .register("io", "readKey", arguments -> {
                    firstInputStep.compareAndSet(-1, executedSteps.get());
                    return keys.get(keyIndex.getAndIncrement());
                });
        FclProgram program = new FclCompiler().compile(module + "\nreturn run()\n");
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(functions);

        int steps = 0;
        while (!continuation.halted() && steps++ < 5_000) {
            executedSteps.incrementAndGet();
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }

        assertTrue(continuation.halted(), "editor did not finish within the step limit");
        assertFalse(continuation.failed());
        assertEquals("a\nb", saved.get());
        assertEquals(keys.size(), keyIndex.get());
        assertTrue(firstInputStep.get() > 0 && firstInputStep.get() <= 150,
                "editor first frame must reach input without excessive FCL steps: "
                        + firstInputStep.get());
    }

    @Test
    void marketFilesSourceBuildsAValidImmutablePackageDatabase() throws Exception {
        Path output = temporaryDirectory.resolve("files.db");
        PackageDescriptor descriptor = new PackageBuilder().build(
                Path.of("market/sources/files"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        String module = new String(new SqlitePackageReader().readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);

        assertEquals("cilexec/files/1.0.3", descriptor.coordinate());
        assertEquals(com.follarce.domain.packageinfo.PackageKind.APPLICATION, descriptor.kind());
        assertEquals(List.of("run"), descriptor.entrypoints().stream()
                .map(value -> value.name()).toList());
        assertEquals(List.of("open"), descriptor.exports().stream()
                .map(value -> value.name()).toList());
        assertTrue(module.contains("func fv_render(state)"));
        assertTrue(module.contains("term.getSize()"));
        assertTrue(module.contains("func run()"));
    }

    @Test
    void marketFilesBrowserOpensAndExitsThroughFcl() throws Exception {
        Path output = temporaryDirectory.resolve("files.db");
        new PackageBuilder().build(Path.of("market/sources/files"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        String module = new String(new SqlitePackageReader().readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
        AtomicInteger keyIndex = new AtomicInteger();
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("term", "getSize", arguments -> java.util.Map.of(
                        "width", 80L, "height", 24L), "size")
                .register("term", "sanitize", arguments ->
                        com.follarce.terminal.TerminalSanitizer.sanitize(
                                String.valueOf(arguments.getFirst())))
                .register("file", "listdir", arguments -> List.of(java.util.Map.of(
                        "name", "notes.txt", "type", "FILE", "updatedAt", "now")))
                .register("file", "readChunk", arguments -> "hello from CilFiles")
                .register("io", "print", arguments -> null)
                .register("io", "readKey", arguments -> List.of("q").get(keyIndex.getAndIncrement()));
        FclProgram program = new FclCompiler().compile(module + "\nreturn run()\n");
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(functions);

        int steps = 0;
        while (!continuation.halted() && steps++ < 5_000) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }

        assertTrue(continuation.halted(), "files browser did not finish within the step limit");
        assertFalse(continuation.failed());
        assertEquals(1, keyIndex.get());
    }

    @Test
    void marketClientBuildsWithEveryPublicOperation() throws Exception {
        Path output = temporaryDirectory.resolve("market.db");
        PackageDescriptor descriptor = new PackageBuilder().build(
                Path.of("market/sources/market"), output);

        assertEquals("cilexec/market/1.0.3", descriptor.coordinate());
        assertEquals(com.follarce.domain.packageinfo.PackageKind.APPLICATION, descriptor.kind());
        assertEquals(List.of("run"), descriptor.entrypoints().stream()
                .map(value -> value.name()).toList());
        assertEquals(List.of("download", "help", "info", "install", "list", "search", "uninstall",
                        "update", "upgrade"),
                descriptor.exports().stream().map(value -> value.name()).sorted().toList());
        assertTrue(descriptor.capabilities().containsAll(List.of(
                "network.http", "vfs.read", "vfs.write", "terminal.raw_input",
                "package.manage")));
    }

    @Test
    void marketClientSearchesTheCachedCompleteIndexLocally() throws Exception {
        Path output = temporaryDirectory.resolve("market.db");
        new PackageBuilder().build(Path.of("market/sources/market"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        String module = new String(new SqlitePackageReader().readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
        String index = """
                {"apiVersion":"cilexec.market/v1","packages":[
                  {"namespace":"cilexec","name":"editor","version":"1.0.4",
                   "coordinate":"cilexec/editor/1.0.4","sha256":"%s",
                   "download":"/market/v1/%s","bytes":123,
                   "summary":"自适应终端文本编辑器","tags":["editor","编辑器"]},
                  {"namespace":"cilexec","name":"files","version":"1.0.3",
                   "coordinate":"cilexec/files/1.0.3","sha256":"%s",
                   "download":"/market/v1/%s","bytes":123,
                   "summary":"文件浏览器","tags":["files","文件"]}
                ]}
                """.formatted("a".repeat(64), "a".repeat(64),
                "b".repeat(64), "b".repeat(64));
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("env", "get", arguments -> "https://market.test")
                .register("file", "exists", arguments -> true)
                .register("file", "read", arguments -> index)
                .register("file", "write", arguments -> true)
                .register("file", "createDir", arguments -> true);
        FclProgram program = new FclCompiler().compile(module + """

                matches = search("editor 编辑器")
                detail = info("%s")
                """.formatted("a".repeat(64)));
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(functions);

        int steps = 0;
        while (!continuation.halted() && steps++ < 2_000) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }

        assertTrue(continuation.halted());
        assertTrue(continuation.scope().get("matches") instanceof List<?> matches
                && matches.size() == 1);
        assertTrue(continuation.scope().get("detail") instanceof java.util.Map<?, ?> detail
                && "editor".equals(detail.get("name")));
    }

    @Test
    void marketClientListsInstalledNamesAndDistributionHashes() throws Exception {
        Path output = temporaryDirectory.resolve("market.db");
        new PackageBuilder().build(Path.of("market/sources/market"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        String module = new String(new SqlitePackageReader().readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
        String hash = "c".repeat(64);
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("env", "get", arguments -> "https://market.test")
                .register("file", "exists", arguments -> true)
                .register("file", "read", arguments -> "[]")
                .register("file", "write", arguments -> true)
                .register("file", "createDir", arguments -> true)
                .register("package", "list", arguments -> List.of(
                        java.util.Map.of("name", "editor", "sha256", hash)));
        FclProgram program = new FclCompiler().compile(module + "\ninstalled = list()\n");
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(functions);

        int steps = 0;
        while (!continuation.halted() && steps++ < 2_000) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }

        assertTrue(continuation.scope().get("installed") instanceof List<?> values
                && values.size() == 1
                && values.getFirst() instanceof java.util.Map<?, ?> item
                && "editor".equals(item.get("name"))
                && hash.equals(item.get("sha256")));
    }

    @Test
    void marketRunShowsVersionAndHelpWithoutConfiguringAMirror() throws Exception {
        String module = marketModule();
        java.util.ArrayList<String> output = new java.util.ArrayList<>();
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("io", "println", arguments -> {
                    output.add(String.valueOf(arguments.getFirst()));
                    return true;
                });
        FclContinuation continuation = run(functions,
                new FclCompiler().compile(module + "\nstarted = run()\n"));

        assertTrue(continuation.halted());
        assertTrue(output.stream().anyMatch(line -> line.contains("CilExec Market 1.0.3")));
        assertTrue(output.stream().anyMatch(line -> line.contains("mkt.search")));
    }

    @Test
    void firstNonRunOperationConfiguresExactlyOneMirror() throws Exception {
        String module = marketModule();
        AtomicReference<String> mirror = new AtomicReference<>();
        AtomicReference<String> prompt = new AtomicReference<>();
        String emptyIndex = "{\"apiVersion\":\"cilexec.market/v1\",\"packages\":[]}";
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("env", "get", arguments -> mirror.get())
                .register("env", "set", arguments -> {
                    mirror.set(String.valueOf(arguments.get(1)));
                    return true;
                })
                .register("io", "println", arguments -> true)
                .register("io", "input", arguments -> {
                    prompt.set(String.valueOf(arguments.getFirst()));
                    return "http://host.docker.internal:8787/";
                })
                .register("file", "exists", arguments -> true)
                .register("file", "read", arguments -> emptyIndex)
                .register("file", "write", arguments -> true)
                .register("file", "createDir", arguments -> true);
        FclContinuation continuation = run(functions,
                new FclCompiler().compile(module + "\nresults = search(\"\")\n"));

        assertTrue(continuation.halted());
        assertEquals("http://host.docker.internal:8787", mirror.get());
        assertEquals("Please enter the mirror source address> ", prompt.get());
        assertTrue(continuation.scope().get("results") instanceof List<?> results
                && results.isEmpty());
    }

    @Test
    void importedSearchResumesAfterInteractiveMirrorConfiguration() throws Exception {
        String module = marketModule();
        String index = """
                {"apiVersion":"cilexec.market/v1","packages":[
                  {"namespace":"cilexec","name":"editor","version":"1.0.4",
                   "coordinate":"cilexec/editor/1.0.4","sha256":"%s",
                   "download":"/market/v1/%s","bytes":123}
                ]}
                """.formatted("a".repeat(64), "a".repeat(64));
        AtomicReference<String> mirror = new AtomicReference<>();
        AtomicInteger inputRequests = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean inputDelivered =
                new java.util.concurrent.atomic.AtomicBoolean();
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("env", "get", arguments -> mirror.get())
                .register("env", "set", arguments -> {
                    mirror.set(String.valueOf(arguments.get(1)));
                    return true;
                })
                .register("io", "println", arguments -> true)
                .registerContextual("io", "input", (arguments, invocation) -> {
                    if (!inputDelivered.get()) {
                        inputRequests.incrementAndGet();
                        invocation.continuation().waitFor("input", java.util.Map.of());
                        throw FclSuspension.suspend();
                    }
                    return "http://host.docker.internal:8787";
                })
                .register("file", "exists", arguments -> true)
                .register("file", "read", arguments -> index)
                .register("file", "write", arguments -> true)
                .register("file", "createDir", arguments -> true);
        FclProgram base = new FclCompiler().compile("matches = mkt.search(\"editor\")\n");
        FclProgram linked = new FclProgramLinker().link(base, List.of(
                new FclProgramLinker.Module("market-package", "main", module, List.of(
                        new FclProgramLinker.Export("search", List.of("mkt.search"))))));
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(functions);

        int steps = 0;
        while (!continuation.halted() && steps++ < 4_000) {
            FclStepResult step = runtime.executeOne(linked, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
            if (step.status() == FclStepResult.Status.WAITING) {
                inputDelivered.set(true);
                continuation.clearWait();
            }
        }

        assertTrue(continuation.halted(), "interactive imported search did not finish");
        assertEquals(1, inputRequests.get());
        assertEquals("http://host.docker.internal:8787", mirror.get());
        assertTrue(continuation.scope().get("matches") instanceof List<?> matches
                && matches.size() == 1);
    }

    @Test
    void marketInstallRejectsAFileWhoseDistributionHashDoesNotMatchTheIndex() throws Exception {
        String module = marketModule();
        String expected = "a".repeat(64);
        String actual = "b".repeat(64);
        String index = """
                {"apiVersion":"cilexec.market/v1","packages":[
                  {"namespace":"demo","name":"tool","version":"1.0.0",
                   "coordinate":"demo/tool/1.0.0","sha256":"%s",
                   "download":"/market/v1/%s","bytes":123}
                ]}
                """.formatted(expected, expected);
        AtomicInteger removals = new AtomicInteger();
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("env", "get", arguments -> "https://market.test")
                .register("file", "exists", arguments -> true)
                .register("file", "read", arguments -> index)
                .register("file", "write", arguments -> true)
                .register("file", "createDir", arguments -> true)
                .register("file", "removeFile", arguments -> true)
                .register("network", "download", arguments -> java.util.Map.of(
                        "path", arguments.get(1), "bytes", 123L))
                .register("package", "install", arguments -> java.util.Map.of(
                        "sha256", actual,
                        "coordinate", "demo/tool/1.0.0",
                        "environmentId", UUID.randomUUID().toString(),
                        "binding", "tool"))
                .register("package", "remove", arguments -> {
                    removals.incrementAndGet();
                    return true;
                });
        FclContinuation continuation = run(functions,
                new FclCompiler().compile(module + "\nresult = install(\"" + expected + "\")\n"));

        assertTrue(continuation.halted());
        assertEquals(1, removals.get());
        assertTrue(continuation.scope().get("result") instanceof java.util.Map<?, ?> result
                && Boolean.FALSE.equals(result.get("ok")));
    }

    private String marketModule() throws Exception {
        Path output = temporaryDirectory.resolve("market-" + UUID.randomUUID() + ".db");
        new PackageBuilder().build(Path.of("market/sources/market"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        return new String(new SqlitePackageReader().readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
    }

    private static FclContinuation run(FclFunctionRegistry functions, FclProgram program) {
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(functions);
        int steps = 0;
        while (!continuation.halted() && steps++ < 4_000) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }
        return continuation;
    }
}
