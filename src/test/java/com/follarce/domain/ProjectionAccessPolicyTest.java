package com.follarce.domain;

import com.follarce.domain.port.ProjectionAccessPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionAccessPolicyTest {
    @Test
    void administratorCanAddressRuntimeTablesButNotUnknownSchemas() {
        for (String name : new String[] {"auth.user_credential", "auth.login_throttle",
                "object_store.object", "process.process", "process.variable",
                "terminal.input", "effect.effect", "audit.event"}) {
            String[] parts = name.split("\\.");
            assertTrue(ProjectionAccessPolicy.canRead(parts[0], parts[1]), name);
            assertTrue(ProjectionAccessPolicy.canCreate(parts[0], parts[1]), name);
            assertTrue(ProjectionAccessPolicy.canDelete(parts[0], parts[1]), name);
        }
        assertFalse(ProjectionAccessPolicy.canRead("future", "table"));
    }

    @Test
    void blankTableNamesAreNotAddressable() {
        assertFalse(ProjectionAccessPolicy.canRead("auth", ""));
    }
}
