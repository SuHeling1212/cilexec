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
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Compiles immutable FCL programs and persists both source and runtime format objects. */
public final class ProgramService {
    public static final String LANGUAGE_VERSION = "fcl-1";
    public static final String SOURCE_MEDIA_TYPE = "text/x-fcl; charset=utf-8";
    public static final String COMPILED_MEDIA_TYPE =
            "application/vnd.cilexec.fcl-program+json; version=1";

    private final UserTransactionExecutor transactions;
    private final FclCompiler compiler;
    private final FclProgramCodec programCodec;
    private final FclSourceIncludes includes = new FclSourceIncludes();
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ProgramService(UserTransactionExecutor transactions) {
        this(transactions, new FclCompiler(), new FclProgramCodec(), Clock.systemUTC(),
                UUID::randomUUID);
    }

    public ProgramService(UserTransactionExecutor transactions, FclCompiler compiler,
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
     * Compilation happens before opening the short database transaction.
     */
    public Program create(UUID ownerId, String source) {
        return create(ownerId, source, "/");
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

    private Program compileAndSave(com.follarce.domain.port.TransactionContext transaction,
                                   String source) {
        FclProgram compiled = compiler.compile(source);
        StoredObject sourceObject = object(source.getBytes(StandardCharsets.UTF_8),
                SOURCE_MEDIA_TYPE);
        if (!sourceObject.objectHash().value().equals(compiled.sourceHash())) {
            throw new IllegalStateException("Compiler and object store disagree on source hash");
        }
        StoredObject compiledObject = object(
                programCodec.toJson(compiled).getBytes(StandardCharsets.UTF_8),
                COMPILED_MEDIA_TYPE);
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
