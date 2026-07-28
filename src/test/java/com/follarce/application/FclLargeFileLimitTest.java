package com.follarce.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FclLargeFileLimitTest {
    @Test
    void supportsAtLeastOneGibibytePerDownloadedFile() {
        assertTrue(FclRuntimeFunctions.MAX_FILE_BYTES >= 1L * 1024 * 1024 * 1024);
    }
}
