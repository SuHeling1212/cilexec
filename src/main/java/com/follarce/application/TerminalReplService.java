package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.program.Program;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.domain.terminal.TerminalSession;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclInstruction;
import com.follarce.fcl.FclPath;
import com.follarce.fcl.FclProgram;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Runs every submission from one terminal in the same durable, suspended process. */
public final class TerminalReplService {
    private static final String LIBRARY_SCOPE_KEY = "cilexec.repl.library";
    static final String TERMINAL_PROCESS_SCOPE_KEY = "cilexec.repl.terminalProcess";
    private final UserTransactionExecutor transactions;
    private final ProgramService programs;
    private final FclCompiler compiler;
    private final FclPersistenceBridge bridge;
    private final FclContinuationCodec codec;
    private final com.google.gson.Gson displayJson =
            new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    private final Clock clock;

    public TerminalReplService(UserTransactionExecutor transactions) {
        this(transactions, new ProgramService(transactions), new FclCompiler(),
                new FclContinuationCodec(), Clock.systemUTC());
    }

    TerminalReplService(UserTransactionExecutor transactions, ProgramService programs,
                        FclCompiler compiler, FclContinuationCodec codec, Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.programs = java.util.Objects.requireNonNull(programs, "programs");
        this.compiler = java.util.Objects.requireNonNull(compiler, "compiler");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
        this.bridge = new FclPersistenceBridge(codec);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public Submission submit(UUID ownerId, UUID sessionId, String submittedSource) {
        java.util.Objects.requireNonNull(ownerId, "ownerId");
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        String library = library(ownerId, sessionId);
        PreparedSource prepared = replSource(submittedSource, library);
        Program program = programs.create(ownerId, prepared.source());
        Instant now = clock.instant();
        Submission submission = transactions.inUserTransaction(ownerId, Isolation.SERIALIZABLE, transaction -> {
            Authorization.require(transaction, ownerId, Capability.PROCESS_CREATE);
            Authorization.require(transaction, ownerId, Capability.TERMINAL_ATTACH);
            TerminalSession session = transaction.terminal().findSession(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown terminal session"));
            if (!session.ownerId().equals(ownerId) || session.status() != TerminalSession.Status.OPEN) {
                throw new SecurityException("Terminal session is not open for this user");
            }

            Optional<CilProcess> previous = transaction.terminal().findActiveAttachment(sessionId)
                    .flatMap(attachment -> transaction.processes().findByUid(attachment.processUid()));
            if (previous.isPresent()
                    && previous.orElseThrow().status() != CilProcess.Status.PAUSED) {
                throw new IllegalStateException("Attached PID "
                        + previous.orElseThrow().identity().pid()
                        + " must be PAUSED before accepting input; current status is "
                        + previous.orElseThrow().status());
            }

            FclContinuation runtime = nextSubmission(previous);
            runtime.scope().put(TERMINAL_PROCESS_SCOPE_KEY, true);
            runtime.scope().put(FclPath.SCOPE_KEY,
                    transaction.terminal().workingDirectory(sessionId));
            if (!prepared.library().isEmpty()) {
                runtime.scope().put(LIBRARY_SCOPE_KEY, prepared.library());
            }
            UUID processUid = previous.map(value -> value.identity().processUid())
                    .orElseGet(UUID::randomUUID);
            Continuation pristine = initial(program,
                    previous.map(value -> value.continuation().packageBindings()).orElse(Map.of()));
            Continuation persisted = bridge.persist(processUid, program, pristine, runtime);
            CilProcess process;
            if (previous.isPresent()) {
                CilProcess suspended = previous.orElseThrow();
                process = suspended.acceptSubmission(persisted, now);
                requireUpdated(transaction.processes().update(process,
                        suspended.stateVersion(), suspended.executionEpoch()));
            } else {
                long pid = transaction.processes().allocatePid();
                process = new CilProcess(new ProcessIdentity(processUid, pid), ownerId,
                        CilProcess.Status.READY, 0, 0, persisted, Optional.empty(), now, now);
                transaction.processes().insert(process);
                transaction.terminal().saveAttachment(new TerminalSession.Attachment(
                        UUID.randomUUID(), sessionId, processUid, now, Optional.empty()));
            }
            transaction.scheduler().enqueue(new SchedulerQueueEntry(processUid, now, now,
                    SchedulerQueueEntry.Status.READY));
            transaction.audit().append(new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                    ownerId.toString(), "terminal.repl.submit", "process",
                    processUid.toString(), AuditEvent.Result.SUCCEEDED,
                    Map.of("pid", Long.toString(process.identity().pid()),
                            "programId", program.programId().toString(),
                            "reusedProcess", Boolean.toString(previous.isPresent())),
                    now));
            return new Submission(process, prepared.source());
            });
            return submission;
    }

    public Optional<Snapshot> active(UUID ownerId, UUID sessionId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                transaction.terminal().findActiveAttachment(sessionId)
                        .flatMap(attachment -> transaction.processes()
                                .findByUid(attachment.processUid()))
                        .map(this::snapshot));
    }

    public Map<String, Object> variables(UUID ownerId, UUID sessionId) {
        return active(ownerId, sessionId).map(Snapshot::variables).orElse(Map.of());
    }

    public String workingDirectory(UUID ownerId, UUID sessionId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> transaction.terminal().workingDirectory(sessionId));
    }

    private Snapshot snapshot(CilProcess process) {
        FclContinuation runtime = bridge.restore(process.continuation());
        Map<String, Object> variables = new LinkedHashMap<>(runtime.scope().values());
        ProcessInbox.keys().forEach(variables::remove);
        variables.remove(LIBRARY_SCOPE_KEY);
        variables.remove(TERMINAL_PROCESS_SCOPE_KEY);
        variables.remove(FclPath.SCOPE_KEY);
        return new Snapshot(process.identity().pid(), process.status(), runtime.result(),
                Map.copyOf(variables), runtime.failed(), runtime.exceptionStack().stream()
                .map(frame -> frame.type() + ": " + frame.message()).toList());
    }

    private FclContinuation nextSubmission(Optional<CilProcess> previous) {
        if (previous.isEmpty()) return new FclContinuation();
        FclContinuation next = bridge.restore(previous.orElseThrow().continuation())
                .nextSubmission();
        ProcessInbox.keys().forEach(name -> {
            if (next.scope().contains(name)) next.scope().remove(name);
        });
        return next;
    }

    private PreparedSource replSource(String source, String existingLibrary) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("FCL input is empty");
        }
        FclProgram compiled = compiler.compile(source);
        List<FclInstruction> semantic = compiled.instructions().stream()
                .filter(instruction -> !(instruction instanceof FclInstruction.Jump)).toList();
        String normalized = source.endsWith("\n") ? source : source + "\n";
        boolean declaration = librarySubmission(compiled);
        if (declaration) {
            String changedLibrary = existingLibrary + normalized;
            // Compile the complete accumulated library now so duplicate or incompatible
            // declarations fail before a process or terminal attachment is created.
            compiler.compile(changedLibrary);
            return new PreparedSource(changedLibrary, changedLibrary);
        }
        if (semantic.size() == 1 && semantic.getFirst() instanceof FclInstruction.Evaluation) {
            return new PreparedSource(existingLibrary + "return " + source.strip() + "\n",
                    existingLibrary);
        }
        return new PreparedSource(existingLibrary + normalized, existingLibrary);
    }

    private String library(UUID ownerId, UUID sessionId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                transaction.terminal().findActiveAttachment(sessionId)
                        .flatMap(value -> transaction.processes().findByUid(value.processUid()))
                        .filter(value -> value.status() == CilProcess.Status.PAUSED)
                        .map(value -> bridge.restore(value.continuation()))
                        .filter(value -> value.scope().contains(LIBRARY_SCOPE_KEY))
                        .map(value -> value.scope().get(LIBRARY_SCOPE_KEY))
                        .map(value -> {
                            if (!(value instanceof String text)) {
                                throw new IllegalStateException("Persisted REPL library is invalid");
                            }
                            return text;
                        }).orElse(""));
    }

    private static boolean librarySubmission(FclProgram program) {
        if (program.instructions().isEmpty()) return false;
        java.util.BitSet functionBodies = new java.util.BitSet(program.instructions().size());
        program.functions().values().forEach(function ->
                functionBodies.set(function.entryPoint(), function.endPoint()));
        boolean declaration = false;
        for (int index = 0; index < program.instructions().size(); index++) {
            if (functionBodies.get(index)) continue;
            FclInstruction instruction = program.instructions().get(index);
            if (instruction instanceof FclInstruction.FunctionDeclaration
                    || instruction instanceof FclInstruction.Import) {
                declaration = true;
                continue;
            }
            if (!(instruction instanceof FclInstruction.Jump)) return false;
        }
        return declaration;
    }

    private static Continuation initial(Program program,
                                        Map<String, com.follarce.domain.vfs.ObjectHash> bindings) {
        return new Continuation(program.programId(), program.programHash(), 0,
                List.of(), List.of(), List.of(), List.of(), Optional.empty(), Map.of(), bindings,
                program.languageVersion(), Integer.toString(program.runtimeFormatVersion()));
    }

    static boolean isTerminalProcess(FclContinuation continuation) {
        FclContinuation runtime = java.util.Objects.requireNonNull(continuation,
                "continuation");
        if (runtime.scope().contains(TERMINAL_PROCESS_SCOPE_KEY)) return true;
        return runtime.callStack().stream().anyMatch(frame ->
                frame.callerScope().contains(TERMINAL_PROCESS_SCOPE_KEY));
    }

    private static void requireUpdated(
            com.follarce.domain.port.ProcessRepository.UpdateResult result) {
        if (result != com.follarce.domain.port.ProcessRepository.UpdateResult.UPDATED) {
            throw new IllegalStateException(
                    "Suspended terminal process changed while accepting input: " + result);
        }
    }

    public String render(Object value) {
        return displayJson.toJson(value);
    }

    public record Submission(CilProcess process, String source) {
    }

    public record Snapshot(long pid, CilProcess.Status status, Object result,
                           Map<String, Object> variables, boolean failed,
                           List<String> errors) {
        public Snapshot {
            variables = Map.copyOf(variables);
            errors = List.copyOf(errors);
        }
    }

    private record PreparedSource(String source, String library) {
    }
}
