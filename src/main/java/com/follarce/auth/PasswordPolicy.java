package com.follarce.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Human terminal-account password policy and hashing. */
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

    /** Returns the hex-encoded SHA-512 digest of the password. */
    public static String sha512Hex(char[] password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] raw = new String(password).getBytes(StandardCharsets.UTF_8);
            byte[] hash = digest.digest(raw);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new RuntimeException("SHA-512 not available", impossible);
        }
    }
}
