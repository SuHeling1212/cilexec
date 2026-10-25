package com.follarce.domain;

import com.follarce.domain.port.ProjectionAccessPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionAccessPolicyTest {
    @Test
    void sensitiveAndUnknownTablesFailClosed() {
        for (String name : new String[] {"auth.user_credential", "auth.login_throttle",
                "object_store.object", "process.process", "process.variable",
                "terminal.input", "effect.effect", "audit.event", "future.table"}) {
            String[] parts = name.split("\\.");
            assertFalse(ProjectionAccessPolicy.canRead(parts[0], parts[1]), name);
            assertTrue(ProjectionAccessPolicy.updateColumns(parts[0], parts[1]).isEmpty(), name);
            assertFalse(ProjectionAccessPolicy.canCreate(parts[0], parts[1]), name);
            assertFalse(ProjectionAccessPolicy.canDelete(parts[0], parts[1]), name);
        }
    }

    @Test
    void onlyReviewedDisplayColumnsCanChange() {
        assertTrue(ProjectionAccessPolicy.canRead("auth", "group_account"));
        assertEquals(java.util.Set.of("group_name"),
                ProjectionAccessPolicy.updateColumns("auth", "group_account"));
        assertEquals(java.util.Set.of("description"),
                ProjectionAccessPolicy.updateColumns("auth", "capability"));
        assertTrue(ProjectionAccessPolicy.updateColumns("vfs", "node").isEmpty());
        assertFalse(ProjectionAccessPolicy.readColumns("vfs", "node")
                .contains("current_object_hash"));
        assertFalse(ProjectionAccessPolicy.readColumns("auth", "user_credential")
                .contains("password_hash"));
    }
}
