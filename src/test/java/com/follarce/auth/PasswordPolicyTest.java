package com.follarce.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test
    void acceptsSixCharactersAndRejectsShorterPasswords() {
        assertDoesNotThrow(() -> PasswordPolicy.require("123456".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.require("12345".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.require(null));
    }

    @Test
    void storesSaltedVerifierAndMatchesWithoutDatabaseLoginSecret() {
        char[] password = "12345678".toCharArray();
        String first = PasswordPolicy.hash(password);
        String second = PasswordPolicy.hash(password);
        assertNotEquals(first, second);
        assertTrue(PasswordPolicy.matches(password, first));
        assertFalse(PasswordPolicy.matches("87654321".toCharArray(), first));
    }
}
