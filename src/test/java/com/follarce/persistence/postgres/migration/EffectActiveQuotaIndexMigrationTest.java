package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectActiveQuotaIndexMigrationTest {
    @Test
    void baselineIndexesExactlyTheStatusesCountedByTheActiveEffectQuota() throws IOException {
        String sql = BaselineSql.load();

        assertTrue(sql.contains("ON effect.effect(owner_id)"));
        assertTrue(sql.contains("'PREPARED', 'CLAIMED', 'EXECUTING', 'UNKNOWN'"));
    }
}
