package com.follarce.domain.port;

import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.ObjectHash;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ProgramRepository {
    Optional<Program> findById(UUID programId);

    /** Finds the current principal's immutable program with this complete identity. */
    Optional<Program> findByIdentity(
            ObjectHash programHash,
            String languageVersion,
            int runtimeFormatVersion
    );

    Program saveIfAbsent(Program program);

    /** Optional persistent acceleration for name-independent function bodies. */
    default Optional<byte[]> findFunctionBody(ObjectHash structuralHash, int formatVersion) {
        return Optional.empty();
    }

    /** Concurrent inserts of the same immutable body are equivalent. */
    default void saveFunctionBody(ObjectHash structuralHash, int formatVersion, byte[] body) { }

    /**
     * Explicit administrator removal of one program that no process references and no
     * other program imports; otherwise returns a reference report without removing it.
     * The returned map carries {@code removed}, {@code processCount},
     * {@code importedByCount}, {@code processes}, and {@code importedBy}.
     */
    default Map<String, Object> removeByAdministrator(
            UUID administratorId, UUID programId, UUID auditEventId, Instant at) {
        throw new UnsupportedOperationException("Program removal is not implemented");
    }
}
