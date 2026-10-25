package com.follarce.persistence.postgres.connection;

import com.follarce.version.ReleaseVersion;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Read-only decision fence for the CilExec 0.0.5 database format.
 *
 * <p>The fence never creates, migrates, repairs, or deletes data. It classifies the database as a
 * genuinely empty first initialization, an already current 0.0.5 database, or persistent data that
 * belongs to another CilExec format. Old or unknown data are refused before Flyway applies any
 * migration and before the Runtime writes instance or audit rows, so an existing database is never
 * upgraded in place.
 */
public final class DatabaseFormatFence {
    private DatabaseFormatFence() {
    }

    /** Classification of one database's CilExec format identity. */
    public enum Decision {
        /** Empty database without CilExec history: the first 0.0.5 initialization may run. */
        FRESH,
        /** CilExec history within the supported schema range: migration and startup may continue. */
        CURRENT,
        /** CilExec history older than the supported range: old data are never migrated. */
        LEGACY,
        /** Persistent data without any CilExec migration history: unknown or old data. */
        UNKNOWN,
        /** CilExec history newer than the supported range: downgrades are forbidden. */
        FUTURE,
        /** CilExec history contains failed migrations: the database is not usable as-is. */
        FAILED
    }

    /** One read-only snapshot of a database's format identity. */
    public record Inspection(
            boolean cilExecHistoryPresent,
            Integer latestSuccessfulVersion,
            boolean failedMigrationPresent,
            boolean persistentDataPresent
    ) {
        public Inspection {
            if (!cilExecHistoryPresent && latestSuccessfulVersion != null) {
                throw new IllegalArgumentException(
                        "A database without CilExec history has no successful version");
            }
        }
    }

    /** Signals that persistent data belong to another CilExec format and must not be touched. */
    public static final class RefusalException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public RefusalException(String message) {
            super(message);
        }
    }

    /** Reads the format identity without modifying the database. */
    public static Inspection inspect(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try (Statement statement = connection.createStatement()) {
            boolean historyPresent = booleanQuery(statement,
                    "SELECT to_regclass('flyway.flyway_schema_history') IS NOT NULL");
            if (!historyPresent) {
                return new Inspection(false, null, false, persistentDataPresent(statement));
            }
            Integer latest = null;
            try (ResultSet row = statement.executeQuery(
                    "SELECT max(version::integer) FROM flyway.flyway_schema_history "
                            + "WHERE success")) {
                if (row.next()) {
                    int value = row.getInt(1);
                    latest = row.wasNull() ? null : value;
                }
            }
            boolean failed = booleanQuery(statement,
                    "SELECT EXISTS (SELECT 1 FROM flyway.flyway_schema_history "
                            + "WHERE NOT success)");
            return new Inspection(true, latest, failed, false);
        }
    }

    /** Classifies a database snapshot against one supported schema range. */
    public static Decision classify(Inspection inspection, int minimumSchema, int maximumSchema) {
        Objects.requireNonNull(inspection, "inspection");
        if (minimumSchema < 1 || maximumSchema < minimumSchema) {
            throw new IllegalArgumentException("Invalid CilExec schema compatibility range");
        }
        if (!inspection.cilExecHistoryPresent()) {
            return inspection.persistentDataPresent() ? Decision.UNKNOWN : Decision.FRESH;
        }
        if (inspection.failedMigrationPresent() || inspection.latestSuccessfulVersion() == null) {
            return Decision.FAILED;
        }
        int version = inspection.latestSuccessfulVersion();
        if (version < minimumSchema) return Decision.LEGACY;
        if (version > maximumSchema) return Decision.FUTURE;
        return Decision.CURRENT;
    }

    /**
     * Refuses old, unknown, newer, or failed data before Flyway applies any migration.
     *
     * @param requiredSchema the exact schema this release initializes and accepts
     */
    public static void requireMigratable(Inspection inspection, int requiredSchema) {
        Decision decision = classify(inspection, requiredSchema, requiredSchema);
        if (decision == Decision.CURRENT || decision == Decision.FRESH) return;
        throw new RefusalException(
                refusalMessage(decision, inspection, requiredSchema, requiredSchema));
    }

    /** Operator-facing explanation for a rejected database; the data are never modified. */
    public static String refusalMessage(Decision decision, Inspection inspection,
                                        int minimumSchema, int maximumSchema) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(inspection, "inspection");
        String release = releaseIdentity();
        return switch (decision) {
            case LEGACY -> "Existing database schema " + inspection.latestSuccessfulVersion()
                    + " is not " + release + " (required schema " + minimumSchema + "); "
                    + "old data are neither migrated nor deleted. Use a fresh database";
            case UNKNOWN -> "Existing database contains data but has no CilExec migration "
                    + "history; it is not " + release + ". Old data are neither migrated nor "
                    + "deleted. Use a fresh database";
            case FUTURE -> "Existing database schema " + inspection.latestSuccessfulVersion()
                    + " is newer than " + release + " (supported schema " + maximumSchema + "); "
                    + "downgrades are not supported. Use the CilExec release that matches the data";
            case FAILED -> "Existing database has failed CilExec migrations; it is not " + release
                    + ". Old data are neither migrated nor deleted. Restore a matching backup or "
                    + "use a fresh database";
            case FRESH, CURRENT -> throw new IllegalArgumentException(
                    "Only rejected database formats have a refusal message");
        };
    }

    /** Explanation for an empty database that this Runtime was not allowed to migrate. */
    public static String missingMigrationMessage(int minimumSchema) {
        return "Database has no successful " + releaseIdentity() + " migration; run the migrate "
                + "command or enable database.migrate-on-start (required schema "
                + minimumSchema + ")";
    }

    private static boolean booleanQuery(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            return row.next() && row.getBoolean(1);
        }
    }

    /**
     * Detects persistent data owned by any application rather than PostgreSQL internals. A
     * genuinely fresh database (possibly already prepared with an empty {@code flyway} schema)
     * has no non-system namespace besides {@code public}/{@code flyway} and no relations at all.
     */
    private static boolean persistentDataPresent(Statement statement) throws SQLException {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_namespace AS namespace
                    WHERE namespace.nspname NOT IN
                        ('pg_catalog', 'information_schema', 'public', 'flyway')
                      AND namespace.nspname NOT LIKE 'pg\\_%'
                ) OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_class AS relation
                    JOIN pg_catalog.pg_namespace AS namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname NOT IN
                        ('pg_catalog', 'information_schema', 'flyway')
                      AND namespace.nspname NOT LIKE 'pg\\_%'
                      AND relation.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                )
                """;
        return booleanQuery(statement, sql);
    }

    private static String releaseIdentity() {
        return "CilExec " + ReleaseVersion.current();
    }
}
