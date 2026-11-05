package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FclVolatileFunctionTest {
    @Test
    void editorFrameRendererRunsWithPureBuiltins() throws Exception {
        String source = Files.readString(Path.of("dist/editor/main.fcl")) + """

                frame = ed_render_frame({path:"/note", lines:["hello"], row:0, col:0,
                    top:0, left:0, width:30, height:8, dirty:false})
                """;
        FclProgram program = new FclCompiler().compile(source);
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        for (int i = 0; i < 100 && !continuation.halted(); i++) {
            runtime.executeOne(program, continuation);
        }
        assertTrue(continuation.halted());
        assertFalse(continuation.failed(), String.valueOf(continuation.result()));
        assertTrue(String.valueOf(continuation.scope().get("frame")).contains("hello"));
    }

    @Test
    void executesWithoutPersistingTheFunctionCallStackAndSurvivesFclbRoundTrip() {
        String source = """
                func add(a, b) { return a + b }
                volatile func calculate(value) {
                    temporary = add(value, 2)
                    return temporary * 3
                }
                answer = calculate(12)
                """;
        FclProgram program = new FclCompiler().compile(source);
        FclProgram restored = new FclProgramCodec().fromBytes(new FclProgramCodec().toBytes(program),
                FclProgramCodec.FORMAT_VERSION, source);
        assertTrue(restored.function("calculate").volatileFunction());
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        for (int i = 0; i < 20 && !continuation.halted(); i++) {
            runtime.executeOne(restored, continuation);
        }
        assertEquals(42L, continuation.scope().get("answer"));
        assertTrue(continuation.callStack().isEmpty());
    }

    @Test
    void persistentDeclarationSurvivesTheExistingFclbFormat() {
        String source = "persistent func calculate() { return 42 }\nresult = calculate()\n";
        FclProgram restored = new FclProgramCodec().fromBytes(
                new FclProgramCodec().toBytes(new FclCompiler().compile(source)),
                FclProgramCodec.FORMAT_VERSION, source);
        assertTrue(restored.function("calculate").persistentFunction());
    }

    @Test
    void rejectsStatefulBuiltinAndRemovedProcessRun() {
        assertEquals(FclStepResult.Status.FAILED, run("""
                volatile func bad() { return file.open("/note") }
                result = bad()
                """));
        assertEquals(FclStepResult.Status.FAILED, run("result = process.run(\"/worker\", [])"));
    }

    private static FclStepResult.Status run(String source) {
        FclProgram program = new FclCompiler().compile(source);
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        for (int i = 0; i < 20 && !continuation.halted(); i++) {
            runtime.executeOne(program, continuation);
        }
        return continuation.failed() ? FclStepResult.Status.FAILED : FclStepResult.Status.COMPLETED;
    }
}
