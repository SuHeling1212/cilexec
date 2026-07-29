package com.follarce.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Immutable non-secret settings for one PostgreSQL connection role. */
public record DatabaseConfig(
        String jdbcUrl,
        String username,
        Path passwordFile,
        int maximumPoolSize,
        int minimumIdle,
        Duration connectionTimeout,
        Duration validationTimeout,
        String applicationName
) {
    public DatabaseConfig {
        jdbcUrl = requireJdbcUrl(jdbcUrl);
        username = requireText(username, "username");
        passwordFile = Objects.requireNonNull(passwordFile, "passwordFile");
        applicationName = requireText(applicationName, "applicationName");
        connectionTimeout = Objects.requireNonNull(connectionTimeout, "connectionTimeout");
        validationTimeout = Objects.requireNonNull(validationTimeout, "validationTimeout");
        if (maximumPoolSize < 1 || minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new ConfigException("Invalid connection-pool bounds");
        }
        if (connectionTimeout.isNegative() || connectionTimeout.isZero()
                || validationTimeout.isNegative() || validationTimeout.isZero()) {
            throw new ConfigException("Database timeouts must be positive");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ConfigException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String requireJdbcUrl(String value) {
        try {
            return JdbcUrlPolicy.requirePostgreSql(requireText(value, "jdbcUrl"));
        } catch (IllegalArgumentException invalid) {
            throw new ConfigException(invalid.getMessage(), invalid);
        }
    }
}
