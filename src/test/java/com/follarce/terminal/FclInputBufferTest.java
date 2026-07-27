package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FclInputBufferTest {
    @Test
    void waitsForBalancedBlocksAndIgnoresQuotedOrCommentedDelimiters() {
        assertFalse(FclInputBuffer.complete("func run() {\nvalue = 1"));
        assertTrue(FclInputBuffer.complete("func run() {\nvalue = 1\n}"));
        assertTrue(FclInputBuffer.complete("value = \"{\" # }"));
        assertTrue(FclInputBuffer.complete("items = [1, 2, 3]"));
    }
}
