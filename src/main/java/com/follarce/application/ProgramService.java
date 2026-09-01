package com.follarce.application;

import com.follarce.domain.port.Isolation;
import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclInstruction;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.domain.port.UserTransactionRunner;
import com.follarce.version.ReleaseVersion;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Persists immutable FCL programs, compiling only when their source identity is new. */
public final class ProgramService {
    public static final String LANGUAGE_VERSION = "fcl-" + ReleaseVersion.current();
    /** Read compatibility for packages published before the current source-language release. */
    public static final Set<String> LEGACY_LANGUAGE_VERSIONS = Set.of(
            "fcl-0.0.2", "fcl-0.0.3");
    public static final String SOURCE_MEDIA_TYPE = "text/x-fcl; charset=utf-8";
    public static final String COMPILED_MEDIA_TYPE =
            "application/vnd.cilexec.fcl-program; version=" + FclProgramCodec.FORMAT_VERSION;

    private final UserTransactionRunner transactions;
    private final FclCompiler compiler;
    private final FclProgramCodec programCodec;
    private final FclSourceIncludes includes = new FclSourceIncludes();
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ProgramService(UserTransactionRunner transactions) {
        this(transactions, new FclCompiler(), new FclProgramCodec(), Clock.systemUTC(),
                UUID::randomUUID);
    }

    public ProgramService(UserTransactionRunner transactions, FclCompiler compiler,
                          FclProgramCodec programCodec, Clock clock,
                          Supplier<UUID> identifiers) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.programCodec = Objects.requireNonNull(programCodec, "programCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    /**
     * Creates or returns the owner's identical immutable program.
     */
    public Program create(UUID ownerId, String source) {
        return create(ownerId, source, "/");
    }

    /**
     * V003 and V004 did not invalidate the V002 source language, so their packages remain
     * import-compatible. This allowlist is deliberately explicit: a future source-language
     * change must opt in after compatibility has been reviewed.
     */
    public static boolean compatiblePackageLanguage(String packageLanguage, String programLanguage) {
        return packageLanguage.equals(programLanguage)
                || (LANGUAGE_VERSION.equals(programLanguage)
                && LEGACY_LANGUAGE_VERSIONS.contains(packageLanguage));
    }

    public Program create(UUID ownerId, String source, String workingDirectory) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(workingDirectory, "workingDirectory");

        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                compileAndSave(transaction, includes.expand(transaction, ownerId, source,
                        workingDirectory)));
    }

    String expandIncludes(UUID ownerId, String source, String workingDirectory) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                includes.expand(transaction, ownerId, source, workingDirectory));
    }

    Program createExpanded(UUID ownerId, String expandedSource) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(expandedSource, "expandedSource");
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> compileAndSave(transaction, expandedSource));
    }

    /**
     * Compiles and persists the program inside the caller's already-open transaction.
     * Nested independent transactions are unsafe here: a SERIALIZABLE caller snapshot
     * taken before the nested commit cannot see the new program row, so a later
     * process insert referencing it fails the foreign key (SQLSTATE 23503).
     */
    Program compileAndSaveIn(com.follarce.domain.port.TransactionContext transaction,
                             String expandedSource) {
        Objects.requireNonNull(expandedSource, "expandedSource");
        return compileAndSave(transaction, expandedSource);
    }

    private Program compileAndSave(com.follarce.domain.port.TransactionContext transaction,
                                   String source) {
        StoredObject sourceObject = object(source.getBytes(StandardCharsets.UTF_8),
                SOURCE_MEDIA_TYPE);
        Optional<Program> existing = transaction.programs().findByIdentity(
                sourceObject.objectHash(), LANGUAGE_VERSION, FclProgramCodec.FORMAT_VERSION);
        if (existing.isPresent()) return existing.get();

        FclProgram compiled = compiler.compile(source);
        if (!sourceObject.objectHash().value().equals(compiled.sourceHash())) {
            throw new IllegalStateException("Compiler and object store disagree on source hash");
        }
        StoredObject compiledObject = object(programCodec.toBytes(compiled), COMPILED_MEDIA_TYPE);
        Program candidate = new Program(Objects.requireNonNull(identifiers.get(),
                "identifier supplier returned null"), new ObjectHash(compiled.sourceHash()),
                LANGUAGE_VERSION, FclProgramCodec.FORMAT_VERSION, sourceObject.objectHash(),
                Optional.of(compiledObject.objectHash()), semanticStatementCount(compiled),
                sourceObject.createdAt());

        transaction.vfs().saveObject(sourceObject);
        transaction.vfs().saveObject(compiledObject);
        return transaction.programs().saveIfAbsent(candidate);
    }

    private StoredObject object(byte[] bytes, String mediaType) {
        Instant now = clock.instant();
        return StoredObject.create(new BinaryContent(bytes), mediaType, now);
    }

    private static int semanticStatementCount(FclProgram program) {
        return Math.toIntExact(program.instructions().stream()
                .filter(instruction -> !(instruction instanceof FclInstruction.Jump))
                .count());
    }
}
