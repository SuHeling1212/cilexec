package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the complete FCL syntax surface, including nested control and failure paths. */
class FclLanguageExhaustiveTest {
    private final FclCompiler compiler = new FclCompiler();

    @Test
    void executesOperatorsCollectionsCommentsAndNestedControl() {
        FclContinuation state = run("""
                // line comment
                base = 2 + 3 * 4
                grouped = (2 + 3) * 4
                quotient = 9 / 2
                remainder = 9 % 4
                comparison = base == 14 and grouped >= 20 and 2 < 3 and 3 <= 3
                shortAnd = false and missingValue
                shortOr = true or missingValue
                text = "你" + "好" + 1
                values = [1, {name: "before"}, [3]]
                values[1]["name"] = "after"
                object = {"answer": values[0] + values[2][0], truth: !false}
                original = [1, [2]]
                copy = original
                copy[1][0] = 9
                outer = 0
                total = 0
                while outer < 4 {
                    outer = outer + 1
                    inner = 0
                    while inner < 5 {
                        inner = inner + 1
                        if inner == 2 { continue }
                        if outer == 3 and inner == 4 { break }
                        total = total + 1
                    }
                }
                branch = "none"
                if total == 14 { branch = "ok" } else { branch = "wrong" }
                """);

        assertEquals(14L, state.scope().get("base"));
        assertEquals(20L, state.scope().get("grouped"));
        assertEquals(4.5d, state.scope().get("quotient"));
        assertEquals(1L, state.scope().get("remainder"));
        assertEquals(true, state.scope().get("comparison"));
        assertEquals(false, state.scope().get("shortAnd"));
        assertEquals(true, state.scope().get("shortOr"));
        assertEquals("你好1", state.scope().get("text"));
        assertEquals(Map.of("answer", 4L, "truth", true), state.scope().get("object"));
        assertEquals(List.of(1L, List.of(2L)), state.scope().get("original"));
        assertEquals(List.of(1L, List.of(9L)), state.scope().get("copy"));
        assertEquals(14L, state.scope().get("total"));
        assertEquals("ok", state.scope().get("branch"));
    }

    @Test
    void executesNestedAndRecursiveFunctionsWithoutLeakingLocalScope() {
        FclContinuation state = run("""
                global = 10
                func fibonacci(n) {
                    if n <= 1 { return n }
                    return fibonacci(n - 1) + fibonacci(n - 2)
                }
                func outer(value, globalValue) {
                    local = value + globalValue
                    funcNameText = "local"
                    return local
                }
                fib = fibonacci(10)
                answer = outer(5, global)
                """);
        assertEquals(55L, state.scope().get("fib"));
        assertEquals(15L, state.scope().get("answer"));
        assertFalse(state.scope().contains("local"));
        assertTrue(state.callStack().isEmpty());
    }

    @Test
    void compilesPackageHashDirectivesAndFullHashFunctionNames() {
        String hash = "0123456789abcdef".repeat(4);
        String other = "fedcba9876543210".repeat(4);
        FclProgram program = compiler.compile("""
                import "%s"
                import "%s" as "e"
                import "%s.*"
                import "%s" as "exact"
                include "library.fcl"
                result = %s.open("note.txt")
                quoted = "%s".open("other.txt")
                """.formatted(hash, other, hash, hash, hash, hash));

        assertInstanceOf(FclInstruction.Import.class, program.instructions().get(0));
        FclInstruction.Import plain = (FclInstruction.Import) program.instructions().get(0);
        assertEquals(hash, plain.target());
        assertEquals(null, plain.alias());
        assertFalse(plain.wildcard());
        FclInstruction.Import aliased = (FclInstruction.Import) program.instructions().get(1);
        assertEquals("e", aliased.alias());
        FclInstruction.Import wildcard = (FclInstruction.Import) program.instructions().get(2);
        assertTrue(wildcard.wildcard());
        assertInstanceOf(FclInstruction.Include.class, program.instructions().get(4));
        assertInstanceOf(FclInstruction.Assignment.class, program.instructions().get(5));
    }

    @Test
    void rejectsHumanReadableImportTargets() {
        for (String source : List.of(
                "import \"editor\"",
                "import \"editor\" as \"e\"",
                "import \"editor.*\"",
                "import \"e.ditor\"",
                "import \"0x1234\"")) {
            assertThrows(FclCompileException.class, () -> compiler.compile(source), source);
        }
    }

    @Test
    void turnsRuntimeErrorsIntoDurableFailuresWithSourceInformation() {
        for (String source : List.of(
                "value = 1 / 0",
                "value = unknown",
                "value = [1][2]",
                "value = {a:1}[\"missing\"] + 1",
                "math.sqrt(-1)",
                "func one(a) { return a }; one()")) {
            FclProgram program = compiler.compile(source);
            FclContinuation continuation = new FclContinuation();
            FclStepResult last = execute(program, continuation, 1_000);
            assertEquals(FclStepResult.Status.FAILED, last.status(), source);
            assertTrue(continuation.halted(), source);
            assertTrue(continuation.failed(), source);
            assertFalse(continuation.exceptionStack().isEmpty(), source);
            assertTrue(continuation.exceptionStack().getLast().instructionPointer() >= 0, source);
        }
    }

    @Test
    void rejectsMalformedAndOutOfContextSyntax() {
        for (String source : List.of(
                "if true { value = 1",
                "break",
                "continue",
                "else { value = 1 }",
                "func bad(a, a) { return a }",
                "value = @",
                "import \"bad-name\"",
                "import \"editor\" as \"bad-name\"",
                "value = 9223372036854775808",
                "true = 1")) {
            assertThrows(FclCompileException.class, () -> compiler.compile(source), source);
        }
    }

    @Test
    void supportsMultilineStringLiterals() {
        FclContinuation state = run("""
                literal = "first line
                second line"
                crlf = "a
                b"
                joined = "l1
                " + "l2"
                """);
        assertEquals("first line\nsecond line", state.scope().get("literal"));
        assertEquals("a\nb", state.scope().get("crlf"));
        assertEquals("l1\nl2", state.scope().get("joined"));
    }

    @Test
    void reportsErrorsAfterAMultilineStringOnTheCorrectLine() {
        assertThrows(FclCompileException.class, () -> compiler.compile(
                "text = \"line1\nline2\"\nvalue = @\n"));
    }

    private FclContinuation run(String source) {
        FclProgram program = compiler.compile(source);
        FclContinuation continuation = new FclContinuation();
        FclStepResult last = execute(program, continuation, 20_000);
        assertFalse(last.status() == FclStepResult.Status.FAILED,
                () -> String.valueOf(last.value()));
        assertTrue(continuation.halted());
        return continuation;
    }

    private static FclStepResult execute(FclProgram program, FclContinuation continuation,
                                         int maximumSteps) {
        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        FclStepResult last = null;
        int steps = 0;
        while (!continuation.halted() && steps++ < maximumSteps) {
            last = runtime.executeOne(program, continuation);
        }
        if (last == null) throw new AssertionError("program executed no steps");
        assertTrue(continuation.halted(), "program exceeded step limit");
        return last;
    }
}
