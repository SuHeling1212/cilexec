package com.follarce.auth;

import java.util.Locale;
import java.text.Normalizer;

/** Canonical validation for every human CilExec username. */
public final class UsernamePolicy {
    private UsernamePolicy() {
    }

    public static String normalize(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        String normalized = Normalizer.normalize(username.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")
                || normalized.equals(".") || normalized.equals("..")) {
            throw new IllegalArgumentException("Username is invalid");
        }
        return normalized;
    }
}
