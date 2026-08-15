package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FclPackageProvenanceTest {
    private static final String PACKAGE_HASH = "a".repeat(64);

    @Test
    void linkedPackageFunctionsCarryTheirIdentityIntoBuiltinCalls() {
        AtomicReference<String> observed = new AtomicReference<>();
        FclFunctionRegistry functions = new FclFunctionRegistry()
                .registerContextual("host", "probe", (arguments, invocation) -> {
                    observed.set(invocation.packageIdentity());
                    return 7L;
                });
        FclProgram base = new FclCompiler().compile("value = demo.greet()\n");
        FclProgramLinker.Module module = new FclProgramLinker.Module(PACKAGE_HASH, "main",
                "func greet() { return host.probe() }\n",
                List.of(new FclProgramLinker.Export("greet", List.of("demo.greet"))));
        FclProgram linked = new FclProgramLinker().link(base, List.of(module));
        assertEquals(PACKAGE_HASH, linked.function("demo.greet").packageIdentity());

        FclRuntime runtime = new FclRuntime(functions);
        FclContinuation continuation = new FclContinuation();
        int steps = 0;
        while (!continuation.halted() && steps++ < 20) {
            runtime.executeOne(linked, continuation);
        }
        assertEquals(7L, continuation.scope().get("value"));
        assertEquals(PACKAGE_HASH, observed.get());
    }

    @Test
    void topLevelUserCodeHasNoPackageIdentity() {
        AtomicReference<String> observed = new AtomicReference<>("unset");
        FclFunctionRegistry functions = new FclFunctionRegistry()
                .registerContextual("host", "probe", (arguments, invocation) -> {
                    observed.set(invocation.packageIdentity());
                    return 1L;
                });
        FclProgram program = new FclCompiler().compile("value = host.probe()\n");
        FclContinuation continuation = new FclContinuation();
        int steps = 0;
        while (!continuation.halted() && steps++ < 10) {
            new FclRuntime(functions).executeOne(program, continuation);
        }
        assertEquals(1L, continuation.scope().get("value"));
        assertNull(observed.get());
    }
}
