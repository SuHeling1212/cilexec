package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FclInputBufferTest {
    @Test
    void waitsForBalancedBlocksAndIgnoresQuotedOrCommentedDelimiters() {
        assertFalse(FclInputBuffer.complete("func run() {\nvalue = 1"));
        assertTrue(FclInputBuffer.complete("func run() {\nvalue = 1\n}"));
        assertTrue(FclInputBuffer.complete("value = \"{\" // }"));
        assertFalse(FclInputBuffer.complete("value = #("));
        assertTrue(FclInputBuffer.complete("value = #[1, 2]"));
        assertTrue(FclInputBuffer.complete("items = [1, 2, 3]"));
    }

    @Test
    void trailingBackslashKeepsTheSubmissionOpenLikeTheCPreprocessor() {
        assertFalse(FclInputBuffer.complete("process.exec(\"hello.fcl\") \\"));
        assertFalse(FclInputBuffer.complete("if (ready) \\"));
        assertFalse(FclInputBuffer.complete("value = 1 + \\\n2 \\"));
        assertFalse(FclInputBuffer.complete("value = 2 \\"));
        assertTrue(FclInputBuffer.complete("value = 1 + \\\n2"));
        assertTrue(FclInputBuffer.complete("process.exec(\\\n\"/next.fcl\")"));
        assertTrue(FclInputBuffer.complete("value = \"line \\\\\\\\\""));
        assertFalse(FclInputBuffer.complete("value = #items \\"));
        assertTrue(FclInputBuffer.complete("value = 1 // comment \\"));
    }

    @Test
    void stripsLineContinuationsOutsideStringsAndComments() {
        assertEquals("process.exec(\"/next.fcl\")\n",
                FclInputBuffer.stripContinuations("process.exec(\\\n\"/next.fcl\")\n"));
        assertEquals("func f() { return 1 }\n",
                FclInputBuffer.stripContinuations("func f(\\\n) { return 1 }\n"));
        assertEquals("first second\n",
                FclInputBuffer.stripContinuations("first \\\r\nsecond\n"));
        assertEquals("value = \"a\\\nb\"\n",
                FclInputBuffer.stripContinuations("value = \"a\\\nb\"\n"));
        assertEquals("value = #[1, 2]\n",
                FclInputBuffer.stripContinuations("value = #[1, \\\n2]\n"));
    }
}
