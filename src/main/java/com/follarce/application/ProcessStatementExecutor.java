package com.follarce.application;

import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.program.Program;
import com.follarce.domain.scheduler.SchedulerClaim;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.fcl.FclBuiltins;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.scheduler.ClaimedProcessHandler;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Executes and durably commits exactly one FCL semantic step for a scheduler claim. */
public final class ProcessStatementExecutor implements ClaimedProcessHandler {
    private static final Set<String> PURE_IMPORTS = Set.of(
            "math", "std.math", "util", "std.util", "path", "std.path");
    private final UserTransactionExecutor transactions;
    private final FclRuntime runtime;
    private final FclProgramCodec programCodec;
    private final FclPersistenceBridge continuationBridge;
    private final Clock clock;

    public ProcessStatementExecutor(UserTransactionExecutor transactions) {
        this(transactions, new FclRuntime(FclBuiltins.pureRegistry()),
                new FclProgramCodec(), new FclContinuationCodec(), Clock.systemUTC());
    }

    public ProcessStatementExecutor(UserTransactionExecutor transactions, FclRuntime runtime,
                                    FclProgramCodec programCodec,
                                    FclContinuationCodec continuationCodec,
                                    Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.programCodec = Objects.requireNonNull(programCodec, "programCodec");
        this.continuationBridge = new FclPersistenceBridge(
                Objects.requireNonNull(continuationCodec, "continuationCodec"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void executeOne(SchedulerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        Instant now = clock.instant();
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
            FclProgram compiled = loadProgram(transaction, program);
            FclContinuation continuation = continuationBridge.restore(current.continuation());

            FclStepResult step = runtime.executeOne(compiled, continuation);
            resolveDirective(transaction, current, continuation);
            deliverPendingTerminalInput(transaction, current, continuation, now);
            Continuation persisted = continuationBridge.persist(current.identity().processUid(), program,
                    current.continuation(), continuation.snapshot());
            CilProcess.Status target = targetStatus(step, continuation);
            CilProcess committed = current.commitStatement(persisted, target,
                    current.stateVersion(), claim.executionEpoch(), now);

            ProcessRepository.UpdateResult update = transaction.processes().update(committed,
                    current.stateVersion(), claim.executionEpoch());
            if (update == ProcessRepository.UpdateResult.EPOCH_FENCED) {
                throw new StaleClaimException("Statement commit was fenced by a newer execution epoch");
            }
            if (update != ProcessRepository.UpdateResult.UPDATED) {
                throw new StatementConflictException("Statement state version is stale");
            }

            // Queue state and continuation become visible in the same commit. READY is
            // re-queued; wait/terminal/failure states are removed by repository policy.
            transaction.scheduler().release(claim.processUid(), claim.executionEpoch());
            return null;
        });
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

    private static String utf8(StoredObject object, String description) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(object.content().bytes())).toString();
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
        ProcessRepository.UpdateResult first = transaction.processes().update(terminating,
                current.stateVersion(), claim.executionEpoch());
        if (first != ProcessRepository.UpdateResult.UPDATED) {
            throw new StaleClaimException("Interrupt was fenced by a concurrent process update");
        }
        CilProcess terminated = terminating.transitionTo(CilProcess.Status.TERMINATED, now);
        ProcessRepository.UpdateResult second = transaction.processes().update(terminated,
                terminating.stateVersion(), claim.executionEpoch());
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
            FclContinuation continuation
    ) {
        FclContinuation.WaitState wait = continuation.waitState();
        if (wait.kind() == FclContinuation.WaitKind.NONE
                || wait.kind() == FclContinuation.WaitKind.EXTERNAL) return;
        String target = wait.key();
        if (wait.kind() == FclContinuation.WaitKind.INCLUDE) {
            continuation.rejectDirective(
                    "include requires a compiled source dependency; unresolved include: " + target);
            return;
        }
        boolean pure = PURE_IMPORTS.contains(target);
        boolean pinnedInContinuation = process.continuation().packageBindings().containsKey(target);
        boolean pinnedInDatabase = !pure && !pinnedInContinuation
                && transaction.packages().findProcessBinding(
                process.identity().processUid(), target).isPresent();
        if (pure || pinnedInContinuation || pinnedInDatabase) {
            continuation.clearWait();
        } else {
            continuation.rejectDirective("Unresolved package import: " + target);
        }
    }

    private static CilProcess.Status targetStatus(FclStepResult step,
                                                  FclContinuation continuation) {
        if (continuation.failed()) return CilProcess.Status.FAILED;
        if (continuation.halted()) return CilProcess.Status.TERMINATED;
        return switch (step.status()) {
            case FAILED -> CilProcess.Status.FAILED;
            case COMPLETED -> CilProcess.Status.TERMINATED;
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
