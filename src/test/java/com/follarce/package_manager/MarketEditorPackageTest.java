package com.follarce.package_manager;

import com.follarce.fcl.FclBuiltins;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
                Path.of("dist/editor"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        SqlitePackageReader reader = new SqlitePackageReader();

        assertEquals("cilexec/editor/1.0.8", descriptor.coordinate());
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
        new PackageBuilder().build(Path.of("dist/editor"), output);
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

}
