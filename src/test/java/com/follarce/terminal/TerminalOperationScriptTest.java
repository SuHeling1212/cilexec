package com.follarce.terminal;

import com.follarce.fcl.FclCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TerminalOperationScriptTest {
    @Test
    void preservesFclAndCommentsOutTerminalCommands() {
        String script = TerminalOperationScript.render(List.of(
                "value = 1", ":cd /docs", "io.println(value)"));
        assertEquals("""
                // CilExec terminal operation export
                // Terminal commands are retained as comments.

                value = 1

                // terminal: :cd /docs

                io.println(value)

                """, script);
        assertDoesNotThrow(() -> new FclCompiler().compile(script));
    }
}
