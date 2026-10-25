package com.follarce.persistence.postgres.connection;

import com.follarce.persistence.postgres.error.SqlStateClassifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/** Runtime-side read-only schema check; it never needs migrator credentials. */
public final class SchemaVerifier {
    static final int MINIMUM_POSTGRESQL_VERSION = 170001;
    private final DataSource dataSource;
    private final int minimum;
    private final int maximum;

    public SchemaVerifier(DataSource dataSource, int minimum, int maximum) {
        this.dataSource = dataSource;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public int verify() {
        try (Connection connection = dataSource.getConnection()) {
            requireSecurePostgresql(serverVersion(connection));
            DatabaseFormatFence.Inspection inspection;
            try {
                inspection = DatabaseFormatFence.inspect(connection);
            } catch (SQLException failure) {
                throw SqlStateClassifier.classify("schema.inspect", failure);
            }
            DatabaseFormatFence.Decision decision =
                    DatabaseFormatFence.classify(inspection, minimum, maximum);
            if (decision == DatabaseFormatFence.Decision.FRESH) {
                throw new IllegalStateException(
                        DatabaseFormatFence.missingMigrationMessage(minimum));
            }
            if (decision != DatabaseFormatFence.Decision.CURRENT) {
                // Refusal happens before this Runtime writes instance, boot, or audit rows.
                throw new DatabaseFormatFence.RefusalException(
                        DatabaseFormatFence.refusalMessage(decision, inspection, minimum, maximum));
            }
            try (PreparedStatement invariants = connection.prepareStatement(
                    "SELECT meta.assert_security_invariants()")) {
                invariants.execute();
            }
            return inspection.latestSuccessfulVersion();
        } catch (SQLException exception) {
            throw SqlStateClassifier.classify("schema.verify", exception);
        }
    }

    private static int serverVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT current_setting('server_version_num')::integer")) {
            if (!rows.next()) {
                throw new IllegalStateException("Database version query returned no row");
            }
            return rows.getInt(1);
        }
    }

    static void requireSecurePostgresql(int versionNumber) {
        if (versionNumber < MINIMUM_POSTGRESQL_VERSION) {
            throw new IllegalStateException("PostgreSQL server " + versionNumber
                    + " is unsupported; CilExec requires PostgreSQL 17.1 or newer");
        }
    }
}
