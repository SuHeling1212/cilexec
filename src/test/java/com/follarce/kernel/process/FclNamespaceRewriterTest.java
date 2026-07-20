package com.follarce.kernel.process;

import org.junit.jupiter.api.Test;

import com.follarce.kernel.process.FclNamespaceRewriter;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FclNamespaceRewriterTest {
    @Test
    void explicitDependencyBindingRewritesToItsInternalNamespace() {
        String rewritten = FclNamespaceRewriter.rewrite(
                "func value() { return dep.pick() }",
                "rootV1",
                Set.of("value"),
                Map.of("dep", "rootV1__abc"),
                Map.of("dep", Set.of("pick")));

        assertEquals("func rootV1.value() { return rootV1__abc.pick() }", rewritten);
    }

    @Test
    void ambiguousUnqualifiedDependencyCallIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FclNamespaceRewriter.rewrite(
                        "func value() { return pick() }",
                        "rootV1",
                        Set.of("value"),
                        Map.of("first", "rootV1__one", "second", "rootV1__two"),
                        Map.of("first", Set.of("pick"), "second", Set.of("pick"))));

        assertTrue(error.getMessage().contains("Ambiguous dependency function"));
    }
}
