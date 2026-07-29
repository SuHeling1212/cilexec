package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FclCodecAndRegistryTest {
    @Test
    void roundTripsProgramAndSuspendedContinuationWithoutChangingTypes() {
        String source = """
                func identity(value) { return value }
                data = {number: 7, decimal: 1.5, nested: [true, null]}
                answer = identity(data["number"])
                """;
        FclProgramCodec programCodec = new FclProgramCodec();
        FclProgram program = new FclCompiler().compile(source);
        FclProgram restoredProgram = programCodec.fromJson(programCodec.toJson(program));
        assertEquals(program.sourceHash(), restoredProgram.sourceHash());
        assertEquals(program.instructions(), restoredProgram.instructions());

        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        FclContinuation continuation = new FclContinuation();
        FclStepResult step;
        do {
            step = runtime.executeOne(program, continuation);
        } while (step.status() != FclStepResult.Status.CALL_ENTERED);
        assertEquals(1, continuation.callStack().size());

        FclContinuationCodec codec = new FclContinuationCodec();
        String json = codec.toJson(continuation);
        FclContinuation restored = codec.fromJson(json);
        assertEquals(json, codec.toJson(restored));
        assertEquals(Long.class, ((Map<?, ?>) restored.callStack().getFirst()
                .callerScope().get("data")).get("number").getClass());

        runToCompletion(runtime, restoredProgram, restored);
        assertEquals(7L, restored.scope().get("answer"));

        Map<String, Object> tampered = programCodec.encode(program);
        tampered.put("sourceHash", "00");
        assertThrows(IllegalArgumentException.class, () -> programCodec.decode(tampered));
    }

    @Test
    void persistsImportAndIncludeAsWaitableDirectives() {
        FclProgram program = new FclCompiler().compile("""
                import "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" as "numbers"
                include "lib/util.fcl"
                value = 1
                """);
        assertTrue(program.instructions().get(0) instanceof FclInstruction.Import);
        assertTrue(program.instructions().get(1) instanceof FclInstruction.Include);

        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        FclContinuation continuation = new FclContinuation();
        FclStepResult imported = runtime.executeOne(program, continuation);
        assertEquals(FclStepResult.Status.DIRECTIVE, imported.status());
        assertEquals(FclContinuation.WaitKind.IMPORT, continuation.waitState().kind());
        int waitingPointer = continuation.programCounter();
        assertEquals(FclStepResult.Status.WAITING,
                runtime.executeOne(program, continuation).status());
        assertEquals(waitingPointer, continuation.programCounter());

        continuation.clearWait();
        assertEquals(FclStepResult.Status.DIRECTIVE,
                runtime.executeOne(program, continuation).status());
        assertEquals(FclContinuation.WaitKind.INCLUDE, continuation.waitState().kind());
    }

    @Test
    void requiresQuotedImportAliases() {
        assertThrows(FclCompileException.class,
                () -> new FclCompiler().compile("import \""
                        + "a".repeat(64) + "\" as numbers"));
        assertThrows(FclCompileException.class,
                () -> new FclCompiler().compile("import \"editor\""));
        assertDoesNotThrow(() -> new FclCompiler().compile(
                "value = " + "2".repeat(64) + ".open(\"a.txt\")"));
        assertThrows(FclCompileException.class, () -> new FclCompiler().compile(
                "import \"" + "a".repeat(64) + "\" as \"bad alias\""));
        assertThrows(FclCompileException.class, () -> new FclCompiler().compile(
                "import \"" + "a".repeat(64) + "\" as \"\""));
    }

    @Test
    void reservesQualifiedAndLiteralNamesFromUserAssignments() {
        FclCompiler compiler = new FclCompiler();
        assertThrows(FclCompileException.class,
                () -> compiler.compile("effect.result = 1"));
        assertThrows(FclCompileException.class,
                () -> compiler.compile("true = 1"));
        assertThrows(FclCompileException.class,
                () -> compiler.compile("func null() { return 1 }"));
        assertThrows(FclCompileException.class,
                () -> compiler.compile("func f(io.value) { return io.value }"));
    }

    @Test
    void usesInstanceRegistriesAndRejectsAmbiguousBareNames() {
        FclFunctionRegistry first = FclBuiltins.pureRegistry();
        FclFunctionRegistry second = FclBuiltins.pureRegistry();
        assertNotSame(first, second);
        first.register("alpha", "onlyHere", arguments -> 1L);
        assertEquals(1L, first.invoke("onlyHere", List.of()));
        assertThrows(FclRuntimeException.class,
                () -> second.invoke("onlyHere", List.of()));

        FclFunctionRegistry ambiguous = new FclFunctionRegistry()
                .register("alpha", "echo", arguments -> "a")
                .register("beta", "echo", arguments -> "b");
        assertThrows(FclRuntimeException.class,
                () -> ambiguous.invoke("echo", List.of()));
        assertEquals("a", ambiguous.invoke("alpha.echo", List.of()));

        assertEquals(3.0d, second.invoke("sqrt", List.of(9L)));
        assertEquals("/a/c", second.invoke("path.normalize", List.of("/a/b/../c")));
        assertEquals("bc", second.invoke("text.slice", List.of("abcd", 1L, 3L)));
        assertEquals(List.of("a", "", "b"),
                second.invoke("text.split", List.of("a\n\nb", "\n")));
        assertEquals("a/b", second.invoke("text.join", List.of(List.of("a", "b"), "/")));
        assertEquals(2L, second.invoke("text.indexOf", List.of("abcd", "c")));
        assertEquals("xxx", second.invoke("text.repeat", List.of("x", 3L)));
        assertEquals("\u001b[2;4H", second.invoke("term.cursorTo", List.of(2L, 4L)));
    }

    private static void runToCompletion(FclRuntime runtime, FclProgram program,
                                        FclContinuation continuation) {
        int steps = 0;
        while (!continuation.halted() && steps++ < 100) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }
        assertTrue(continuation.halted());
    }
}
