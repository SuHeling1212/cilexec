package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FclRuntimeTest {
    @Test
    void copiesObjectsOnAssignmentAndDispatchesMethodsAgainstTheCopiedValue() {
        FclProgram program = new FclCompiler().compile("""
                class Box {
                    value = 0
                    func increment() { this.value = this.value + 1 }
                }
                box = new Box()
                copy = box
                copy.increment()
                original = box.value
                changed = copy.value
                """);
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        runToCompletion(program, continuation);
        assertEquals(0L, continuation.scope().get("original"));
        assertEquals(1L, continuation.scope().get("changed"));
        assertFalse(continuation.scope().get("box").equals(continuation.scope().get("copy")));
    }

    @Test
    void copiesObjectArgumentsAndNestedMutableFieldsWithoutObservableSharing() {
        FclProgram program = new FclCompiler().compile("""
                class Counter {
                    value = 0
                    items = [1, 2]
                    func increment() { this.value++ }
                }
                func changed(counter) {
                    counter.increment()
                    counter.items[0] = 99
                    return counter
                }
                original = new Counter()
                copy = changed(original)
                originalValue = original.value
                copyValue = copy.value
                originalItem = original.items[0]
                copyItem = copy.items[0]
                """);
        FclContinuation continuation = new FclContinuation();
        runToCompletion(program, continuation);
        assertEquals(0L, continuation.scope().get("originalValue"));
        assertEquals(1L, continuation.scope().get("copyValue"));
        assertEquals(1L, continuation.scope().get("originalItem"));
        assertEquals(99L, continuation.scope().get("copyItem"));
    }

    @Test
    void runsConstructorsBeforeReturningTheNewObject() {
        FclProgram program = new FclCompiler().compile("""
                class User {
                    name = ""
                    init(name) { this.name = name }
                }
                user = new User("Ada")
                answer = user.name
                """);
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        runToCompletion(program, continuation);
        assertEquals("Ada", continuation.scope().get("answer"));
    }

    @Test
    void inheritsFieldsAndDynamicallyDispatchesOverrides() {
        FclProgram program = new FclCompiler().compile("""
                class Animal {
                    name = "unknown"
                    func sound() { return "..." }
                }
                class Cat extends Animal {
                    func sound() { return "meow" }
                }
                cat = new Cat()
                cat.name = "Mimi"
                answer = cat.sound() + ":" + cat.name
                """);
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        runToCompletion(program, continuation);
        assertEquals("meow:Mimi", continuation.scope().get("answer"));
    }

    @Test
    void enforcesPrivateMembersUsingTheDeclaringClassContext() {
        FclProgram program = new FclCompiler().compile("""
                class Vault {
                    private secret = "s"
                    private func reveal() { return this.secret }
                    func read() { return this.reveal() }
                }
                vault = new Vault()
                answer = vault.read()
                """);
        FclContinuation continuation = new FclContinuation();
        runToCompletion(program, continuation);
        assertEquals("s", continuation.scope().get("answer"));

        FclProgram denied = new FclCompiler().compile("""
                class Vault { private secret = "s" }
                vault = new Vault()
                answer = vault.secret
                """);
        FclContinuation deniedContinuation = new FclContinuation();
        FclStepResult failed = null;
        for (int step = 0; step < 20 && !deniedContinuation.halted(); step++) {
            failed = runtime.executeOne(denied, deniedContinuation);
        }
        assertTrue(deniedContinuation.failed());
        assertEquals(FclStepResult.Status.FAILED, failed.status());
    }

    @Test
    void invokesParentMethodsAndConstructorsThroughSuper() {
        FclProgram program = new FclCompiler().compile("""
                class Parent {
                    name = ""
                    init(name) { this.name = name }
                    func greeting() { return "parent:" + this.name }
                }
                class Child extends Parent {
                    init(name) { super(name) }
                    func greeting() { return super.greeting() + ":child" }
                }
                child = new Child("Ada")
                answer = child.greeting()
                """);
        FclContinuation continuation = new FclContinuation();
        runToCompletion(program, continuation);
        assertEquals("parent:Ada:child", continuation.scope().get("answer"));
    }
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
    void baseFunctionsReadGlobalVariablesButNotCallerLocals() {
        FclProgram program = compiler.compile("""
                root = 10
                func readRoot() { return root }
                func shadowRoot() { root = 20; return root }
                result = readRoot()
                shadowed = shadowRoot()
                after = root
                """);
        FclContinuation continuation = new FclContinuation();

        while (!continuation.halted()) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }

        assertEquals(10L, continuation.scope().get("result"));
        assertEquals(20L, continuation.scope().get("shadowed"));
        assertEquals(10L, continuation.scope().get("after"));

        FclProgram callerScopeAttempt = compiler.compile("""
                func readCallerLocal() { return callerLocal }
                func caller() { callerLocal = 99; return readCallerLocal() }
                caller()
                """);
        FclContinuation rejectedCaller = new FclContinuation();
        FclStepResult last = null;
        while (!rejectedCaller.halted()) last = runtime.executeOne(callerScopeAttempt, rejectedCaller);
        assertEquals(FclStepResult.Status.FAILED, last.status());
        assertTrue(String.valueOf(last.value()).contains("Undefined variable: callerLocal"));
    }

    @Test
    void privateFunctionsRemainAvailableInternallyButNotFromTopLevel() {
        FclProgram publicProgram = compiler.compile("""
                private func helper() { return 7 }
                public func api() { return helper() }
                value = api()
                """);
        FclContinuation publicContinuation = new FclContinuation();
        runToCompletion(publicProgram, publicContinuation);
        assertEquals(7L, publicContinuation.scope().get("value"));

        FclProgram privateProgram = compiler.compile("""
                private func helper() { return 7 }
                helper()
                """);
        FclContinuation privateContinuation = new FclContinuation();
        FclStepResult last = null;
        while (!privateContinuation.halted()) {
            last = runtime.executeOne(privateProgram, privateContinuation);
        }
        assertEquals(FclStepResult.Status.FAILED, last.status());
        assertTrue(String.valueOf(last.value()).contains("Undefined function: helper"));
    }

    @Test
    void sourceImportUsesItsOwnPersistedModuleGlobals() {
        FclProgram base = compiler.compile("result = module.read()\n");
        String moduleSource = """
                value = 1
                public func read() { return value }
                """;
        FclProgram linked = new FclProgramLinker().link(base, List.of(
                new FclProgramLinker.Module("/module.fcl", "module", moduleSource,
                        List.of(new FclProgramLinker.Export("read", List.of("module.read"))),
                        Map.of("value", 1L))));
        FclContinuation continuation = new FclContinuation();
        runToCompletion(linked, continuation);

        assertEquals(1L, continuation.scope().get("result"));
        assertFalse(continuation.scope().contains("value"));
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

    @Test
    void catchesRuntimeFailuresWithImmutableStructuredExceptionValues() {
        FclProgram program = compiler.compile("""
                saved = null
                try {
                    missing()
                    skipped = true
                } catch (e) {
                    saved = e
                    kind = e.type
                    text = e.message
                    frameFunction = e.stack[0].function
                    frameLine = e.stack[0].line
                }
                after = true
                """);
        FclContinuation continuation = new FclContinuation();

        runToCompletion(program, continuation);

        FclExceptionValue exception = (FclExceptionValue) continuation.scope().get("saved");
        assertEquals("UndefinedFunction", continuation.scope().get("kind"));
        assertTrue(String.valueOf(continuation.scope().get("text")).contains("Undefined function"));
        assertEquals("<main>", continuation.scope().get("frameFunction"));
        assertEquals(3L, continuation.scope().get("frameLine"));
        assertEquals("UndefinedFunction", exception.type());
        assertFalse(continuation.scope().contains("e"));
        assertFalse(continuation.scope().contains("skipped"));
        assertEquals(true, continuation.scope().get("after"));
    }

    @Test
    void propagatesAcrossCallsAndDoesNotRecatchFailuresFromCatchBodies() {
        FclProgram program = compiler.compile("""
                func leaf() { missing() }
                func middle() { leaf() }
                try {
                    try {
                        middle()
                    } catch (inner) {
                        missingAgain()
                    }
                } catch (outer) {
                    caught = outer.type
                }
                """);
        FclContinuation continuation = new FclContinuation();

        runToCompletion(program, continuation);

        assertEquals("UndefinedFunction", continuation.scope().get("caught"));
        assertFalse(continuation.scope().contains("inner"));
        assertFalse(continuation.scope().contains("outer"));
    }

    @Test
    void restoresPersistedTryHandlerAndExceptionValueAcrossSuspend() {
        FclFunctionRegistry functions = new FclFunctionRegistry()
                .registerContextual("host", "wait", (arguments, invocation) -> {
                    if (invocation.continuation().scope().contains("resume")) {
                        return invocation.continuation().scope().remove("resume");
                    }
                    invocation.continuation().waitFor("test:resume", Map.of());
                    throw FclSuspension.suspend();
                });
        FclRuntime suspendedRuntime = new FclRuntime(functions);
        FclProgram program = compiler.compile("""
                saved = null
                try {
                    host.wait()
                    missing()
                } catch (e) {
                    saved = e
                }
                kind = saved.type
                """);
        FclContinuation continuation = new FclContinuation();

        assertEquals(FclStepResult.Status.ADVANCED, suspendedRuntime.executeOne(program, continuation).status());
        assertEquals(FclStepResult.Status.ADVANCED, suspendedRuntime.executeOne(program, continuation).status());
        assertEquals(FclStepResult.Status.WAITING, suspendedRuntime.executeOne(program, continuation).status());
        assertEquals(1, continuation.exceptionHandlers().size());
        FclContinuationCodec codec = new FclContinuationCodec();
        continuation = codec.fromJson(codec.toJson(continuation));
        continuation.clearWait();
        continuation.scope().put("resume", true);
        while (!continuation.halted()) {
            FclStepResult step = suspendedRuntime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED, () -> String.valueOf(step.value()));
        }
        assertEquals("UndefinedFunction", continuation.scope().get("kind"));
        FclExceptionValue saved = (FclExceptionValue) continuation.scope().get("saved");
        assertEquals("UndefinedFunction", saved.type());
        assertThrows(UnsupportedOperationException.class, () -> saved.stack().add(
                new FclStackFrame("x", "y", 1, 1)));
    }

    @Test
    void importsPublicClassesButKeepsPrivateClassesOutsideTheLinkedProgram() {
        FclProgram base = compiler.compile("""
                user = new User("Ada")
                answer = user.label()
                """);
        FclProgram linked = new FclProgramLinker().link(base, List.of(new FclProgramLinker.Module(
                "/user.fcl", "user", """
                        public class User {
                            name = ""
                            private prefix = "user:"
                            init(name) { this.name = name }
                            func label() { return this.prefix + this.name }
                        }
                        private class InternalUser { }
                        """, List.of())));
        FclContinuation continuation = new FclContinuation();
        runToCompletion(linked, continuation);
        assertEquals("user:Ada", continuation.scope().get("answer"));
        assertFalse(linked.classes().containsKey("InternalUser"));
    }

    @Test
    void doesNotExposeKernelFailuresToFclCatchBlocks() {
        FclFunctionRegistry functions = new FclFunctionRegistry().register("host", "broken",
                arguments -> { throw new IllegalStateException("kernel failure"); });
        FclProgram program = compiler.compile("""
                try { host.broken() } catch (e) { caught = true }
                """);
        FclContinuation continuation = new FclContinuation();
        FclRuntime kernelRuntime = new FclRuntime(functions);

        assertEquals(FclStepResult.Status.ADVANCED, kernelRuntime.executeOne(program, continuation).status());
        assertEquals(FclStepResult.Status.FAILED, kernelRuntime.executeOne(program, continuation).status());
        assertTrue(continuation.failed());
        assertFalse(continuation.scope().contains("caught"));
    }

    @Test
    void updatesVariablesFieldsAndIndexedValuesWithTheUpdatedValue() {
        FclProgram program = compiler.compile("""
                class Counter { value = 10 }
                count = 1
                before = count++
                after = count++
                count--
                items = [5]
                itemBefore = items[0]++
                values = {score: 7}
                scoreAfter = values["score"]++
                counter = new Counter()
                fieldBefore = counter.value++
                fieldAfter = counter.value++
                """);
        FclContinuation continuation = new FclContinuation();

        runToCompletion(program, continuation);

        assertEquals(2L, continuation.scope().get("before"));
        assertEquals(3L, continuation.scope().get("after"));
        assertEquals(2L, continuation.scope().get("count"));
        assertEquals(6L, continuation.scope().get("itemBefore"));
        assertEquals(List.of(6L), continuation.scope().get("items"));
        assertEquals(8L, continuation.scope().get("scoreAfter"));
        assertEquals(Map.of("score", 8L), continuation.scope().get("values"));
        assertEquals(11L, continuation.scope().get("fieldBefore"));
        assertEquals(12L, continuation.scope().get("fieldAfter"));
    }

    @Test
    void linkMakesOnlyTheNamedTargetFollowItsSourceThroughUpdatesAndReplacement() {
        FclProgram program = compiler.compile("""
                class Human { age = 0 }
                a = new Human()
                a.age = 10
                b link a
                b.age++
                afterUpdate = a.age
                a = new Human()
                afterReplacement = b.age
                b.age = 42
                afterWriteThroughLink = a.age
                copy = a
                copy.age = 99
                afterOrdinaryCopy = a.age
                """);
        FclContinuation continuation = new FclContinuation();

        runToCompletion(program, continuation);

        assertEquals(11L, continuation.scope().get("afterUpdate"));
        assertEquals(0L, continuation.scope().get("afterReplacement"));
        assertEquals(42L, continuation.scope().get("afterWriteThroughLink"));
        assertEquals(42L, continuation.scope().get("afterOrdinaryCopy"));
        assertTrue(program.instructions().stream().anyMatch(FclInstruction.Link.class::isInstance));
    }

    @Test
    void linkWorksForNumbersArraysAndObjectsAndRejectsMalformedOperatorUse() {
        FclProgram program = compiler.compile("""
                number = 1
                numberAlias link number
                numberAlias++
                items = [1, 2]
                itemAlias link items
                itemAlias[0] = 9
                class Box { value = 0 }
                box = new Box()
                boxAlias link box
                boxAlias.value = 7
                """);
        FclContinuation continuation = new FclContinuation();

        runToCompletion(program, continuation);

        assertEquals(2L, continuation.scope().get("number"));
        assertEquals(2L, continuation.scope().get("numberAlias"));
        assertEquals(List.of(9L, 2L), continuation.scope().get("items"));
        assertEquals(List.of(9L, 2L), continuation.scope().get("itemAlias"));
        assertEquals(7L, ((FclObjectValue) continuation.scope().get("box")).field("value"));
        assertThrows(FclCompileException.class, () -> compiler.compile("a linkb\n"));
        assertThrows(FclCompileException.class, () -> compiler.compile("linka b\n"));
        assertThrows(FclCompileException.class, () -> compiler.compile("link = 1\n"));
        assertThrows(FclCompileException.class, () -> compiler.compile("a share b\n"));
    }

    @Test
    void destroyingAnyLinkedNameAlsoDestroysItsSourceAndEveryDependentLink() {
        FclScope scope = new FclScope();
        scope.put("a", 10L);
        scope.link("b", "a");
        scope.link("c", "b");
        scope.destroy("b");
        assertFalse(scope.contains("a"));
        assertFalse(scope.contains("b"));
        assertFalse(scope.contains("c"));
        assertThrows(FclRuntimeException.class, () -> scope.get("b"));
    }

    @Test
    void compilesThePublishedObjectOrientedSmokeTest() throws Exception {
        String source = Files.readString(Path.of("docs", "examples", "fcl-oop-smoke-test.fcl"));

        assertDoesNotThrow(() -> compiler.compile(source));
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
