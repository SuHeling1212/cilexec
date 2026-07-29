package com.follarce.persistence.postgres.connection;

import com.follarce.persistence.postgres.error.SqlStateClassifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        String sql = "SELECT current_setting('server_version_num')::integer, "
                + "(SELECT version FROM flyway.flyway_schema_history WHERE success "
                + "ORDER BY installed_rank DESC LIMIT 1)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("Database version query returned no row");
            }
            requireSecurePostgresql(rows.getInt(1));
            if (rows.getString(2) == null) {
                throw new IllegalStateException("Database has no successful CilExec migration");
            }
            int version = Integer.parseInt(rows.getString(2));
            if (version < minimum || version > maximum) {
                throw new IllegalStateException("Database schema " + version
                        + " is outside supported range " + minimum + ".." + maximum);
            }
            try (PreparedStatement invariants = connection.prepareStatement(
                    "SELECT meta.assert_security_invariants()")) {
                invariants.execute();
            }
            return version;
        } catch (SQLException exception) {
            throw SqlStateClassifier.classify("schema.verify", exception);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Flyway schema version is not numeric", exception);
        }
    }

    static void requireSecurePostgresql(int versionNumber) {
        if (versionNumber < MINIMUM_POSTGRESQL_VERSION) {
            throw new IllegalStateException("PostgreSQL server " + versionNumber
                    + " is unsupported; CilExec requires PostgreSQL 17.1 or newer");
        }
    }
}
