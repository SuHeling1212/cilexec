package com.follarce.persistence.postgres.connection;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

public final class DatabaseHealth {
    private final DataSource dataSource;

    public DatabaseHealth(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isAvailable() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            boolean available = result.next() && result.getInt(1) == 1;
            connection.rollback();
            return available;
        } catch (SQLException exception) {
            return false;
        }
    }
}
