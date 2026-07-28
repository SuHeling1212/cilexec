package com.follarce.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Complete validated runtime configuration; passwords remain in mounted secret files. */
public record CilExecConfig(
        String instanceName,
        long advisoryLockKey,
        DatabaseConfig runtimeDatabase,
        DatabaseConfig effectDatabase,
        DatabaseConfig migratorDatabase,
        int schedulerWorkers,
        int effectWorkers,
        Duration leaseDuration,
        Duration heartbeatInterval,
        Duration schedulerIdlePoll,
        Duration effectIdlePoll,
        Duration shutdownGrace,
        int healthPort,
        boolean migrateOnStart
) {
    public CilExecConfig {
        if (instanceName == null || instanceName.isBlank()) {
            throw new ConfigException("instanceName must not be blank");
        }
        Objects.requireNonNull(runtimeDatabase, "runtimeDatabase");
        Objects.requireNonNull(effectDatabase, "effectDatabase");
        Objects.requireNonNull(migratorDatabase, "migratorDatabase");
        leaseDuration = positive(leaseDuration, "leaseDuration");
        heartbeatInterval = positive(heartbeatInterval, "heartbeatInterval");
        schedulerIdlePoll = positive(schedulerIdlePoll, "schedulerIdlePoll");
        effectIdlePoll = positive(effectIdlePoll, "effectIdlePoll");
        shutdownGrace = positive(shutdownGrace, "shutdownGrace");
        if (schedulerWorkers < 1 || effectWorkers < 1) {
            throw new ConfigException("Worker counts must be positive");
        }
        if (schedulerWorkers + 2 > runtimeDatabase.maximumPoolSize()) {
            throw new ConfigException("Runtime pool must reserve connections beyond scheduler workers");
        }
        if (effectWorkers > effectDatabase.maximumPoolSize()) {
            throw new ConfigException("Effect workers exceed their connection pool");
        }
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new ConfigException("Heartbeat interval must be shorter than lease duration");
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

        DatabaseConfig runtime = database(environment, defaults, "runtime", url, connect, validate);
        DatabaseConfig effect = database(environment, defaults, "effect", url, connect, validate);
        DatabaseConfig migrator = database(environment, defaults, "migrator", url, connect, validate);
        return new CilExecConfig(
                setting(environment, defaults, "CILEXEC_INSTANCE_NAME", "instance.name"),
                longValue(environment, defaults, "CILEXEC_ADVISORY_LOCK_KEY", "instance.lock-key"),
                runtime,
                effect,
                migrator,
                integer(environment, defaults, "CILEXEC_SCHEDULER_WORKERS", "scheduler.workers"),
                integer(environment, defaults, "CILEXEC_EFFECT_WORKERS", "effect.workers"),
                duration(environment, defaults, "CILEXEC_LEASE_DURATION", "scheduler.lease-duration"),
                duration(environment, defaults, "CILEXEC_HEARTBEAT_INTERVAL", "scheduler.heartbeat-interval"),
                duration(environment, defaults, "CILEXEC_SCHEDULER_IDLE_POLL", "scheduler.idle-poll"),
                duration(environment, defaults, "CILEXEC_EFFECT_IDLE_POLL", "effect.idle-poll"),
                duration(environment, defaults, "CILEXEC_SHUTDOWN_GRACE", "runtime.shutdown-grace"),
                integer(environment, defaults, "CILEXEC_HEALTH_PORT", "health.port"),
                bool(environment, defaults, "CILEXEC_MIGRATE_ON_START", "database.migrate-on-start")
        );
    }

    private static DatabaseConfig database(Map<String, String> env, Properties defaults, String role,
                                           String url, Duration connect, Duration validate) {
        String upper = role.toUpperCase();
        return new DatabaseConfig(
                url,
                setting(env, defaults, "CILEXEC_" + upper + "_DATABASE_USER", role + ".database.user"),
                Path.of(setting(env, defaults, "CILEXEC_" + upper + "_DATABASE_PASSWORD_FILE",
                        role + ".database.password-file")),
                integer(env, defaults, "CILEXEC_" + upper + "_POOL_MAX", role + ".pool.max"),
                integer(env, defaults, "CILEXEC_" + upper + "_POOL_MIN_IDLE", role + ".pool.min-idle"),
                connect,
                validate,
                "cilexec-" + role
        );
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

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new ConfigException(name + " must be positive");
        }
        return value;
    }
}
