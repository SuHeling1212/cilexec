package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FclPathTest {
    @Test
    void resolvesRelativePathsAgainstTheDurableWorkingDirectory() {
        assertEquals("/docs/note.txt", FclPath.resolve("/docs", "note.txt"));
        assertEquals("/archive/item", FclPath.resolve("/docs/work", "../../archive/item"));
        assertEquals("/absolute/item", FclPath.resolve("/docs", "/absolute/./item"));
    }

    @Test
    void normalizesAtTheVirtualRootAndRejectsNonAbsoluteWorkingDirectories() {
        assertEquals("/item", FclPath.resolve("/", "../../../item"));
        assertThrows(FclRuntimeException.class, () -> FclPath.resolve("relative", "item"));
    }
}
