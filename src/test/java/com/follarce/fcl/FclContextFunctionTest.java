package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FclContextFunctionTest {
    @Test
    void resumesSuspendedHostCallAndDoesNotRepeatEarlierCallsInTheStatement() {
        AtomicInteger onceCalls = new AtomicInteger();
        AtomicInteger waitCalls = new AtomicInteger();
        FclFunctionRegistry functions = new FclFunctionRegistry()
                .register("host", "once", arguments -> {
                    onceCalls.incrementAndGet();
                    return 40L;
                })
                .registerContextual("host", "wait", (arguments, invocation) -> {
                    waitCalls.incrementAndGet();
                    FclContinuation continuation = invocation.continuation();
                    if (continuation.scope().contains("host.reply")) {
                        return continuation.scope().remove("host.reply");
                    }
                    continuation.waitFor("input:host-test", Map.of());
                    throw FclSuspension.suspend();
                });
        FclRuntime runtime = new FclRuntime(functions);
        FclProgram program = new FclCompiler().compile("answer = host.once() + host.wait()\n");
        FclContinuation continuation = new FclContinuation();

        FclStepResult suspended = runtime.executeOne(program, continuation);
        assertEquals(FclStepResult.Status.WAITING, suspended.status());
        assertEquals(0, continuation.programCounter());
        assertFalse(continuation.halted());
        assertEquals(1, onceCalls.get());
        assertEquals(1, waitCalls.get());

        FclContinuationCodec codec = new FclContinuationCodec();
        continuation = codec.fromJson(codec.toJson(continuation));
        continuation.clearWait();
        continuation.scope().put("host.reply", 2L);

        assertEquals(FclStepResult.Status.ADVANCED,
                runtime.executeOne(program, continuation).status());
        assertEquals(42L, continuation.scope().get("answer"));
        assertEquals(1, onceCalls.get(), "the first host call must be statement-idempotent");
        assertEquals(2, waitCalls.get());
        assertFalse(continuation.scope().contains("host.reply"));
    }
}
