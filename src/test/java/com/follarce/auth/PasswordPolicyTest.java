package com.follarce.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordPolicyTest {
    @Test
    void acceptsEightCharactersAndRejectsShorterPasswords() {
        assertDoesNotThrow(() -> PasswordPolicy.require("12345678".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.require("1234567".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.require(null));
    }
}
