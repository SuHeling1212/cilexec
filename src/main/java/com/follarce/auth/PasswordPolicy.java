package com.follarce.auth;

/** Human terminal-account password policy; database service secrets remain stricter. */
public final class PasswordPolicy {
    public static final int MINIMUM_LENGTH = 8;

    private PasswordPolicy() {
    }

    public static void require(char[] password) {
        if (password == null || password.length < MINIMUM_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must contain at least " + MINIMUM_LENGTH + " characters");
        }
    }
}
