package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalDimensionsTest {
    @Test
    void parsesSttyRowsThenColumnsIntoWidthAndHeight() {
        assertEquals(Optional.of(new TerminalDimensions.Size(132, 41)),
                TerminalDimensions.parse("41 132\n"));
        assertTrue(TerminalDimensions.parse("0 80").isEmpty());
        assertTrue(TerminalDimensions.parse("not-a-size").isEmpty());
    }

    @Test
    void dimensionsMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalDimensions.Size(0, 24));
    }
}
