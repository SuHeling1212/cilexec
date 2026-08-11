package com.follarce.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/** Complete validated runtime configuration; passwords remain in mounted secret files. */
public record CilExecConfig(
        String instanceName,
        long advisoryLockKey,
        DatabaseConfig runtimeDatabase,
        DatabaseConfig effectDatabase,
        DatabaseConfig migratorDatabase,
        DatabaseConfig exporterDatabase,
        int schedulerWorkers,
        int effectWorkers,
        Duration leaseDuration,
        Duration schedulerErrorBackoff,
        Duration effectErrorBackoff,
        Duration shutdownGrace,
        Duration healthDatabaseProbeInterval,
        int healthPort,
        boolean migrateOnStart
) {
    private static final int MAXIMUM_WORKERS = 256;
    private static final Duration MAXIMUM_LEASE_DURATION = Duration.ofMinutes(10);
    private static final Duration MAXIMUM_ERROR_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_SHUTDOWN_GRACE = Duration.ofMinutes(10);
    private static final Duration MINIMUM_HEALTH_PROBE_INTERVAL = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_HEALTH_PROBE_INTERVAL = Duration.ofMinutes(5);

    public CilExecConfig {
        if (instanceName == null || instanceName.isBlank()) {
            throw new ConfigException("instanceName must not be blank");
        }
        instanceName = instanceName.trim();
        if (instanceName.length() > 128) {
            throw new ConfigException("instanceName must contain at most 128 characters");
        }
        Objects.requireNonNull(runtimeDatabase, "runtimeDatabase");
        Objects.requireNonNull(effectDatabase, "effectDatabase");
        Objects.requireNonNull(migratorDatabase, "migratorDatabase");
        Objects.requireNonNull(exporterDatabase, "exporterDatabase");
        leaseDuration = bounded(leaseDuration, Duration.ofSeconds(1),
                MAXIMUM_LEASE_DURATION, "leaseDuration");
        schedulerErrorBackoff = bounded(schedulerErrorBackoff, Duration.ofMillis(1),
                MAXIMUM_ERROR_BACKOFF, "schedulerErrorBackoff");
        effectErrorBackoff = bounded(effectErrorBackoff, Duration.ofMillis(1),
                MAXIMUM_ERROR_BACKOFF, "effectErrorBackoff");
        shutdownGrace = bounded(shutdownGrace, Duration.ofSeconds(1),
                MAXIMUM_SHUTDOWN_GRACE, "shutdownGrace");
        healthDatabaseProbeInterval = bounded(healthDatabaseProbeInterval,
                MINIMUM_HEALTH_PROBE_INTERVAL, MAXIMUM_HEALTH_PROBE_INTERVAL,
                "healthDatabaseProbeInterval");
        if (schedulerWorkers < 1 || effectWorkers < 1) {
            throw new ConfigException("Worker counts must be positive");
        }
        if (schedulerWorkers > MAXIMUM_WORKERS || effectWorkers > MAXIMUM_WORKERS) {
            throw new ConfigException("Worker counts must not exceed " + MAXIMUM_WORKERS);
        }
        if (schedulerWorkers + effectWorkers + 2 > runtimeDatabase.maximumPoolSize()) {
            throw new ConfigException("Runtime pool must reserve connections beyond scheduler and "
                    + "effect workers: need at least " + (schedulerWorkers + effectWorkers + 2)
                    + ", got " + runtimeDatabase.maximumPoolSize() + "; increase runtime.pool.max");
        }
        if (effectWorkers > effectDatabase.maximumPoolSize()) {
            throw new ConfigException("Effect workers exceed their connection pool");
        }
        if (healthPort < 1 || healthPort > 65_535) {
            throw new ConfigException("healthPort is outside 1..65535");
        }
    }

    public static CilExecConfig load() {
        return load(System.getenv());
    }

    static CilExecConfig load(Map<String, String> environment) {
        Properties defaults = loadDefaults();
        String url = setting(environment, defaults, "CILEXEC_DATABASE_URL", "database.url");
        Duration connect = duration(environment, defaults, "CILEXEC_DATABASE_CONNECTION_TIMEOUT",
                "database.connection-timeout");
        Duration validate = duration(environment, defaults, "CILEXEC_DATABASE_VALIDATION_TIMEOUT",
                "database.validation-timeout");
        Duration statement = duration(environment, defaults, "CILEXEC_DATABASE_STATEMENT_TIMEOUT",
                "database.statement-timeout");
        Optional<Path> sslRootCertificate = optionalPath(environment, defaults,
                "CILEXEC_DATABASE_SSL_ROOT_CERTIFICATE_FILE",
                "database.ssl-root-certificate-file");

        DatabaseConfig runtime = database(environment, defaults, "runtime", url,
                sslRootCertificate, connect, validate, statement, false);
        DatabaseConfig effect = database(environment, defaults, "effect", url,
                sslRootCertificate, connect, validate, statement, false);
        DatabaseConfig migrator = database(environment, defaults, "migrator", url,
                sslRootCertificate, connect, validate, statement, false);
        DatabaseConfig exporter = database(environment, defaults, "exporter", url,
                sslRootCertificate, connect, validate, statement, true);
        return new CilExecConfig(
                setting(environment, defaults, "CILEXEC_INSTANCE_NAME", "instance.name"),
                longValue(environment, defaults, "CILEXEC_ADVISORY_LOCK_KEY", "instance.lock-key"),
                runtime,
                effect,
                migrator,
                exporter,
                integer(environment, defaults, "CILEXEC_SCHEDULER_WORKERS", "scheduler.workers"),
                integer(environment, defaults, "CILEXEC_EFFECT_WORKERS", "effect.workers"),
                duration(environment, defaults, "CILEXEC_LEASE_DURATION", "scheduler.lease-duration"),
                duration(environment, defaults, "CILEXEC_SCHEDULER_ERROR_BACKOFF", "scheduler.error-backoff"),
                duration(environment, defaults, "CILEXEC_EFFECT_ERROR_BACKOFF", "effect.error-backoff"),
                duration(environment, defaults, "CILEXEC_SHUTDOWN_GRACE", "runtime.shutdown-grace"),
                duration(environment, defaults, "CILEXEC_HEALTH_DATABASE_PROBE_INTERVAL",
                        "health.database-probe-interval"),
                integer(environment, defaults, "CILEXEC_HEALTH_PORT", "health.port"),
                bool(environment, defaults, "CILEXEC_MIGRATE_ON_START", "database.migrate-on-start")
        );
    }

    private static DatabaseConfig database(Map<String, String> env, Properties defaults, String role,
                                           String url, Optional<Path> sslRootCertificate,
                                           Duration connect, Duration validate, Duration statement,
                                           boolean readOnly) {
        String upper = role.toUpperCase();
        return new DatabaseConfig(
                url,
                setting(env, defaults, "CILEXEC_" + upper + "_DATABASE_USER", role + ".database.user"),
                Path.of(setting(env, defaults, "CILEXEC_" + upper + "_DATABASE_PASSWORD_FILE",
                        role + ".database.password-file")),
                sslRootCertificate,
                integer(env, defaults, "CILEXEC_" + upper + "_POOL_MAX", role + ".pool.max"),
                integer(env, defaults, "CILEXEC_" + upper + "_POOL_MIN_IDLE", role + ".pool.min-idle"),
                connect,
                validate,
                statement,
                "cilexec-" + role,
                readOnly
        );
    }

    private static Optional<Path> optionalPath(Map<String, String> env, Properties defaults,
                                               String envName, String key) {
        String value = env.containsKey(envName) ? env.get(envName) : defaults.getProperty(key);
        if (value == null || value.isBlank()) return Optional.empty();
        return Optional.of(Path.of(value.trim()));
    }

    private static Properties loadDefaults() {
        Properties properties = new Properties();
        try (InputStream input = CilExecConfig.class.getResourceAsStream("/cilexec-defaults.properties")) {
            if (input == null) {
                throw new ConfigException("Missing cilexec-defaults.properties");
            }
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            throw new ConfigException("Cannot load default configuration", exception);
        }
    }

    private static String setting(Map<String, String> env, Properties defaults, String envName, String key) {
        String value = env.get(envName);
        if (value == null || value.isBlank()) {
            value = defaults.getProperty(key);
        }
        if (value == null || value.isBlank()) {
            throw new ConfigException("Missing setting " + envName + " (" + key + ")");
        }
        return value.trim();
    }

    private static int integer(Map<String, String> env, Properties defaults, String envName, String key) {
        try {
            return Integer.parseInt(setting(env, defaults, envName, key));
        } catch (NumberFormatException exception) {
            throw new ConfigException("Invalid integer for " + envName, exception);
        }
    }

    private static long longValue(Map<String, String> env, Properties defaults, String envName, String key) {
        try {
            return Long.parseLong(setting(env, defaults, envName, key));
        } catch (NumberFormatException exception) {
            throw new ConfigException("Invalid long for " + envName, exception);
        }
    }

    private static Duration duration(Map<String, String> env, Properties defaults, String envName, String key) {
        try {
            return Duration.parse(setting(env, defaults, envName, key));
        } catch (RuntimeException exception) {
            throw new ConfigException("Invalid ISO-8601 duration for " + envName, exception);
        }
    }

    private static boolean bool(Map<String, String> env, Properties defaults, String envName, String key) {
        String value = setting(env, defaults, envName, key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new ConfigException("Invalid boolean for " + envName);
        }
        return Boolean.parseBoolean(value);
    }

    private static Duration bounded(Duration value, Duration minimum, Duration maximum,
                                    String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new ConfigException(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }
}
