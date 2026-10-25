package com.follarce.config;

import java.util.Map;

/** Process-local credentials for the bundled database; never persisted as password files. */
public final class LocalDatabaseCredentials {
    private static volatile Map<String, String> passwords = Map.of();

    private LocalDatabaseCredentials() {
    }

    public static void install(Map<String, String> values) {
        passwords = Map.copyOf(values);
    }

    public static String password(String username) {
        String value = passwords.get(username);
        if (value == null) throw new ConfigException("Local database credentials are unavailable");
        return value;
    }

    public static void clear() {
        passwords = Map.of();
    }
}
