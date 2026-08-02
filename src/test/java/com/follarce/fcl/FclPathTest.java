package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FclPathTest {
    @Test
    void resolvesRelativePathsAgainstTheDurableWorkingDirectory() {
        assertEquals("/docs/note.txt", FclPath.resolve("/docs", "note.txt"));
        assertEquals("/archive/item", FclPath.resolve("/docs/work", "../../archive/item"));
        assertEquals("/absolute/item", FclPath.resolve("/docs", "/absolute/./item"));
    }

    @Test
    void normalizesAtTheVirtualRootAndRejectsNonAbsoluteWorkingDirectories() {
        assertEquals("/item", FclPath.resolve("/", "../../../item"));
        assertThrows(FclRuntimeException.class, () -> FclPath.resolve("relative", "item"));
    }

    @Test
    void keepsTheProcessWorkingDirectoryInsideUserFunctions() {
        FclProgram program = new FclCompiler().compile("""
                func inside() { return 1 }
                result = inside()
                """);
        FclContinuation continuation = new FclContinuation();
        continuation.scope().put(FclPath.SCOPE_KEY, "/market");
        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());

        FclStepResult entered;
        do {
            entered = runtime.executeOne(program, continuation);
        } while (entered.status() == FclStepResult.Status.ADVANCED);

        assertEquals(FclStepResult.Status.CALL_ENTERED, entered.status());
        assertEquals("/market", FclPath.current(continuation));
        assertEquals("/market/index.json", FclPath.resolve(continuation, "index.json"));
    }
}
