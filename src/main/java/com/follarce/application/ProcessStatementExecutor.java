package com.follarce.application;

import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.ProcessRepository;
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
import com.follarce.extension.JavaExtensionCatalog;
import com.follarce.extension.SourceExtensionIndex;
import com.follarce.scheduler.ClaimedProcessHandler;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import com.follarce.package_manager.PackageEnvironments;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Executes one durable scheduler slice; interactive terminal slices may contain pure steps. */
public final class ProcessStatementExecutor implements ClaimedProcessHandler {
    private static final int MAX_TERMINAL_STEPS_PER_SLICE = 4_096;
    private static final long MAX_TERMINAL_SLICE_NANOS = Duration.ofMillis(20).toNanos();
    private final UserTransactionExecutor transactions;
    private final FclRuntime fixedRuntime;
    private final FclProgramCodec programCodec;
    private final FclPersistenceBridge continuationBridge;
    private final Clock clock;
    private final JavaExtensionCatalog extensions;
    private final Runnable schedulerWake;
    private final Runnable effectWake;
    private final BoundedCache<ObjectHash, FclProgram> programCache = new BoundedCache<>(128);
    private final BoundedCache<ObjectHash, CachedPackage> packageCache = new BoundedCache<>(64);
    private final FclProgramLinker programLinker = new FclProgramLinker();

    public ProcessStatementExecutor(UserTransactionExecutor transactions) {
        this(transactions, SourceExtensionIndex.catalog(), null, new FclProgramCodec(),
                new FclContinuationCodec(), Clock.systemUTC(), () -> { }, () -> { });
    }

    public ProcessStatementExecutor(UserTransactionExecutor transactions,
                                    Runnable schedulerWake, Runnable effectWake) {
        this(transactions, SourceExtensionIndex.catalog(), null, new FclProgramCodec(),
                new FclContinuationCodec(), Clock.systemUTC(), schedulerWake, effectWake);
    }

    public ProcessStatementExecutor(UserTransactionExecutor transactions,
                                    JavaExtensionCatalog extensions) {
        this(transactions, extensions, null, new FclProgramCodec(),
                new FclContinuationCodec(), Clock.systemUTC(), () -> { }, () -> { });
    }

    public ProcessStatementExecutor(UserTransactionExecutor transactions, FclRuntime runtime,
                                    FclProgramCodec programCodec,
                                    FclContinuationCodec continuationCodec,
                                    Clock clock) {
        this(transactions, SourceExtensionIndex.catalog(), runtime, programCodec,
                continuationCodec, clock, () -> { }, () -> { });
    }

    ProcessStatementExecutor(UserTransactionExecutor transactions,
                             JavaExtensionCatalog extensions, FclRuntime runtime,
                             FclProgramCodec programCodec,
                             FclContinuationCodec continuationCodec,
                             Clock clock) {
        this(transactions, extensions, runtime, programCodec, continuationCodec, clock,
                () -> { }, () -> { });
    }

    ProcessStatementExecutor(UserTransactionExecutor transactions,
                             JavaExtensionCatalog extensions, FclRuntime runtime,
                             FclProgramCodec programCodec,
                             FclContinuationCodec continuationCodec,
                             Clock clock, Runnable schedulerWake, Runnable effectWake) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        this.fixedRuntime = runtime;
        this.programCodec = Objects.requireNonNull(programCodec, "programCodec");
        this.continuationBridge = new FclPersistenceBridge(
                Objects.requireNonNull(continuationCodec, "continuationCodec"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.schedulerWake = Objects.requireNonNull(schedulerWake, "schedulerWake");
        this.effectWake = Objects.requireNonNull(effectWake, "effectWake");
    }

    @Override
    public void executeOne(SchedulerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        Instant now = clock.instant();
        PostCommitSignal signal = transactions.inUserTransaction(claim.ownerId(),
                Isolation.READ_COMMITTED, transaction -> {
            if (!claim.authorizes(claim.executionEpoch(), now)) {
                throw new StaleClaimException("Scheduler claim has expired");
            }
            if (!transaction.scheduler().heartbeat(claim)) {
                throw new StaleClaimException("Scheduler lease is no longer owned by this worker");
            }

            CilProcess current = transaction.processes().findByUid(claim.processUid())
                    .orElseThrow(() -> new StaleClaimException("Claimed process no longer exists"));
            validateClaim(current, claim);
            Program program = transaction.programs()
                    .findById(current.continuation().programId())
                    .orElseThrow(() -> new IllegalStateException("Process program no longer exists"));
            FclPersistenceBridge.ensureProgramIdentity(program, current.continuation());
            FclContinuation continuation = continuationBridge.restore(current.continuation());
            boolean terminalProcess = TerminalReplService.isTerminalProcess(continuation);
            if (transaction.terminal().consumeInterrupt(current.identity().processUid())) {
                if (terminalProcess) {
                    cancelTerminalSubmission(transaction, current, claim, program, continuation, now);
                    return PostCommitSignal.NONE;
                }
                terminateAtSafePoint(transaction, current, claim, now);
                return PostCommitSignal.NONE;
            }
            FclProgram compiled = loadProgram(transaction, program);
            compiled = linkPackages(transaction, current, compiled, program);

            FclRuntime statementRuntime = fixedRuntime != null ? fixedRuntime
                    : new FclRuntime(FclRuntimeFunctions.create(transaction, current, program,
                    continuation, now, extensions));
            FclStepResult step = null;
            Program committedProgram = program;
            Continuation previousForPersistence = current.continuation();
            int stepLimit = terminalProcess ? MAX_TERMINAL_STEPS_PER_SLICE : 1;
            long sliceStarted = System.nanoTime();
            for (int executed = 0; executed < stepLimit; executed++) {
                step = statementRuntime.executeOne(compiled, continuation);
                ExecutionReplacement replacement = resolveExecutionReplacement(transaction,
                        continuation);
                if (replacement != null) {
                    committedProgram = replacement.program();
                    continuation = replacement.continuation();
                    previousForPersistence = initialContinuation(committedProgram);
                    break;
                }
                resolveDirective(transaction, current, continuation, now);
                deliverPendingTerminalInput(transaction, current, continuation, now);
                if (terminalSliceBoundary(step, continuation)
                        || System.nanoTime() - sliceStarted >= MAX_TERMINAL_SLICE_NANOS) {
                    break;
                }
            }
            if (step == null) throw new IllegalStateException("Runtime slice executed no steps");
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
            return new PostCommitSignal(target == CilProcess.Status.READY,
                    target == CilProcess.Status.WAITING_EFFECT);
            });
        if (signal.scheduler()) schedulerWake.run();
        if (signal.effect()) effectWake.run();
    }

    private record PostCommitSignal(boolean scheduler, boolean effect) {
        private static final PostCommitSignal NONE = new PostCommitSignal(false, false);
    }

    private static boolean terminalSliceBoundary(FclStepResult step,
                                                  FclContinuation continuation) {
        if (step.status() == FclStepResult.Status.COMPLETED
                || step.status() == FclStepResult.Status.FAILED
                || step.status() == FclStepResult.Status.DIRECTIVE) {
            return true;
        }
        return continuation.waitState().kind() != FclContinuation.WaitKind.NONE;
    }

    private FclProgram loadProgram(com.follarce.domain.port.TransactionContext transaction,
                                   Program program) {
        FclProgram decoded;
        if (program.compiledObjectHash().isPresent()) {
            ObjectHash compiledHash = program.compiledObjectHash().orElseThrow();
            decoded = programCache.get(compiledHash, () -> {
                StoredObject sourceObject = transaction.vfs().findObject(program.sourceObjectHash())
                        .orElseThrow(() -> new IllegalStateException(
                                "Program source object is missing"));
                String source = utf8(sourceObject, "program source");
                StoredObject compiledObject = transaction.vfs().findObject(compiledHash)
                        .orElseThrow(() -> new IllegalStateException(
                                "Compiled program object is missing"));
                FclProgram loaded = programCodec.fromJson(
                        utf8(compiledObject, "compiled program"));
                if (!loaded.source().equals(source)) {
                    throw new IllegalStateException(
                            "Compiled program does not match its source object");
                }
                return loaded;
            });
        } else {
            StoredObject sourceObject = transaction.vfs().findObject(program.sourceObjectHash())
                    .orElseThrow(() -> new IllegalStateException(
                            "Program source object is missing"));
            String source = utf8(sourceObject, "program source");
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

    private FclProgram linkPackages(
            com.follarce.domain.port.TransactionContext transaction,
            CilProcess process,
            FclProgram base,
            Program program
    ) {
        List<ImportSpec> imports = base.instructions().stream()
                .filter(FclInstruction.Import.class::isInstance)
                .map(FclInstruction.Import.class::cast)
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
                    .filter(spec -> spec.importName().equals(entry.getKey())).toList();
            if (matching.isEmpty()) continue;
            PackageRelease release = transaction.packages().findRelease(entry.getValue().packageHash())
                    .orElseThrow(() -> new IllegalStateException("Pinned package release is missing"));
            CachedPackage cached = packageCache.get(release.databaseObjectHash(), () ->
                    loadPackage(transaction, release, reader));
            PackageDescriptor descriptor = cached.descriptor();
            cached.capabilityPolicy().requireUserCapabilities(
                    transaction.auth().capabilities(process.ownerId()));
            if (!descriptor.languageVersion().equals(program.languageVersion())) {
                throw new IllegalStateException("Package language version does not match program: "
                        + descriptor.coordinate());
            }
            String identity = release.packageHash().value().value();
            for (PackageIndex.Module module : descriptor.moduleIndex()) {
                String moduleSource = cached.moduleSources().get(module.objectPath());
                if (moduleSource == null) throw new IllegalStateException(
                        "Package module source is missing: " + module.name());
                List<FclProgramLinker.Export> exports = publishedFunctions(descriptor, module,
                        matching);
                mergeModule(modules, new FclProgramLinker.Module(identity, module.name(),
                        moduleSource, exports));
            }
            Set<ObjectHash> visiting = new LinkedHashSet<>();
            visiting.add(release.databaseFileHash());
            linkDependencies(transaction, process, program, descriptor, reader, modules,
                    visiting, new LinkedHashSet<>());
        }
        return programLinker.link(base, List.copyOf(modules.values()));
    }

    private void linkDependencies(
            com.follarce.domain.port.TransactionContext transaction,
            CilProcess process,
            Program program,
            PackageDescriptor parent,
            SqlitePackageReader reader,
            Map<String, FclProgramLinker.Module> modules,
            Set<ObjectHash> visiting,
            Set<ObjectHash> linked
    ) {
        for (PackageIndex.Dependency dependency : parent.dependencyIndex()) {
            ObjectHash fileHash = dependency.databaseFileHash();
            if (linked.contains(fileHash)) continue;
            Optional<PackageRelease> resolved = transaction.packages()
                    .findReleaseByDatabaseFileHash(fileHash);
            if (resolved.isEmpty()) {
                if (dependency.optional()) continue;
                throw new IllegalStateException("Required package dependency is missing: "
                        + fileHash.value());
            }
            if (!visiting.add(fileHash)) {
                throw new IllegalStateException("Cyclic package dependency: " + fileHash.value());
            }
            PackageRelease release = resolved.orElseThrow();
            CachedPackage cached = packageCache.get(release.databaseObjectHash(), () ->
                    loadPackage(transaction, release, reader));
            PackageDescriptor descriptor = cached.descriptor();
            cached.capabilityPolicy().requireUserCapabilities(
                    transaction.auth().capabilities(process.ownerId()));
            if (!descriptor.languageVersion().equals(program.languageVersion())) {
                throw new IllegalStateException("Dependency language version does not match program: "
                        + descriptor.coordinate());
            }
            linkDependencies(transaction, process, program, descriptor, reader, modules,
                    visiting, linked);
            String identity = release.packageHash().value().value();
            for (PackageIndex.Module module : descriptor.moduleIndex()) {
                String moduleSource = cached.moduleSources().get(module.objectPath());
                if (moduleSource == null) throw new IllegalStateException(
                        "Dependency module source is missing: " + module.name());
                mergeModule(modules, new FclProgramLinker.Module(identity, module.name(),
                        moduleSource, dependencyExports(descriptor, module, fileHash.value())));
            }
            visiting.remove(fileHash);
            linked.add(fileHash);
        }
    }

    private static CachedPackage loadPackage(
            com.follarce.domain.port.TransactionContext transaction,
            PackageRelease release,
            SqlitePackageReader reader
    ) {
        StoredObject database = transaction.vfs().findObject(release.databaseObjectHash())
                .orElseThrow(() -> new IllegalStateException("Pinned package database is missing"));
        byte[] bytes = database.content().bytes();
        PackageDescriptor descriptor = reader.inspect(bytes);
        com.follarce.package_manager.PackageCapabilityPolicy policy =
                com.follarce.package_manager.PackageCapabilityPolicy.inspect(bytes, descriptor);
        Map<String, String> sources = new LinkedHashMap<>();
        for (PackageIndex.Module module : descriptor.moduleIndex()) {
            byte[] sourceBytes = reader.readResource(bytes, module.objectPath());
            if (!ObjectHash.sha256(new com.follarce.domain.vfs.BinaryContent(sourceBytes))
                    .equals(module.hash())) {
                throw new IllegalStateException("Package module hash mismatch: " + module.name());
            }
            sources.put(module.objectPath(), utf8(sourceBytes,
                    "package module " + module.name()));
        }
        return new CachedPackage(descriptor, policy, Map.copyOf(sources));
    }

    private record CachedPackage(
            PackageDescriptor descriptor,
            com.follarce.package_manager.PackageCapabilityPolicy capabilityPolicy,
            Map<String, String> moduleSources
    ) { }

    /** Small synchronized LRU for immutable, database-derived runtime artifacts. */
    private static final class BoundedCache<K, V> {
        private final int maximum;
        private final LinkedHashMap<K, V> values = new LinkedHashMap<>(16, 0.75f, true);

        private BoundedCache(int maximum) {
            this.maximum = maximum;
        }

        private V get(K key, Supplier<V> loader) {
            synchronized (values) {
                V existing = values.get(key);
                if (existing != null) return existing;
            }
            V loaded = Objects.requireNonNull(loader.get(), "cache loader returned null");
            synchronized (values) {
                V raced = values.get(key);
                if (raced != null) return raced;
                values.put(key, loaded);
                while (values.size() > maximum) {
                    values.remove(values.keySet().iterator().next());
                }
                return loaded;
            }
        }
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

    private static List<FclProgramLinker.Export> dependencyExports(
            PackageDescriptor descriptor,
            PackageIndex.Module module,
            String fileHash
    ) {
        return descriptor.exports().stream()
                .filter(value -> value.moduleName().equals(module.name()))
                .map(value -> new FclProgramLinker.Export(value.symbolName(),
                        List.of(fileHash + "." + value.name())))
                .toList();
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

    private void cancelTerminalSubmission(
            com.follarce.domain.port.TransactionContext transaction,
            CilProcess current,
            SchedulerClaim claim,
            Program program,
            FclContinuation continuation,
            Instant now
    ) {
        Continuation cancelled = continuationBridge.persist(current.identity().processUid(), program,
                current.continuation(), continuation.cancelSubmission());
        CilProcess paused = current.commitStatement(cancelled, CilProcess.Status.PAUSED,
                current.stateVersion(), claim.executionEpoch(), now);
        ProcessRepository.UpdateResult result = transaction.processes().updateClaimed(paused,
                current.stateVersion(), claim);
        if (result != ProcessRepository.UpdateResult.UPDATED) {
            throw new StaleClaimException("Terminal interrupt was fenced: " + result);
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

    private void resolveDirective(
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
        Optional<ProcessPackageBinding> pinned = transaction.packages().findProcessBinding(
                process.identity().processUid(), importName(wait, target));
        if (pinned.isEmpty()) {
            var environment = PackageEnvironments.ensureDefault(transaction.packages(),
                    process.ownerId(), now);
            Optional<PackageRelease> release = isSha256(target)
                    ? directRelease(transaction, target)
                    : bindingRelease(transaction, environment.environmentId(), target);
            if (release.isEmpty()) {
                continuation.rejectDirective("Unresolved package import: " + target);
                return;
            }
            ProcessPackageBinding resolved = new ProcessPackageBinding(
                    process.identity().processUid(), importName(wait, target),
                    environment.environmentId(),
                    release.orElseThrow().packageHash(), now);
            transaction.packages().saveProcessBinding(resolved);
        }
        continuation.clearWait();
    }

    private static Optional<PackageRelease> bindingRelease(
            com.follarce.domain.port.TransactionContext transaction,
            UUID environmentId,
            String target
    ) {
        if (!target.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) return Optional.empty();
        return transaction.packages().findBinding(environmentId, target)
                .flatMap(binding -> transaction.packages().findRelease(binding.packageHash()));
    }

    private static Optional<PackageRelease> directRelease(
            com.follarce.domain.port.TransactionContext transaction, String target) {
        if (!isSha256(target)) return Optional.empty();
        return transaction.packages().findReleaseByDatabaseFileHash(new ObjectHash(
                target.toLowerCase(java.util.Locale.ROOT)));
    }

    private static boolean isSha256(String target) {
        return target != null && target.matches("(?i)[0-9a-f]{64}");
    }

    private static String importName(FclContinuation.WaitState wait, String target) {
        Object alias = wait.payload().get("alias");
        return alias instanceof String name ? name : target;
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
        String normalized = target != null && target.endsWith(".*")
                ? target.substring(0, target.length() - 2) : target;
        return isSha256(normalized)
                ? normalized.toLowerCase(java.util.Locale.ROOT) : normalized;
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
        FclContinuation replacement = new FclContinuation();
        if (TerminalReplService.isTerminalProcess(continuation)) {
            // A terminal PID is a durable REPL context, not a disposable command
            // process. exec replaces only its program and execution frames; the
            // outermost user scope must remain available to the target and to the
            // next terminal submission.
            continuation.globalScope().values().forEach(replacement.scope()::put);
            ProcessInbox.keys().forEach(name -> {
                if (replacement.scope().contains(name)) replacement.scope().remove(name);
            });
        }
        return new ExecutionReplacement(target, replacement);
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

    private record ImportSpec(String target, String alias, boolean wildcard) {
        private String importName() {
            return alias == null ? target : alias;
        }
    }

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
