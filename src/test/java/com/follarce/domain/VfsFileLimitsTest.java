package com.follarce.domain;

import com.follarce.domain.vfs.VfsFileLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VfsFileLimitsTest {
    @Test
    void acceptsExactlyOneGiBAndRejectsMore() {
        assertEquals(VfsFileLimits.MAX_FILE_BYTES,
                VfsFileLimits.checkedAppendSize(VfsFileLimits.MAX_FILE_BYTES - 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> VfsFileLimits.requireWithinLimit(VfsFileLimits.MAX_FILE_BYTES + 1));
        assertThrows(IllegalArgumentException.class,
                () -> VfsFileLimits.checkedAppendSize(VfsFileLimits.MAX_FILE_BYTES, 1));
    }
}
