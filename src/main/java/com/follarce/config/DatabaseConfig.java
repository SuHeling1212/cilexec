package com.follarce.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable non-secret settings for one PostgreSQL connection role. */
public record DatabaseConfig(
        String jdbcUrl,
        String username,
        Path passwordFile,
        Optional<Path> sslRootCertificateFile,
        int maximumPoolSize,
        int minimumIdle,
        Duration connectionTimeout,
        Duration validationTimeout,
        Duration statementTimeout,
        String applicationName,
        boolean readOnly
) {
    private static final int MAXIMUM_POOL_SIZE = 512;
    private static final Duration MINIMUM_DRIVER_TIMEOUT = Duration.ofMillis(250);
    private static final Duration MAXIMUM_CONNECTION_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration MINIMUM_STATEMENT_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAXIMUM_STATEMENT_TIMEOUT = Duration.ofMinutes(10);

    public DatabaseConfig {
        jdbcUrl = requireJdbcUrl(jdbcUrl);
        username = requireText(username, "username");
        passwordFile = Objects.requireNonNull(passwordFile, "passwordFile");
        sslRootCertificateFile = Objects.requireNonNull(
                sslRootCertificateFile, "sslRootCertificateFile").map(path -> {
                    if (!path.isAbsolute()) {
                        throw new ConfigException("sslRootCertificateFile must be absolute");
                    }
                    return path.normalize();
                });
        if (JdbcUrlPolicy.requiresVerifiedTls(jdbcUrl)
                && sslRootCertificateFile.isEmpty()) {
            throw new ConfigException(
                    "Remote PostgreSQL requires a root CA file and sslmode=verify-full");
        }
        applicationName = requireText(applicationName, "applicationName");
        connectionTimeout = Objects.requireNonNull(connectionTimeout, "connectionTimeout");
        validationTimeout = Objects.requireNonNull(validationTimeout, "validationTimeout");
        statementTimeout = Objects.requireNonNull(statementTimeout, "statementTimeout");
        if (maximumPoolSize < 1 || minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new ConfigException("Invalid connection-pool bounds");
        }
        if (maximumPoolSize > MAXIMUM_POOL_SIZE) {
            throw new ConfigException("Connection pool exceeds safety limit of "
                    + MAXIMUM_POOL_SIZE);
        }
        requireRange(connectionTimeout, MINIMUM_DRIVER_TIMEOUT, MAXIMUM_CONNECTION_TIMEOUT,
                "connectionTimeout");
        requireRange(validationTimeout, MINIMUM_DRIVER_TIMEOUT, MAXIMUM_CONNECTION_TIMEOUT,
                "validationTimeout");
        requireRange(statementTimeout, MINIMUM_STATEMENT_TIMEOUT, MAXIMUM_STATEMENT_TIMEOUT,
                "statementTimeout");
        if (validationTimeout.compareTo(connectionTimeout) > 0) {
            throw new ConfigException("validationTimeout must not exceed connectionTimeout");
        }
    }

    public boolean verifyFullTls() {
        return sslRootCertificateFile.isPresent();
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

    private static void requireRange(Duration value, Duration minimum, Duration maximum,
                                     String name) {
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new ConfigException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
