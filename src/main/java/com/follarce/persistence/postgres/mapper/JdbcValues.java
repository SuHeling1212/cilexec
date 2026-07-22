package com.follarce.persistence.postgres.mapper;

import com.follarce.domain.vfs.ObjectHash;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.util.PGobject;

public final class JdbcValues {
    private JdbcValues() {
    }

    public static byte[] hash(ObjectHash hash) {
        return HexFormat.of().parseHex(hash.value());
    }

    public static ObjectHash hash(byte[] bytes) {
        return new ObjectHash(HexFormat.of().formatHex(bytes));
    }

    public static PGobject json(String value) {
        try {
            PGobject object = new PGobject();
            object.setType("jsonb");
            object.setValue(value);
            return object;
        } catch (SQLException impossible) {
            throw new IllegalArgumentException("Invalid JSON value", impossible);
        }
    }

    public static Optional<UUID> optionalUuid(ResultSet rows, String column) throws SQLException {
        return Optional.ofNullable(rows.getObject(column, UUID.class));
    }

    public static Optional<Instant> optionalInstant(ResultSet rows, String column) throws SQLException {
        var timestamp = rows.getTimestamp(column);
        return timestamp == null ? Optional.empty() : Optional.of(timestamp.toInstant());
    }

    public static Optional<String> optionalString(ResultSet rows, String column) throws SQLException {
        return Optional.ofNullable(rows.getString(column));
    }

    public static void nullableUuid(PreparedStatement statement, int index, Optional<UUID> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setObject(index, value.get());
        } else {
            statement.setNull(index, java.sql.Types.OTHER);
        }
    }

    public static void nullableInstant(PreparedStatement statement, int index, Optional<Instant> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setTimestamp(index, java.sql.Timestamp.from(value.get()));
        } else {
            statement.setNull(index, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        }
    }
}
