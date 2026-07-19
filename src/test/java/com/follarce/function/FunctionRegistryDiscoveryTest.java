package com.follarce.function;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionRegistryDiscoveryTest {
    private static final Set<String> BUILTIN_NAMESPACES = Set.of(
            "file", "io", "math", "network", "package", "path", "system",
            "process", "socket", "swapPool", "term", "user", "util");

    @Test
    void discoversEveryBuiltinProviderThroughJavaSpiIdempotently() {
        ClassLoader classLoader = getClass().getClassLoader();

        assertEquals(13, FunctionRegistry.loadProviders(classLoader));
        assertEquals(13, FunctionRegistry.loadProviders(classLoader));
        assertTrue(FunctionRegistry.providerNamespaces().containsAll(BUILTIN_NAMESPACES));
    }
}
