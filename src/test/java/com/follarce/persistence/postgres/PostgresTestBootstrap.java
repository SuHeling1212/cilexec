package com.follarce.persistence.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Creates the exact cluster-role contract required before the frozen V001 migration runs. */
public final class PostgresTestBootstrap {
    public static final String MIGRATOR_ROLE = "cilexec_migrator";
    public static final String DEFAULT_PASSWORD = "cilexec-integration-test-password";

    private PostgresTestBootstrap() { }

    public static void createServiceRoles(Connection connection) throws SQLException {
        createServiceRoles(connection, DEFAULT_PASSWORD);
    }

    public static void createServiceRoles(Connection connection, String password)
            throws SQLException {
        String literal = password.replace("'", "''");
        String database = connection.getCatalog().replace("\"", "\"\"");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE cilexec_owner NOLOGIN INHERIT");
            statement.execute("CREATE ROLE cilexec_migrator LOGIN INHERIT CREATEROLE PASSWORD '"
                    + literal + "'");
            statement.execute("CREATE ROLE cilexec_runtime LOGIN NOINHERIT PASSWORD '"
                    + literal + "'");
            statement.execute("CREATE ROLE cilexec_effect_worker LOGIN NOINHERIT PASSWORD '"
                    + literal + "'");
            statement.execute("CREATE ROLE cilexec_readonly LOGIN NOINHERIT PASSWORD '"
                    + literal + "'");
            statement.execute("CREATE ROLE cilexec_exporter LOGIN NOINHERIT PASSWORD '"
                    + literal + "'");
            statement.execute("ALTER ROLE cilexec_readonly SET default_transaction_read_only TO on");
            statement.execute("ALTER ROLE cilexec_exporter SET default_transaction_read_only TO on");
            statement.execute("GRANT cilexec_owner TO cilexec_migrator");
            statement.execute("ALTER DATABASE \"" + database + "\" OWNER TO cilexec_owner");
            statement.execute("REVOKE ALL ON DATABASE \"" + database + "\" FROM PUBLIC");
            statement.execute("GRANT CONNECT ON DATABASE \"" + database + "\" TO "
                    + "cilexec_migrator, cilexec_runtime, cilexec_effect_worker, "
                    + "cilexec_readonly, cilexec_exporter");
            statement.execute("CREATE SCHEMA flyway AUTHORIZATION cilexec_migrator");
            statement.execute("REVOKE ALL ON SCHEMA flyway FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA flyway TO cilexec_migrator");
        }
    }
}
