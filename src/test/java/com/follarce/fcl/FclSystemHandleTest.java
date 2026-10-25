package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FclSystemHandleTest {
    @Test
    void chainCallsMethodsAndPreservesHandleIdentityAcrossCopies() {
        FclFunctionRegistry registry = FclBuiltins.pureRegistry();
        registry.register("sample", "open", args -> new FclSystemHandle("Sample", "id-1"));
        registry.registerMethod("Sample", "id", (args, ignored) ->
                ((FclSystemHandle) args.getFirst()).id());
        FclProgram program = new FclCompiler().compile("""
                a = sample.open()
                b = a
                first = a.id()
                second = sample.open().id()
                """);
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(registry);
        for (int step = 0; !continuation.halted() && step < 20; step++) {
            assertNotEquals(FclStepResult.Status.FAILED,
                    runtime.executeOne(program, continuation).status());
        }
        assertTrue(continuation.halted());
        assertEquals("id-1", continuation.scope().get("first"));
        assertEquals("id-1", continuation.scope().get("second"));
        assertEquals(continuation.scope().get("a"), continuation.scope().get("b"));
        assertEquals("id-1", ((FclSystemHandle) continuation.scope().get("b")).id());
        assertNotNull(new FclProgramCodec().fromBytes(new FclProgramCodec().toBytes(program),
                FclProgramCodec.FORMAT_VERSION, program.source()));
    }

    @Test
    void serializesOnlyLogicalIdentityAndHidesOldFunction() {
        FclSystemHandle handle = new FclSystemHandle("File", "node-1", Map.of("token", "7"));
        FclContinuationCodec codec = new FclContinuationCodec();
        assertEquals(handle, codec.valueFromJson(codec.valueToJson(handle)));
        FclFunctionRegistry registry = new FclFunctionRegistry();
        registry.register("file", "read", args -> "data").internalize("file.read");
        assertFalse(registry.qualifiedNames().contains("file.read"));
        assertThrows(FclRuntimeException.class, () -> registry.invoke("file.read", List.of("/x")));
        assertEquals("data", registry.invokeInternal("file.read", List.of("/x"),
                new FclFunctionRegistry.Invocation(1, new FclContinuation())));
    }
}
