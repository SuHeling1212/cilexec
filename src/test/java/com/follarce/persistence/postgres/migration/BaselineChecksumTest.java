package com.follarce.persistence.postgres.migration;

import db.migration.V001__CilexecBaseline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaselineChecksumTest {
    @Test
    void checksumTracksTheFrozenBaselineModules() {
        assertEquals(384_892_211, new V001__CilexecBaseline().getChecksum());
    }
}
