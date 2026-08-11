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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketEditorPackageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void mouseAndPasteEventsNeverCrashTheEditorDispatch() throws Exception {
        Path output = temporaryDirectory.resolve("editor.db");
        new PackageBuilder().build(Path.of("dist/editor"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        String module = new String(new SqlitePackageReader().readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
        java.util.List<Map<String, Object>> events = java.util.List.of(
                Map.of("kind", "mouse", "button", "WHEEL", "action", "SCROLL", "scroll", 1L,
                        "x", 5L, "y", 3L),
                Map.of("kind", "mouse", "button", "LEFT", "action", "PRESS",
                        "x", 5L, "y", 3L),
                Map.of("kind", "paste", "text", "inserted"),
                Map.of("kind", "key", "key", "CTRL_X", "text", ""),
                Map.of("kind", "key", "key", "y", "text", "y"));
        AtomicInteger eventIndex = new AtomicInteger();
        AtomicReference<String> saved = new AtomicReference<>();
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("term", "getSize", arguments -> java.util.Map.of(
                        "width", 80L, "height", 24L), "size")
                .register("term", "sanitize", arguments -> String.valueOf(arguments.getFirst()))
                .register("file", "exists", arguments -> false)
                .register("file", "read", arguments -> "")
                .register("file", "write", arguments -> {
                    saved.set((String) arguments.get(1));
                    return true;
                })
                .register("env", "get", arguments -> "/")
                .register("io", "print", arguments -> null)
                .register("io", "readKey", arguments ->
                        events.get(eventIndex.getAndIncrement()));
        FclProgram program = new FclCompiler().compile(module + "\nreturn run()\n");
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(functions);

        int steps = 0;
        while (!continuation.halted() && steps++ < 5_000) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }

        assertTrue(continuation.halted(), "mouse and paste events must not fail the editor");
        assertFalse(continuation.failed());
        assertEquals(events.size(), eventIndex.get());
        assertTrue(saved.get().contains("inserted"),
                "the bracketed-paste text must be inserted before exit");
    }

    @Test
    void marketSourceBuildsAValidImmutablePackageDatabase() throws Exception {
        Path output = temporaryDirectory.resolve("editor.db");
        PackageDescriptor descriptor = new PackageBuilder().build(
                Path.of("dist/editor"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        SqlitePackageReader reader = new SqlitePackageReader();

        assertEquals("cilexec/editor/1.1.1", descriptor.coordinate());
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
        assertTrue(module.contains("array.removeAt(lines, row)"));
        assertFalse(module.contains("while index < #lines"));
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
                .register("env", "get", arguments -> "/")
                .register("io", "print", arguments -> null)
                .register("io", "readKey", arguments -> {
                    firstInputStep.compareAndSet(-1, executedSteps.get());
                    String key = keys.get(keyIndex.getAndIncrement());
                    String text = key.length() == 1 ? key : "";
                    return java.util.Map.of("kind", "key", "key", key,
                            "shift", false, "ctrl", false, "alt", false, "text", text);
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
    void deletingALineFromALargeScrolledBufferDoesNotWalkTheWholeFile() throws Exception {
        Path output = temporaryDirectory.resolve("editor.db");
        new PackageBuilder().build(Path.of("dist/editor"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        String module = new String(new SqlitePackageReader().readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
        String source = java.util.stream.IntStream.range(0, 5_000)
                .mapToObj(index -> "line" + index).collect(java.util.stream.Collectors.joining("\n"));
        List<String> keys = List.of("PAGE_DOWN", "BACKSPACE", "CTRL_O", "CTRL_X");
        AtomicInteger keyIndex = new AtomicInteger();
        AtomicReference<String> saved = new AtomicReference<>();
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("term", "getSize", arguments -> java.util.Map.of(
                        "width", 80L, "height", 24L), "size")
                .register("term", "sanitize", arguments -> String.valueOf(arguments.getFirst()))
                .register("file", "exists", arguments -> true)
                .register("file", "read", arguments -> source)
                .register("file", "write", arguments -> {
                    saved.set((String) arguments.get(1));
                    return true;
                })
                .register("env", "get", arguments -> "/")
                .register("io", "print", arguments -> null)
                .register("io", "readKey", arguments -> {
                    String key = keys.get(keyIndex.getAndIncrement());
                    return java.util.Map.of("kind", "key", "key", key,
                            "shift", false, "ctrl", false, "alt", false, "text", "");
                });
        FclProgram program = new FclCompiler().compile(
                module + "\nreturn open(\"large.txt\")\n");
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(functions);

        int steps = 0;
        while (!continuation.halted() && steps++ < 2_000) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }

        assertTrue(continuation.halted(),
                "large-buffer line deletion must not execute one FCL iteration per file line");
        assertTrue(steps < 2_000, "large-buffer edit used too many FCL steps: " + steps);
        assertEquals(keys.size(), keyIndex.get());
        assertTrue(saved.get().contains("line20line21\nline22"));
        assertEquals(4_999, saved.get().split("\n", -1).length);
    }

    @Test
    void positionsDocumentAndSearchCursorsByTerminalColumns() throws Exception {
        Path output = temporaryDirectory.resolve("editor.db");
        new PackageBuilder().build(Path.of("dist/editor"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        String module = new String(new SqlitePackageReader().readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
        List<String> keys = List.of("RIGHT", "CTRL_W", "中", "ESCAPE", "CTRL_X");
        AtomicInteger keyIndex = new AtomicInteger();
        List<String> frames = new java.util.ArrayList<>();
        FclFunctionRegistry functions = FclBuiltins.pureRegistry()
                .register("term", "getSize", arguments -> java.util.Map.of(
                        "width", 80L, "height", 24L), "size")
                .register("term", "sanitize", arguments -> String.valueOf(arguments.getFirst()))
                .register("file", "exists", arguments -> true)
                .register("file", "read", arguments -> "中a")
                .register("file", "write", arguments -> true)
                .register("env", "get", arguments -> "/")
                .register("io", "print", arguments -> {
                    frames.add((String) arguments.getFirst());
                    return null;
                })
                .register("io", "readKey", arguments -> {
                    String key = keys.get(keyIndex.getAndIncrement());
                    String text = key.length() == 1 ? key : "";
                    return java.util.Map.of("kind", "key", "key", key,
                            "shift", false, "ctrl", false, "alt", false, "text", text);
                });
        FclProgram program = new FclCompiler().compile(module + "\nreturn open(\"wide.txt\")\n");
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(functions);

        int steps = 0;
        while (!continuation.halted() && steps++ < 5_000) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }

        assertTrue(continuation.halted());
        assertTrue(frames.get(2).endsWith("\u001b[2;3H\u001b[?25h"),
                "cursor after one CJK character must move two terminal columns");
        assertTrue(frames.get(3).endsWith("\u001b[24;9H\u001b[?25h"),
                "empty search prompt cursor must be on the footer");
        assertTrue(frames.get(4).endsWith("\u001b[24;11H\u001b[?25h"),
                "CJK search text must advance the footer cursor by two columns");
    }

}
