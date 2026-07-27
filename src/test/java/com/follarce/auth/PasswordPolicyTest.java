package com.follarce.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordPolicyTest {
    @Test
    void acceptsSixCharactersAndRejectsShorterPasswords() {
        assertDoesNotThrow(() -> PasswordPolicy.require("123456".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.require("12345".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.require(null));
    }
}
