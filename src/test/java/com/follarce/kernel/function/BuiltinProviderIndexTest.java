package com.follarce.kernel.function;

import com.follarce.bootstrap.BuiltinProviderIndex;
import com.follarce.kernel.api.function.FunctionProvider;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinProviderIndexTest {
    private static final Set<String> BUILTIN_NAMESPACES = Set.of(
            "file", "io", "math", "network", "package", "path", "system",
            "process", "socket", "swapPool", "term", "user", "util");

    @Test
    void installsEveryCompileTimeBuiltinWithoutAnExternalSpiEntryPoint() {
        ClassLoader classLoader = getClass().getClassLoader();

        assertEquals(13, BuiltinProviderIndex.install());
        assertEquals(13, BuiltinProviderIndex.install());
        assertTrue(FunctionRegistry.providerNamespaces().containsAll(BUILTIN_NAMESPACES));
        assertNull(classLoader.getResource("META-INF/services/" + FunctionProvider.class.getName()));
    }
}
