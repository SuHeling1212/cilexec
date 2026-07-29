package com.follarce.persistence.postgres.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimCommitFenceMigrationTest {
    @Test
    void finalStatementGuardRequiresLiveControlProofAndUnexpiredExactLease()
            throws IOException {
        String sql = BaselineSql.load();

        assertTrue(sql.contains("control_backend_pid"));
        assertTrue(sql.contains("control_proof_lock_key"));
        assertTrue(sql.contains("GRANT EXECUTE ON FUNCTION scheduler.claim_authorizes_commit_as"));
        assertTrue(sql.contains("scheduler.claim_authorizes_commit"));
        assertTrue(sql.contains("lease.expires_at > clock_timestamp()"));
        assertTrue(sql.contains("lease.execution_epoch = p_execution_epoch"));
        assertTrue(sql.contains("lease.runner_id = p_runner_id"));
        assertTrue(sql.contains("lease.boot_id = p_boot_id"));
        assertTrue(sql.contains("control_lock.locktype = 'advisory'"));
        assertTrue(sql.contains("proof_lock.locktype = 'advisory'"));
        assertTrue(sql.contains("boot.status IN ('RECOVERING', 'ACTIVE')"));
        assertFalse(sql.contains("GRANT EXECUTE ON FUNCTION scheduler.claim_authorizes_commit("
                + "\n    uuid, uuid, uuid, uuid, bigint\n) TO PUBLIC"));
    }
}
