package com.follarce.domain.program;

import com.follarce.domain.Invariant;
import com.follarce.domain.vfs.ObjectHash;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Immutable identity and metadata for shared FCL code. */
public record Program(
        UUID programId,
        ObjectHash programHash,
        String languageVersion,
        int runtimeFormatVersion,
        ObjectHash sourceObjectHash,
        Optional<ObjectHash> compiledObjectHash,
        int statementCount,
        Instant createdAt
) {
    public Program {
        Invariant.required(programId, "programId");
        Invariant.required(programHash, "programHash");
        languageVersion = Invariant.text(languageVersion, "languageVersion");
        Invariant.positive(runtimeFormatVersion, "runtimeFormatVersion");
        Invariant.required(sourceObjectHash, "sourceObjectHash");
        compiledObjectHash = Invariant.required(compiledObjectHash, "compiledObjectHash");
        Invariant.nonNegative(statementCount, "statementCount");
        Invariant.required(createdAt, "createdAt");
    }

    public boolean hasSameIdentity(Program other) {
        return other != null
                && programHash.equals(other.programHash)
                && languageVersion.equals(other.languageVersion)
                && runtimeFormatVersion == other.runtimeFormatVersion;
    }
}
