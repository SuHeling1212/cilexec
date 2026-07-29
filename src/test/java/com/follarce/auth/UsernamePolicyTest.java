package com.follarce.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsernamePolicyTest {
    @Test
    void canonicalizesUsernamesWithoutCaseSensitivity() {
        assertEquals("alice", UsernamePolicy.normalize(" Alice "));
        assertThrows(IllegalArgumentException.class,
                () -> UsernamePolicy.normalize("管理员"));
    }

    @Test
    void rejectsPathLikeUsernames() {
        assertThrows(IllegalArgumentException.class, () -> UsernamePolicy.normalize("../alice"));
    }
}
