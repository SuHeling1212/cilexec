package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FclRuntimeTest {
    private final FclCompiler compiler = new FclCompiler();
    private final FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());

    @Test
    void evaluatesCompositeValuesAndAdvancesOneStatementAtATime() {
        FclProgram program = compiler.compile("""
                items = [1, 2, 3]
                items[1] = items[0] + 4
                record = {answer: items[1], ok: true and !false}
                score = math.pow(record["answer"], 2) + #items
                """);
        FclContinuation continuation = new FclContinuation();

        FclStepResult first = runtime.executeOne(program, continuation);
        assertEquals(FclStepResult.Status.ADVANCED, first.status());
        assertEquals(List.of(1L, 2L, 3L), continuation.scope().get("items"));
        assertFalse(continuation.scope().contains("record"));

        runToCompletion(program, continuation);
        assertEquals(List.of(1L, 5L, 3L), continuation.scope().get("items"));
        assertEquals(Map.of("answer", 5L, "ok", true),
                continuation.scope().get("record"));
        assertEquals(28.0d, continuation.scope().get("score"));
    }

    @Test
    void persistsLoopControlAndRecursiveCallsAcrossSteps() {
        FclProgram program = compiler.compile("""
                func fact(n) {
                    if n <= 1 {
                        return 1
                    }
                    return n * fact(n - 1)
                }
                i = 0
                sum = 0
                while i < 6 {
                    i = i + 1
                    if i == 3 { continue }
                    if i == 5 { break }
                    sum = sum + i
                }
                answer = fact(5)
                """);
        FclContinuation continuation = new FclContinuation();
        int maximumCallDepth = 0;
        int steps = 0;
        while (!continuation.halted() && steps++ < 300) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
            maximumCallDepth = Math.max(maximumCallDepth, continuation.callStack().size());
        }

        assertTrue(continuation.halted());
        assertFalse(continuation.failed());
        assertTrue(maximumCallDepth >= 5);
        assertEquals(7L, continuation.scope().get("sum"));
        assertEquals(120L, continuation.scope().get("answer"));
        assertTrue(continuation.callStack().isEmpty());
        assertTrue(continuation.loopState().isEmpty());
        assertTrue(continuation.branchState().isEmpty());
    }

    @Test
    void turnsInvalidUserCallArityIntoADurableFailure() {
        FclProgram program = compiler.compile("""
                func identity(value) { return value }
                identity()
                """);
        FclContinuation continuation = new FclContinuation();

        assertEquals(FclStepResult.Status.ADVANCED,
                runtime.executeOne(program, continuation).status());
        FclStepResult failed = runtime.executeOne(program, continuation);

        assertEquals(FclStepResult.Status.FAILED, failed.status());
        assertTrue(continuation.halted());
        assertTrue(continuation.failed());
        assertTrue(String.valueOf(failed.value()).contains("expects 1 arguments"));
    }

    @Test
    void doesNotAccumulateBranchFramesAcrossInfiniteLoopIterations() {
        FclProgram program = compiler.compile("""
                while true {
                    if true { value = 1 }
                }
                """);
        FclContinuation continuation = new FclContinuation();

        for (int step = 0; step < 2_000; step++) {
            FclStepResult result = runtime.executeOne(program, continuation);
            assertFalse(result.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(result.value()));
            assertTrue(continuation.branchState().size() <= 1,
                    "loop iterations must reclaim completed branch state");
        }
    }

    private void runToCompletion(FclProgram program, FclContinuation continuation) {
        int steps = 0;
        while (!continuation.halted() && steps++ < 200) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }
        assertTrue(continuation.halted(), "program did not complete within the step limit");
    }
}
