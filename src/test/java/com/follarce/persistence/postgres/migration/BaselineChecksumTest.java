package com.follarce.persistence.postgres.migration;

import db.migration.V001__CilexecBaseline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaselineChecksumTest {
    @Test
    void checksumTracksTheFrozenBaselineModules() {
        assertEquals(-607_161_170, new V001__CilexecBaseline().getChecksum());
    }
}
