package com.follarce.persistence.postgres.repository;

import com.follarce.domain.port.ProgramRepository;
import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public final class JdbcProgramRepository extends JdbcRepositorySupport implements ProgramRepository {
    private static final String COLUMNS = "program_id, program_hash, language_version, runtime_format_version, "
            + "source_object_hash, compiled_object_hash, statement_count, created_at";

    public JdbcProgramRepository(Connection connection) {
        super(connection);
    }

    @Override
    public Optional<Program> findById(UUID programId) {
        return find("program.findById", "SELECT " + COLUMNS + " FROM program.program WHERE program_id=?",
                statement -> statement.setObject(1, programId));
    }

    @Override
    public Optional<Program> findByIdentity(ObjectHash programHash, String languageVersion,
                                            int runtimeFormatVersion) {
        return find("program.findByIdentity", "SELECT " + COLUMNS
                        + " FROM program.program WHERE program_hash=? AND language_version=? "
                        + "AND runtime_format_version=?",
                statement -> {
                    statement.setBytes(1, JdbcValues.hash(programHash));
                    statement.setString(2, languageVersion);
                    statement.setInt(3, runtimeFormatVersion);
                });
    }

    @Override
    public Program saveIfAbsent(Program program) {
        String sql = "INSERT INTO program.program(program_id, owner_id, program_hash, language_version, "
                + "runtime_format_version, source_object_hash, compiled_object_hash, statement_count, created_at) "
                + "SELECT ?, auth.current_cilexec_user_id(), ?, ?, ?, ?, ?, ?, ? "
                + "ON CONFLICT (owner_id, program_hash, language_version, runtime_format_version) DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, program.programId());
            statement.setBytes(2, JdbcValues.hash(program.programHash()));
            statement.setString(3, program.languageVersion());
            statement.setInt(4, program.runtimeFormatVersion());
            statement.setBytes(5, JdbcValues.hash(program.sourceObjectHash()));
            if (program.compiledObjectHash().isPresent()) {
                statement.setBytes(6, JdbcValues.hash(program.compiledObjectHash().get()));
            } else {
                statement.setNull(6, java.sql.Types.BINARY);
            }
            statement.setInt(7, program.statementCount());
            statement.setTimestamp(8, java.sql.Timestamp.from(program.createdAt()));
            if (statement.executeUpdate() == 1) {
                return program;
            }
            return findByIdentity(program.programHash(), program.languageVersion(),
                    program.runtimeFormatVersion()).orElseThrow(
                    () -> new IllegalStateException("Program conflict did not expose existing row"));
        } catch (SQLException exception) {
            throw failure("program.saveIfAbsent", exception);
        }
    }

    private Optional<Program> find(String operation, String sql, Binder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure(operation, exception);
        }
    }

    private static Program map(ResultSet rows) throws SQLException {
        byte[] compiled = rows.getBytes("compiled_object_hash");
        return new Program(
                rows.getObject("program_id", UUID.class),
                JdbcValues.hash(rows.getBytes("program_hash")),
                rows.getString("language_version"),
                rows.getInt("runtime_format_version"),
                JdbcValues.hash(rows.getBytes("source_object_hash")),
                compiled == null ? Optional.empty() : Optional.of(JdbcValues.hash(compiled)),
                rows.getInt("statement_count"),
                rows.getTimestamp("created_at").toInstant()
        );
    }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement statement) throws SQLException; }
}
