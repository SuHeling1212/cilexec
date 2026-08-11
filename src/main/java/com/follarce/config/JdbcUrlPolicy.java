package com.follarce.config;

/** Shared validation for runtime and terminal PostgreSQL connection URLs. */
public final class JdbcUrlPolicy {
    private JdbcUrlPolicy() {
    }

    public static String requirePostgreSql(String value) {
        if (value == null || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)
                || !value.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("A PostgreSQL JDBC URL is required");
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(value.substring("jdbc:".length()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("PostgreSQL JDBC URL is invalid", invalid);
        }
        if (!"postgresql".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getRawPath() == null || uri.getRawPath().equals("/")) {
            throw new IllegalArgumentException(
                    "PostgreSQL JDBC URL must contain a host and database without user info");
        }
        rejectEmbeddedConnectionSettings(uri.getRawQuery());
        return value;
    }

    public static boolean requiresVerifiedTls(String value) {
        String checked = requirePostgreSql(value);
        java.net.URI uri = java.net.URI.create(checked.substring("jdbc:".length()));
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return !host.equals("localhost")
                && !host.equals("127.0.0.1")
                && !host.equals("::1")
                && !host.equals("[::1]");
    }

    private static void rejectEmbeddedConnectionSettings(String query) {
        if (query == null || query.isBlank()) return;
        for (String parameter : query.split("&")) {
            String rawName = parameter.split("=", 2)[0];
            String name;
            try {
                name = java.net.URLDecoder.decode(rawName, java.nio.charset.StandardCharsets.UTF_8)
                        .toLowerCase(java.util.Locale.ROOT);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("PostgreSQL JDBC URL query is invalid", invalid);
            }
            if (java.util.Set.of("user", "password", "sslpassword", "loggerfile",
                    "options", "ssl", "sslmode", "sslfactory", "sslfactoryarg",
                    "sslhostnameverifier", "sslcert", "sslkey", "sslrootcert")
                    .contains(name)) {
                throw new IllegalArgumentException(
                        "PostgreSQL credentials, TLS settings, and output paths must use dedicated configuration");
            }
        }
    }
}
