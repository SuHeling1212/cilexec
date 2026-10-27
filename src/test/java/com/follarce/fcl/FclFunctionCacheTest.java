package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FclFunctionCacheTest {
    @Test
    void dynamicCallsUseTheNormalCompiler() {
        AtomicInteger writes = new AtomicInteger();
        FclCompiler.FunctionBodyCache cache = new FclCompiler.FunctionBodyCache() {
            @Override public Optional<byte[]> find(String hash) { return Optional.empty(); }
            @Override public void save(String hash, byte[] body) { writes.incrementAndGet(); }
        };
        new FclCompiler().compile("func called(a){ return(a(1)) }", cache);
        assertEquals(0, writes.get());
    }

    @Test
    void reusesBranchingBodyAcrossRenamedParameters() {
        Map<String, byte[]> stored = new HashMap<>();
        AtomicInteger writes = new AtomicInteger();
        FclCompiler.FunctionBodyCache cache = new FclCompiler.FunctionBodyCache() {
            @Override public Optional<byte[]> find(String hash) {
                return Optional.ofNullable(stored.get(hash));
            }

            @Override public void save(String hash, byte[] body) {
                writes.incrementAndGet();
                stored.putIfAbsent(hash, body);
            }
        };
        FclCompiler compiler = new FclCompiler();
        compiler.compile("""
                func choose(a,b) {
                    if (a > b) { return(a) }
                    return(b)
                }
                """, cache);
        FclProgram program = compiler.compile("""
                func pick(x,y) {
                    if (y > x) { return(y) }
                    return(x)
                }
                result = pick(2, 7)
                """, cache);
        assertEquals(1, writes.get());
        assertEquals(1, stored.size());
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        for (int step = 0; step < 50 && !continuation.halted(); step++) {
            runtime.executeOne(program, continuation);
        }
        assertTrue(continuation.halted());
        assertEquals(7L, continuation.scope().get("result"));
    }

    @Test
    void literalMapKeysDoNotAliasParameterNames() {
        Map<String, byte[]> stored = new HashMap<>();
        FclCompiler.FunctionBodyCache cache = new FclCompiler.FunctionBodyCache() {
            @Override public Optional<byte[]> find(String hash) {
                return Optional.ofNullable(stored.get(hash));
            }
            @Override public void save(String hash, byte[] body) { stored.putIfAbsent(hash, body); }
        };
        FclCompiler compiler = new FclCompiler();
        compiler.compile("func first(a) { return({a: a}) }", cache);
        compiler.compile("func second(b) { return({b: b}) }", cache);
        assertEquals(2, stored.size());
    }

    @Test
    void reusesRenamedAndPermutedParametersWithoutChangingOrderedAddition() {
        Map<String, byte[]> stored = new HashMap<>();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        FclCompiler.FunctionBodyCache cache = new FclCompiler.FunctionBodyCache() {
            @Override public Optional<byte[]> find(String hash) {
                reads.incrementAndGet();
                return Optional.ofNullable(stored.get(hash));
            }

            @Override public void save(String hash, byte[] body) {
                writes.incrementAndGet();
                stored.putIfAbsent(hash, body);
            }
        };
        FclCompiler compiler = new FclCompiler();
        compiler.compile("func aaa(a,b){ return(a+b) }", cache);
        FclProgram program = compiler.compile("""
                func bbb(c,d){ return(d+c) }
                result = bbb("left", "right")
                """, cache);
        assertEquals(1, stored.size());
        assertEquals(1, writes.get());
        assertEquals(2, reads.get());

        FclRuntime runtime = new FclRuntime(FclBuiltins.pureRegistry());
        FclContinuation continuation = new FclContinuation();
        for (int step = 0; step < 20 && !continuation.halted(); step++) {
            runtime.executeOne(program, continuation);
        }
        assertTrue(continuation.halted());
        assertEquals("rightleft", continuation.scope().get("result"));
    }
}
