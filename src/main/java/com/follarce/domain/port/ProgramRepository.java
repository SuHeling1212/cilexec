package com.follarce.domain.port;

import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.ObjectHash;

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
}
