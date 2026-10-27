package com.follarce.persistence.postgres.connection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Transactional, idempotent pre-release V005 schema patch for existing 0.0.5 data. */
final class V005Patch {
    private static final String RESOURCE = "db/contracts/V005_patch.sql";

    private V005Patch() { }

    static void apply(Connection connection) {
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT to_regclass('program.function_cache') IS NOT NULL")) {
                if (rows.next() && rows.getBoolean(1)) {
                    connection.rollback();
                    return;
                }
            }
            try (InputStream input = V005Patch.class.getClassLoader()
                    .getResourceAsStream(RESOURCE)) {
                if (input == null) throw new IllegalStateException("Missing " + RESOURCE);
                String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            connection.commit();
        } catch (SQLException | IOException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("Cannot apply the V005 schema patch", failure);
        }
    }
}
