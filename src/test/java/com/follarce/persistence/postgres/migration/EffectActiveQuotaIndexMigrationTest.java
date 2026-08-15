package com.follarce.persistence.postgres.migration;

import db.migration.V002__EffectActiveQuotaIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectActiveQuotaIndexMigrationTest {
    @Test
    void indexesExactlyTheStatusesCountedByTheActiveEffectQuota() {
        String sql = V002__EffectActiveQuotaIndex.SQL;

        assertTrue(sql.contains("ON effect.effect(owner_id)"));
        assertTrue(sql.contains("'PREPARED','CLAIMED','EXECUTING','UNKNOWN'"));
    }
}
