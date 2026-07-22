package com.follarce.domain.port;

import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.ObjectHash;

import java.util.Optional;
import java.util.UUID;

public interface ProgramRepository {
    Optional<Program> findById(UUID programId);

    Optional<Program> findByIdentity(
            ObjectHash programHash,
            String languageVersion,
            int runtimeFormatVersion
    );

    Program saveIfAbsent(Program program);
}
