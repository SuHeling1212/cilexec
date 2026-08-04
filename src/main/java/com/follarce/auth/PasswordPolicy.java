package com.follarce.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Human terminal-account password policy and hashing. */
public final class PasswordPolicy {
    public static final int MINIMUM_LENGTH = 6;
    public static final int MAXIMUM_LENGTH = 1024;
    private static final int ITERATIONS = 310_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final String PREFIX = "pbkdf2-sha256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordPolicy() {
    }

    public static void require(char[] password) {
        if (password == null || password.length < MINIMUM_LENGTH
                || password.length > MAXIMUM_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must contain " + MINIMUM_LENGTH + " to "
                            + MAXIMUM_LENGTH + " characters");
        }
    }

    /** Produces a salted, deliberately expensive application credential verifier. */
    public static String hash(char[] password) {
        require(password);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        try {
            return PREFIX + "$" + ITERATIONS + "$"
                    + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                    + Base64.getEncoder().withoutPadding().encodeToString(derived);
        } finally {
            java.util.Arrays.fill(salt, (byte) 0);
            java.util.Arrays.fill(derived, (byte) 0);
        }
    }

    public static boolean matches(char[] password, String encoded) {
        if (password == null || encoded == null) return false;
        if (password.length > MAXIMUM_LENGTH) return false;
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
        final int iterations;
        final byte[] salt;
        final byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 2_000_000) return false;
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (salt.length < 16 || salt.length > 64 || expected.length != HASH_BITS / 8) {
            java.util.Arrays.fill(salt, (byte) 0);
            java.util.Arrays.fill(expected, (byte) 0);
            return false;
        }
        byte[] actual = derive(password, salt, iterations);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            java.util.Arrays.fill(salt, (byte) 0);
            java.util.Arrays.fill(expected, (byte) 0);
            java.util.Arrays.fill(actual, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 is unavailable", unavailable);
        } finally {
            spec.clearPassword();
        }
    }
}
