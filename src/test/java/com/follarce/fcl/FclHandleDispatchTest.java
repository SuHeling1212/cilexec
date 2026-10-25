package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.0.5 dispatch tests: named receivers, expression receivers, system handles. */
class FclHandleDispatchTest {
    @Test
    void dispatchesNamedAndParenthesizedUserObjectMethodsWithWriteBack() {
        FclFunctionRegistry registry = FclBuiltins.pureRegistry();
        FclProgram program = new FclCompiler().compile("""
                class Counter {
                    value = 0
                    func increment() { this.value++ }
                    func addValue(step) { this.value = this.value + step }
                }
                c = new Counter()
                c.increment()
                c.increment()
                before = c.value
                (c).increment()
                after = c.value
                c.addValue(5)
                final = c.value
                """);
        FclContinuation continuation = run(new FclRuntime(registry), program);

        assertEquals(2L, continuation.scope().get("before"));
        assertEquals(3L, continuation.scope().get("after"));
        assertEquals(8L, continuation.scope().get("final"));
    }

    @Test
    void dispatchesHandleMethodsOnNamedAndChainedReceivers() {
        FclFunctionRegistry registry = FclBuiltins.pureRegistry();
        registry.register("sample", "open", arguments -> new FclSystemHandle("Sample", "id-1"));
        registry.registerMethod("Sample", "read", (arguments, invocation) ->
                "data:" + ((FclSystemHandle) arguments.getFirst()).id());
        registry.registerMethod("Sample", "repeat", (arguments, invocation) ->
                "repeat:"
                        + ((FclSystemHandle) arguments.getFirst()).id()
                        + ":" + arguments.get(1));
        FclProgram program = new FclCompiler().compile("""
                h = sample.open()
                named = h.read()
                chained = sample.open().read()
                withArgument = sample.open().repeat(7)
                copied = h
                fromCopy = copied.read()
                """);
        FclContinuation continuation = run(new FclRuntime(registry), program);

        assertEquals("data:id-1", continuation.scope().get("named"));
        assertEquals("data:id-1", continuation.scope().get("chained"));
        assertEquals("repeat:id-1:7", continuation.scope().get("withArgument"));
        assertEquals("data:id-1", continuation.scope().get("fromCopy"));
        assertEquals(continuation.scope().get("h"), continuation.scope().get("copied"));
    }

    @Test
    void suspendingChainedMethodResumesOnceAndDoesNotRepeatEarlierCalls() {
        AtomicInteger onceCalls = new AtomicInteger();
        AtomicInteger waitCalls = new AtomicInteger();
        FclFunctionRegistry registry = FclBuiltins.pureRegistry();
        registry.register("host", "open", arguments -> new FclSystemHandle("Host", "h-1"));
        registry.register("host", "once", arguments -> {
            onceCalls.incrementAndGet();
            return 40L;
        });
        registry.registerMethod("Host", "waitForReply", (arguments, invocation) -> {
            waitCalls.incrementAndGet();
            FclContinuation continuation = invocation.continuation();
            if (continuation.scope().contains("host.reply")) {
                return continuation.scope().remove("host.reply");
            }
            continuation.waitFor("test:host", Map.of());
            throw FclSuspension.suspend();
        });
        FclProgram program = new FclCompiler().compile(
                "answer = host.once() + host.open().waitForReply()\n");
        FclRuntime runtime = new FclRuntime(registry);
        FclContinuation continuation = new FclContinuation();

        assertEquals(FclStepResult.Status.WAITING,
                runtime.executeOne(program, continuation).status());
        assertEquals(1, onceCalls.get());
        assertEquals(1, waitCalls.get());

        FclContinuationCodec codec = new FclContinuationCodec();
        continuation = codec.fromJson(codec.toJson(continuation));
        continuation.clearWait();
        continuation.scope().put("host.reply", 2L);

        assertEquals(FclStepResult.Status.ADVANCED,
                runtime.executeOne(program, continuation).status());
        assertEquals(42L, continuation.scope().get("answer"));
        assertEquals(1, onceCalls.get(), "an earlier call result must survive the suspend");
        assertEquals(2, waitCalls.get());
    }

    @Test
    void handlesSurviveContinuationSaveAndRestoreInsideACallFrame() {
        AtomicInteger lateCalls = new AtomicInteger();
        FclFunctionRegistry registry = FclBuiltins.pureRegistry();
        registry.register("sample", "open", arguments -> new FclSystemHandle("Sample", "node-9"));
        registry.registerMethod("Sample", "late", (arguments, invocation) -> {
            lateCalls.incrementAndGet();
            FclContinuation continuation = invocation.continuation();
            if (continuation.scope().contains("later")) {
                return continuation.scope().remove("later");
            }
            continuation.waitFor("test:late", Map.of());
            throw FclSuspension.suspend();
        });
        FclProgram program = new FclCompiler().compile("""
                func wrap(value) { return value.late() }
                h = sample.open()
                result = wrap(h)
                """);
        FclRuntime runtime = new FclRuntime(registry);
        FclContinuation continuation = new FclContinuation();

        FclStepResult status = null;
        for (int step = 0; step < 20 && !continuation.halted(); step++) {
            status = runtime.executeOne(program, continuation);
            if (status.status() == FclStepResult.Status.WAITING) break;
        }
        FclStepResult observed = status;
        assertTrue(observed != null && observed.status() == FclStepResult.Status.WAITING,
                String.valueOf(observed));
        assertEquals(1, continuation.callStack().size());
        FclSystemHandle frameHandle = (FclSystemHandle) continuation.callStack().getFirst()
                .callerScope().get("h");

        FclContinuationCodec codec = new FclContinuationCodec();
        FclContinuation restored = codec.fromJson(codec.toJson(continuation));

        FclSystemHandle restoredScopeHandle = (FclSystemHandle) restored.callStack().getFirst()
                .callerScope().get("h");
        assertEquals(frameHandle, restoredScopeHandle);
        assertEquals("Sample", restoredScopeHandle.kind());
        assertEquals("node-9", restoredScopeHandle.id());
        assertEquals(1, restored.callStack().size());

        restored.clearWait();
        restored.scope().put("later", "done");
        run(runtime, program, restored);
        assertEquals("done", restored.scope().get("result"));
        assertEquals(2, lateCalls.get());
    }

    private static FclContinuation run(FclRuntime runtime, FclProgram program) {
        FclContinuation continuation = new FclContinuation();
        run(runtime, program, continuation);
        return continuation;
    }

    private static void run(FclRuntime runtime, FclProgram program,
                            FclContinuation continuation) {
        for (int step = 0; !continuation.halted() && step < 200; step++) {
            FclStepResult result = runtime.executeOne(program, continuation);
            assertNotEquals(FclStepResult.Status.FAILED, result.status(),
                    () -> String.valueOf(result.value()));
        }
        assertFalse(continuation.failed());
        assertTrue(continuation.halted());
    }
}
