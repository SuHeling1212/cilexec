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
                Path.of("market/sources/editor"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        SqlitePackageReader reader = new SqlitePackageReader();

        assertEquals("cilexec/editor/1.0.0", descriptor.coordinate());
        assertEquals(List.of("main"), descriptor.modules());
        assertEquals(List.of("open"), descriptor.exports().stream()
                .map(value -> value.name()).toList());
        String module = new String(reader.readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
        assertTrue(module.contains("func open(editorPath)"));
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
        AtomicReference<String> saved = new AtomicReference<>();
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("file", "exists", arguments -> false)
                .register("file", "read", arguments -> "")
                .register("file", "write", arguments -> {
                    saved.set((String) arguments.get(1));
                    return true;
                })
                .register("io", "print", arguments -> null)
                .register("io", "readKey", arguments -> keys.get(keyIndex.getAndIncrement()));
        FclProgram program = new FclCompiler().compile(module + "\nreturn open(\"notes.txt\")\n");
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(functions);

        int steps = 0;
        while (!continuation.halted() && steps++ < 5_000) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }

        assertTrue(continuation.halted(), "editor did not finish within the step limit");
        assertFalse(continuation.failed());
        assertEquals("a\nb", saved.get());
        assertEquals(keys.size(), keyIndex.get());
    }
}
