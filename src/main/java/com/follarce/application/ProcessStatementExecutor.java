package com.follarce.application;

import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.packageinfo.PackageBinding;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.program.Program;
import com.follarce.domain.scheduler.SchedulerClaim;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.fcl.FclBuiltins;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclInstruction;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramLinker;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.scheduler.ClaimedProcessHandler;
import com.follarce.util.CommandTiming;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import com.follarce.package_manager.PackageEnvironments;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

/** Executes and durably commits exactly one FCL semantic step for a scheduler claim. */
public final class ProcessStatementExecutor implements ClaimedProcessHandler {
    private static final Set<String> BUILTIN_IMPORTS = Set.of(
            "math", "std.math", "util", "std.util", "path", "std.path", "term", "file",
            "io", "process", "user", "swapPool", "network", "socket", "package", "system");
    private final UserTransactionExecutor transactions;
    private final FclRuntime fixedRuntime;
    private final FclProgramCodec programCodec;
    private final FclPersistenceBridge continuationBridge;
    private final Clock clock;

    public ProcessStatementExecutor(UserTransactionExecutor transactions) {
        this(transactions, null, new FclProgramCodec(), new FclContinuationCodec(),
                Clock.systemUTC());
    }

    public ProcessStatementExecutor(UserTransactionExecutor transactions, FclRuntime runtime,
                                    FclProgramCodec programCodec,
                                    FclContinuationCodec continuationCodec,
                                    Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.fixedRuntime = runtime;
        this.programCodec = Objects.requireNonNull(programCodec, "programCodec");
        this.continuationBridge = new FclPersistenceBridge(
                Objects.requireNonNull(continuationCodec, "continuationCodec"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void executeOne(SchedulerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        Instant now = clock.instant();
        String[] traceId = {null};
        try {
            transactions.inUserTransaction(claim.ownerId(), Isolation.READ_COMMITTED, transaction -> {
            if (!claim.authorizes(claim.executionEpoch(), now)) {
                throw new StaleClaimException("Scheduler claim has expired");
            }
            if (!transaction.scheduler().heartbeat(claim)) {
                throw new StaleClaimException("Scheduler lease is no longer owned by this worker");
            }

            CilProcess current = transaction.processes().findByUid(claim.processUid())
                    .orElseThrow(() -> new StaleClaimException("Claimed process no longer exists"));
            validateClaim(current, claim);
            if (transaction.terminal().consumeInterrupt(current.identity().processUid())) {
                terminateAtSafePoint(transaction, current, claim, now);
                return null;
            }

            Program program = transaction.programs()
                    .findById(current.continuation().programId())
                    .orElseThrow(() -> new IllegalStateException("Process program no longer exists"));
            FclPersistenceBridge.ensureProgramIdentity(program, current.continuation());
            FclContinuation continuation = continuationBridge.restore(current.continuation());
            boolean terminalProcess = TerminalReplService.isTerminalProcess(continuation);
            traceId[0] = trace(continuation);
            CommandTiming.point(traceId[0], "scheduler.claimed pid="
                    + current.identity().pid());
            FclProgram compiled = loadProgram(transaction, program);
            CommandTiming.point(traceId[0], "executor.program.loaded");
            compiled = linkPackages(transaction, current, compiled, program);
            CommandTiming.point(traceId[0], "executor.packages.linked");

            FclRuntime statementRuntime = fixedRuntime != null ? fixedRuntime
                    : new FclRuntime(FclRuntimeFunctions.create(transaction, current, program,
                    continuation, now));
            FclStepResult step = statementRuntime.executeOne(compiled, continuation);
            CommandTiming.point(traceId[0], "executor.fcl.step.executed=" + step);
            Program committedProgram = program;
            Continuation previousForPersistence = current.continuation();
            ExecutionReplacement replacement = resolveExecutionReplacement(transaction,
                    continuation);
            if (replacement != null) {
                committedProgram = replacement.program();
                continuation = replacement.continuation();
                previousForPersistence = initialContinuation(committedProgram);
            }
            resolveDirective(transaction, current, continuation, now);
            deliverPendingTerminalInput(transaction, current, continuation, now);
            Continuation persisted = continuationBridge.persist(current.identity().processUid(),
                    committedProgram, previousForPersistence, continuation.snapshot());
            persisted = withPackageBindings(persisted,
                    transaction.packages().findProcessBindings(current.identity().processUid()));
            CilProcess.Status target = targetStatus(step, continuation, terminalProcess);
            CilProcess committed = current.commitStatement(persisted, target,
                    current.stateVersion(), claim.executionEpoch(), now);

            ProcessRepository.UpdateResult update = transaction.processes().updateClaimed(
                    committed, current.stateVersion(), claim);
            if (update == ProcessRepository.UpdateResult.EPOCH_FENCED) {
                throw new StaleClaimException("Statement commit was fenced by a newer execution epoch");
            }
            if (update != ProcessRepository.UpdateResult.UPDATED) {
                throw new StatementConflictException("Statement state version is stale");
            }

            // Queue state and continuation become visible in the same commit. READY is
            // re-queued; wait/terminal/failure states are removed by repository policy.
            transaction.scheduler().release(claim.processUid(), claim.executionEpoch());
            CommandTiming.point(traceId[0], "executor.state-written status=" + target);
            return null;
            });
            CommandTiming.point(traceId[0], "executor.transaction.committed");
        } catch (RuntimeException failure) {
            CommandTiming.point(traceId[0], "executor.failed="
                    + failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private static String trace(FclContinuation continuation) {
        if (!continuation.scope().contains(CommandTiming.SCOPE_KEY)) return null;
        Object value = continuation.scope().get(CommandTiming.SCOPE_KEY);
        return value instanceof String traceId ? traceId : null;
    }

    private FclProgram loadProgram(com.follarce.domain.port.TransactionContext transaction,
                                   Program program) {
        StoredObject sourceObject = transaction.vfs().findObject(program.sourceObjectHash())
                .orElseThrow(() -> new IllegalStateException("Program source object is missing"));
        String source = utf8(sourceObject, "program source");
        FclProgram decoded;
        if (program.compiledObjectHash().isPresent()) {
            StoredObject compiledObject = transaction.vfs()
                    .findObject(program.compiledObjectHash().orElseThrow())
                    .orElseThrow(() -> new IllegalStateException(
                            "Compiled program object is missing"));
            decoded = programCodec.fromJson(utf8(compiledObject, "compiled program"));
            if (!decoded.source().equals(source)) {
                throw new IllegalStateException(
                        "Compiled program does not match its source object");
            }
        } else {
            decoded = programCodec.decode(java.util.Map.of(
                    "formatVersion", FclProgramCodec.FORMAT_VERSION,
                    "source", source,
                    "sourceHash", program.programHash().value()));
        }
        if (!decoded.sourceHash().equals(program.programHash().value())) {
            throw new IllegalStateException("Loaded program hash does not match metadata");
        }
        if (FclProgramCodec.FORMAT_VERSION != program.runtimeFormatVersion()) {
            throw new IllegalStateException("Loaded program runtime format does not match metadata");
        }
        return decoded;
    }

    private static FclProgram linkPackages(
            com.follarce.domain.port.TransactionContext transaction,
            CilProcess process,
            FclProgram base,
            Program program
    ) {
        List<ImportSpec> imports = base.instructions().stream()
                .filter(FclInstruction.Import.class::isInstance)
                .map(FclInstruction.Import.class::cast)
                .filter(value -> !isBuiltinImport(normalizeImport(value.target())))
                .map(value -> new ImportSpec(normalizeImport(value.target()), value.alias(),
                        value.wildcard()))
                .toList();
        if (imports.isEmpty()) return base;
        Map<String, ProcessPackageBinding> bindings = new LinkedHashMap<>();
        transaction.packages().findProcessBindings(process.identity().processUid())
                .forEach(binding -> bindings.put(binding.importName(), binding));
        Map<String, FclProgramLinker.Module> modules = new LinkedHashMap<>();
        SqlitePackageReader reader = new SqlitePackageReader();
        for (Map.Entry<String, ProcessPackageBinding> entry : bindings.entrySet()) {
            List<ImportSpec> matching = imports.stream()
                    .filter(spec -> spec.target().equals(entry.getKey())).toList();
            if (matching.isEmpty()) continue;
            PackageRelease release = transaction.packages().findRelease(entry.getValue().packageHash())
                    .orElseThrow(() -> new IllegalStateException("Pinned package release is missing"));
            StoredObject database = transaction.vfs().findObject(release.databaseObjectHash())
                    .orElseThrow(() -> new IllegalStateException("Pinned package database is missing"));
            PackageDescriptor descriptor = reader.inspect(database.content().bytes());
            if (!descriptor.languageVersion().equals(program.languageVersion())) {
                throw new IllegalStateException("Package language version does not match program: "
                        + descriptor.coordinate());
            }
            String identity = release.packageHash().value().value();
            for (PackageIndex.Module module : descriptor.moduleIndex()) {
                byte[] sourceBytes = reader.readResource(database.content().bytes(),
                        module.objectPath());
                if (!ObjectHash.sha256(new com.follarce.domain.vfs.BinaryContent(sourceBytes))
                        .equals(module.hash())) {
                    throw new IllegalStateException("Package module hash mismatch: "
                            + module.name());
                }
                List<FclProgramLinker.Export> exports = publishedFunctions(descriptor, module,
                        matching);
                mergeModule(modules, new FclProgramLinker.Module(identity, module.name(),
                        utf8(sourceBytes, "package module " + module.name()), exports));
            }
        }
        return new FclProgramLinker().link(base, List.copyOf(modules.values()));
    }

    private static void mergeModule(Map<String, FclProgramLinker.Module> modules,
                                    FclProgramLinker.Module candidate) {
        String key = candidate.packageIdentity() + "\u0000" + candidate.moduleName();
        FclProgramLinker.Module existing = modules.get(key);
        if (existing == null) {
            modules.put(key, candidate);
            return;
        }
        if (!existing.source().equals(candidate.source())) {
            throw new IllegalStateException("Identical package module identity has different source");
        }
        Map<String, List<String>> published = new LinkedHashMap<>();
        for (FclProgramLinker.Export export : existing.exports()) {
            published.put(export.symbol(), new ArrayList<>(export.publicNames()));
        }
        for (FclProgramLinker.Export export : candidate.exports()) {
            published.computeIfAbsent(export.symbol(), ignored -> new ArrayList<>())
                    .addAll(export.publicNames());
        }
        List<FclProgramLinker.Export> exports = published.entrySet().stream()
                .map(entry -> new FclProgramLinker.Export(entry.getKey(),
                        entry.getValue().stream().distinct().toList())).toList();
        modules.put(key, new FclProgramLinker.Module(existing.packageIdentity(),
                existing.moduleName(), existing.source(), exports));
    }

    private static List<FclProgramLinker.Export> publishedFunctions(
            PackageDescriptor descriptor,
            PackageIndex.Module module,
            List<ImportSpec> imports
    ) {
        Map<String, List<String>> names = new LinkedHashMap<>();
        descriptor.exports().stream().filter(value -> value.moduleName().equals(module.name()))
                .forEach(value -> imports.forEach(spec -> names
                        .computeIfAbsent(value.symbolName(), ignored -> new ArrayList<>())
                        .add(publicName(spec, value.name()))));
        descriptor.entrypoints().stream()
                .filter(value -> value.moduleName().equals(module.name()))
                .forEach(value -> imports.forEach(spec -> names
                        .computeIfAbsent(value.functionName(), ignored -> new ArrayList<>())
                        .add(publicName(spec, value.name()))));
        return names.entrySet().stream().map(entry -> new FclProgramLinker.Export(
                entry.getKey(), entry.getValue().stream().distinct().toList())).toList();
    }

    private static String publicName(ImportSpec spec, String exportedName) {
        if (spec.wildcard()) return exportedName;
        String namespace = spec.alias() == null ? spec.target() : spec.alias();
        return namespace + "." + exportedName;
    }

    private static String utf8(StoredObject object, String description) {
        return utf8(object.content().bytes(), description);
    }

    private static String utf8(byte[] bytes, String description) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalStateException(description + " is not valid UTF-8", failure);
        }
    }

    private static void validateClaim(CilProcess process, SchedulerClaim claim) {
        if (process.status() != CilProcess.Status.RUNNING) {
            throw new StaleClaimException("Claimed process is not RUNNING");
        }
        if (!process.ownerId().equals(claim.ownerId())) {
            throw new StaleClaimException("Claim owner does not match the process owner");
        }
        if (process.executionEpoch() != claim.executionEpoch()) {
            throw new StaleClaimException("Claim execution epoch is stale");
        }
    }

    private static void terminateAtSafePoint(
            com.follarce.domain.port.TransactionContext transaction,
            CilProcess current,
            SchedulerClaim claim,
            Instant now
    ) {
        CilProcess terminating = current.commitStatement(current.continuation(),
                CilProcess.Status.TERMINATING, current.stateVersion(),
                claim.executionEpoch(), now);
        ProcessRepository.UpdateResult first = transaction.processes().updateClaimed(
                terminating, current.stateVersion(), claim);
        if (first != ProcessRepository.UpdateResult.UPDATED) {
            throw new StaleClaimException("Interrupt was fenced by a concurrent process update");
        }
        CilProcess terminated = terminating.transitionTo(CilProcess.Status.TERMINATED, now);
        ProcessRepository.UpdateResult second = transaction.processes().updateClaimed(
                terminated, terminating.stateVersion(), claim);
        if (second != ProcessRepository.UpdateResult.UPDATED) {
            throw new StaleClaimException("Interrupt termination was fenced");
        }
        transaction.scheduler().release(claim.processUid(), claim.executionEpoch());
    }

    private static void deliverPendingTerminalInput(
            com.follarce.domain.port.TransactionContext transaction,
            CilProcess process,
            FclContinuation continuation,
            Instant now
    ) {
        FclContinuation.WaitState wait = continuation.waitState();
        if (wait.kind() != FclContinuation.WaitKind.EXTERNAL
                || wait.key() == null
                || !(wait.key().equals("input") || wait.key().startsWith("input:"))) {
            return;
        }
        transaction.terminal().acceptPendingInput(process.identity().processUid(), now)
                .ifPresent(input -> {
                    continuation.scope().put(ProcessInbox.TERMINAL_INPUT, input.committedText());
                    continuation.clearWait();
                });
    }

    private static void resolveDirective(
            com.follarce.domain.port.TransactionContext transaction,
            CilProcess process,
            FclContinuation continuation,
            Instant now
    ) {
        FclContinuation.WaitState wait = continuation.waitState();
        if (wait.kind() == FclContinuation.WaitKind.NONE
                || wait.kind() == FclContinuation.WaitKind.EXTERNAL) return;
        String target = normalizeImport(wait.key());
        if (wait.kind() == FclContinuation.WaitKind.INCLUDE) {
            continuation.rejectDirective(
                    "include requires a compiled source dependency; unresolved include: " + target);
            return;
        }
        if (isBuiltinImport(target)) {
            continuation.clearWait();
            return;
        }
        Optional<ProcessPackageBinding> pinned = transaction.packages().findProcessBinding(
                process.identity().processUid(), target);
        if (pinned.isEmpty()) {
            var environment = PackageEnvironments.ensureDefault(transaction.packages(),
                    process.ownerId(), now);
            Optional<PackageBinding> declared = transaction.packages().findBinding(
                    environment.environmentId(), target);
            if (declared.isEmpty()) {
                Optional<PackageRelease> direct = directRelease(transaction, target);
                if (direct.isPresent()) {
                    PackageBinding binding = new PackageBinding(environment.environmentId(), target,
                            direct.orElseThrow().packageHash(), now);
                    transaction.packages().saveBinding(binding);
                    declared = Optional.of(binding);
                }
            }
            if (declared.isEmpty()) {
                continuation.rejectDirective("Unresolved package import: " + target);
                return;
            }
            ProcessPackageBinding resolved = new ProcessPackageBinding(
                    process.identity().processUid(), target, environment.environmentId(),
                    declared.orElseThrow().packageHash(), now);
            transaction.packages().saveProcessBinding(resolved);
        }
        continuation.clearWait();
    }

    private static Optional<PackageRelease> directRelease(
            com.follarce.domain.port.TransactionContext transaction, String target) {
        String[] coordinate = target.split("/", 3);
        if (coordinate.length != 3) return Optional.empty();
        try {
            return transaction.packages().findRelease(new PackageRelease.Coordinate(
                    coordinate[0], coordinate[1], coordinate[2]));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static Continuation withPackageBindings(Continuation continuation,
                                                   List<ProcessPackageBinding> bindings) {
        Map<String, ObjectHash> exact = new LinkedHashMap<>();
        bindings.forEach(binding -> exact.put(binding.importName(),
                binding.packageHash().value()));
        return new Continuation(continuation.programId(), continuation.programHash(),
                continuation.programCounter(), continuation.callStack(), continuation.scopeStack(),
                continuation.exceptionStack(), continuation.controlStack(),
                continuation.waitState(), continuation.globalVariables(), Map.copyOf(exact),
                continuation.languageVersion(), continuation.runtimeFormatVersion());
    }

    private static String normalizeImport(String target) {
        return target != null && target.endsWith(".*")
                ? target.substring(0, target.length() - 2) : target;
    }

    private static boolean isBuiltinImport(String target) {
        return BUILTIN_IMPORTS.contains(target);
    }

    private static ExecutionReplacement resolveExecutionReplacement(
            com.follarce.domain.port.TransactionContext transaction,
            FclContinuation continuation
    ) {
        FclContinuation.WaitState wait = continuation.waitState();
        if (wait.kind() != FclContinuation.WaitKind.EXTERNAL || wait.key() == null
                || !wait.key().startsWith("exec:")) return null;
        UUID programId;
        try {
            programId = UUID.fromString(wait.key().substring("exec:".length()));
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid process.exec program identity", failure);
        }
        Program target = transaction.programs().findById(programId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown exec program"));
        return new ExecutionReplacement(target, new FclContinuation());
    }

    private static Continuation initialContinuation(Program program) {
        return new Continuation(program.programId(), program.programHash(), 0,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), Optional.empty(), java.util.Map.of(), java.util.Map.of(),
                program.languageVersion(), Integer.toString(program.runtimeFormatVersion()));
    }

    private static CilProcess.Status targetStatus(FclStepResult step,
                                                  FclContinuation continuation,
                                                  boolean terminalProcess) {
        if (continuation.failed()) {
            return terminalProcess ? CilProcess.Status.PAUSED : CilProcess.Status.FAILED;
        }
        if (continuation.halted()) {
            return terminalProcess ? CilProcess.Status.PAUSED : CilProcess.Status.TERMINATED;
        }
        return switch (step.status()) {
            case FAILED -> terminalProcess ? CilProcess.Status.PAUSED
                    : CilProcess.Status.FAILED;
            case COMPLETED -> terminalProcess ? CilProcess.Status.PAUSED
                    : CilProcess.Status.TERMINATED;
            case WAITING, DIRECTIVE -> continuation.waitState().kind()
                    == FclContinuation.WaitKind.NONE
                    ? CilProcess.Status.READY : waitingStatus(continuation.waitState());
            case ADVANCED, CALL_ENTERED, RETURNED -> CilProcess.Status.READY;
        };
    }

    private static CilProcess.Status waitingStatus(FclContinuation.WaitState wait) {
        if (wait.kind() == FclContinuation.WaitKind.NONE) {
            throw new IllegalStateException("Runtime reported WAITING without a wait state");
        }
        if (wait.kind() == FclContinuation.WaitKind.EXTERNAL && wait.key() != null) {
            if (wait.key().equals("input") || wait.key().startsWith("input:")) {
                return CilProcess.Status.WAITING_INPUT;
            }
            if (wait.key().startsWith("ipc:")) return CilProcess.Status.WAITING_IPC;
            if (wait.key().startsWith("timer:")) return CilProcess.Status.WAITING_TIMER;
        }
        return CilProcess.Status.WAITING_EFFECT;
    }

    private record ExecutionReplacement(Program program, FclContinuation continuation) {}

    private record ImportSpec(String target, String alias, boolean wildcard) {}

    /** The lease or process epoch no longer authorizes this worker. */
    public static final class StaleClaimException extends IllegalStateException {
        public StaleClaimException(String message) {
            super(message);
        }
    }

    /** A different statement changed the same state version in this execution epoch. */
    public static final class StatementConflictException extends IllegalStateException {
        public StatementConflictException(String message) {
            super(message);
        }
    }
}
