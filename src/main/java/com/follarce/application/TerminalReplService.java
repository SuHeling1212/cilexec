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
import com.follarce.fcl.FclExpression;
import com.follarce.fcl.FclInstruction;
import com.follarce.fcl.FclPath;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclScope;
import com.follarce.fcl.TerminalModeState;
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
    static final String LIBRARY_SCOPE_KEY = "cilexec.repl.library";
    static final String TERMINAL_PROCESS_SCOPE_KEY = "cilexec.repl.terminalProcess";
    public static final String TERMINAL_SESSION_SCOPE_KEY = "cilexec.repl.terminalSession";
    private final UserTransactionExecutor transactions;
    private final ProgramService programs;
    private final FclCompiler compiler;
    private final FclPersistenceBridge bridge;
    private final FclContinuationCodec codec;
    private static final com.google.gson.Gson DISPLAY_JSON = new com.google.gson.GsonBuilder()
            .disableHtmlEscaping().setPrettyPrinting().create();
    private final Clock clock;
    private final Runnable workAvailable;

    public TerminalReplService(UserTransactionExecutor transactions) {
        this(transactions, () -> { });
    }

    public TerminalReplService(UserTransactionExecutor transactions, Runnable workAvailable) {
        this(transactions, new ProgramService(transactions), new FclCompiler(),
                new FclContinuationCodec(), Clock.systemUTC(), workAvailable);
    }

    TerminalReplService(UserTransactionExecutor transactions, ProgramService programs,
                        FclCompiler compiler, FclContinuationCodec codec, Clock clock) {
        this(transactions, programs, compiler, codec, clock, () -> { });
    }

    TerminalReplService(UserTransactionExecutor transactions, ProgramService programs,
                        FclCompiler compiler, FclContinuationCodec codec, Clock clock,
                        Runnable workAvailable) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.programs = java.util.Objects.requireNonNull(programs, "programs");
        this.compiler = java.util.Objects.requireNonNull(compiler, "compiler");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
        this.bridge = new FclPersistenceBridge(codec);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.workAvailable = java.util.Objects.requireNonNull(workAvailable, "workAvailable");
    }

    public Submission submit(UUID ownerId, UUID sessionId, String submittedSource) {
        java.util.Objects.requireNonNull(ownerId, "ownerId");
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        if (submittedSource == null
                || submittedSource.length() > com.follarce.terminal.TerminalInput.MAX_SUBMISSION_CHARACTERS) {
            throw new IllegalArgumentException("FCL submission exceeds 256 Ki characters");
        }
        String workingDirectory = workingDirectory(ownerId, sessionId);
        String expandedSubmission = programs.expandIncludes(ownerId, submittedSource,
                workingDirectory);
        Instant now = clock.instant();
        Submission submission = transactions.inUserTransaction(ownerId, Isolation.SERIALIZABLE, transaction -> {
            Authorization.require(transaction, ownerId, Capability.PROCESS_CREATE);
            Authorization.require(transaction, ownerId, Capability.TERMINAL_ATTACH);
            TerminalSession session = transaction.terminal().findSessionForUpdate(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown terminal session"));
            if (!session.ownerId().equals(ownerId) || session.status() != TerminalSession.Status.OPEN) {
                throw new SecurityException("Terminal session is not open for this user");
            }

            Optional<CilProcess> attached = transaction.terminal().findActiveAttachment(sessionId)
                    .flatMap(attachment -> transaction.processes().findByUid(attachment.processUid()));
            if (attached.isPresent() && !attached.orElseThrow().isTerminal()
                    && attached.orElseThrow().status() != CilProcess.Status.PAUSED) {
                throw new IllegalStateException("Attached PID "
                        + attached.orElseThrow().identity().pid()
                        + " must be PAUSED before accepting input; current status is "
                        + attached.orElseThrow().status());
            }
            // A deterministic runtime failure cannot be resumed. Replace the terminal
            // attachment so the user can immediately start a clean REPL process.
            Optional<CilProcess> previous = attached.filter(process -> !process.isTerminal());
            String library = accumulatedLibrary(previous);
            PreparedSource prepared = replSource(expandedSubmission, library);
            UUID processUid = previous.map(value -> value.identity().processUid())
                    .orElseGet(UUID::randomUUID);
            // A submission that imports an unresolvable package must fail here, before the
            // accumulated library (and the imported statement) is persisted. Otherwise the
            // broken import is recompiled into every later submission and the terminal is
            // permanently wedged on "Unresolved package import".
            validateImports(transaction, processUid, ownerId,
                    compiler.compile(prepared.source()), now);
            Program program = programs.compileAndSaveIn(transaction, prepared.source());

            FclContinuation runtime = nextSubmission(previous);
            runtime.enableFunctions(functionNames(expandedSubmission));
            runtime.scope().put(TERMINAL_PROCESS_SCOPE_KEY, true);
            runtime.scope().put(TERMINAL_SESSION_SCOPE_KEY, sessionId.toString());
            runtime.scope().put(FclPath.SCOPE_KEY,
                    transaction.terminal().workingDirectory(sessionId));
            if (!prepared.library().isEmpty()) {
                runtime.scope().put(LIBRARY_SCOPE_KEY, prepared.library());
            }
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
            workAvailable.run();
            return submission;
    }

    public Optional<Snapshot> active(UUID ownerId, UUID sessionId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                transaction.terminal().findActiveAttachment(sessionId)
                        .flatMap(attachment -> transaction.processes()
                                .findByUid(attachment.processUid()))
                        .map(this::snapshot));
    }

    /** Replays terminal modes committed by the attached process before it next renders. */
    public String terminalRestoreSequence(UUID ownerId, UUID sessionId) {
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction ->
                transaction.terminal().findActiveAttachment(sessionId)
                        .flatMap(attachment -> transaction.processes()
                                .findByUid(attachment.processUid()))
                        .filter(process -> process.status() == CilProcess.Status.WAITING_INPUT)
                        .filter(process -> {
                            FclContinuation continuation = bridge.restore(process.continuation());
                            return continuation.waitState().kind()
                                    == FclContinuation.WaitKind.EXTERNAL
                                    && continuation.waitState().key() != null
                                    && continuation.waitState().key().startsWith("input:key");
                        })
                        .map(process -> TerminalModeState.replay(
                                bridge.restore(process.continuation()).globalScope()))
                        .orElse(""));
    }

    public Map<String, Object> variables(UUID ownerId, UUID sessionId) {
        return active(ownerId, sessionId).map(Snapshot::variables).orElse(Map.of());
    }

    public String workingDirectory(UUID ownerId, UUID sessionId) {
        return environmentVariable(ownerId, sessionId, "PWD");
    }

    public String environmentVariable(UUID ownerId, UUID sessionId, String name) {
        if (!"PWD".equals(name)) {
            throw new IllegalArgumentException("Unknown terminal environment variable: " + name);
        }
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> transaction.terminal().workingDirectory(sessionId));
    }

    private Snapshot snapshot(CilProcess process) {
        FclContinuation runtime = bridge.restore(process.continuation());
        Map<String, Object> variables = new LinkedHashMap<>(runtime.scope().values());
        ProcessInbox.keys().forEach(variables::remove);
        variables.remove(LIBRARY_SCOPE_KEY);
        variables.remove(TERMINAL_PROCESS_SCOPE_KEY);
        variables.remove(TERMINAL_SESSION_SCOPE_KEY);
        variables.remove(FclPath.SCOPE_KEY);
        return new Snapshot(process.identity().pid(), process.status(), runtime.result(),
                immutableVariables(variables), runtime.failed(),
                runtime.waitState().kind() == FclContinuation.WaitKind.EXTERNAL
                        && runtime.waitState().key() != null
                        && runtime.waitState().key().startsWith("input:key"),
                Boolean.TRUE.equals(runtime.waitState().payload().get("coalesceText")),
                runtime.exceptionStack().stream()
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
            String changedLibrary = mergeImports(existingLibrary, importsFrom(compiled))
                    + strippedImports(normalized);
            // Compile the complete accumulated library now so duplicate or incompatible
            // declarations fail before a process or terminal attachment is created.
            compiler.compile(changedLibrary);
            return new PreparedSource(changedLibrary, changedLibrary);
        }
        String importedLibrary = importsFrom(compiled);
        String nextLibrary = appendFunctionDeclarations(
                mergeImports(existingLibrary, importedLibrary), normalized);
        if (!importedLibrary.isEmpty()) {
            compiler.compile(nextLibrary);
        }
        if (semantic.size() == 1
                && semantic.getFirst() instanceof FclInstruction.Evaluation evaluation) {
            // A string literal must keep its exact text: stripping would destroy a value
            // whose content is only whitespace. Other single expressions only strip.
            boolean stringLiteral = evaluation.expression() instanceof FclExpression.Literal literal
                    && literal.value() instanceof String;
            String expression = stringLiteral ? source : source.strip();
            return new PreparedSource(existingLibrary + "return " + expression + "\n",
                    nextLibrary);
        }
        return new PreparedSource(existingLibrary + normalized, nextLibrary);
    }

    /**
     * Reads the accumulated library from the attached process inside the caller's
     * transaction, so the read-modify-write against the persisted continuation stays
     * atomic under concurrent submissions to the same session.
     */
    private String accumulatedLibrary(Optional<CilProcess> previous) {
        return previous
                .filter(value -> value.status() == CilProcess.Status.PAUSED)
                .map(value -> bridge.restore(value.continuation()))
                .filter(value -> value.globalScope().contains(LIBRARY_SCOPE_KEY))
                .map(value -> value.globalScope().get(LIBRARY_SCOPE_KEY))
                .map(value -> {
                    if (!(value instanceof String text)) {
                        throw new IllegalStateException("Persisted REPL library is invalid");
                    }
                    return text;
                }).orElse("");
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

    /** Retains top-level imports even when package installation and import share one submission. */
    private static String importsFrom(FclProgram program) {
        java.util.BitSet functionBodies = new java.util.BitSet(program.instructions().size());
        program.functions().values().forEach(function ->
                functionBodies.set(function.entryPoint(), function.endPoint()));
        StringBuilder imports = new StringBuilder();
        for (int index = 0; index < program.instructions().size(); index++) {
            if (functionBodies.get(index)) continue;
            if (!(program.instructions().get(index) instanceof FclInstruction.Import value)) {
                continue;
            }
            imports.append("import \"").append(escape(value.target())).append('"');
            if (value.alias() != null) {
                imports.append(" as \"").append(escape(value.alias())).append('"');
            }
            imports.append('\n');
        }
        return imports.toString();
    }

    /**
     * Last-wins import merging: any existing library import that binds the same qualifier
     * (the alias, or the target hash when there is no alias) is replaced by the new import,
     * so re-importing an alias never accumulates a second binding and cannot conflict during
     * later linking.
     */
    private static String mergeImports(String existingLibrary, String newImports) {
        if (newImports == null || newImports.isBlank()) return existingLibrary;
        java.util.Set<String> qualifiers = new java.util.LinkedHashSet<>();
        for (String line : newImports.split("\n")) {
            if (!line.isBlank()) qualifiers.add(importQualifier(line));
        }
        StringBuilder merged = new StringBuilder();
        for (String line : existingLibrary.split("\n")) {
            if (line.isBlank()) continue;
            if (qualifiers.contains(importQualifier(line))) continue;
            merged.append(line).append('\n');
        }
        merged.append(newImports);
        return merged.toString();
    }

    /** Removes top-level import lines from a declaration submission, keeping declarations. */
    private static String strippedImports(String source) {
        StringBuilder result = new StringBuilder();
        for (String line : source.split("\n")) {
            if (line.stripLeading().startsWith("import ")) continue;
            result.append(line).append('\n');
        }
        return result.toString();
    }

    private static String importQualifier(String importLine) {
        int alias = importLine.indexOf(" as \"");
        if (alias >= 0) {
            int aliasStart = alias + 5;
            int end = importLine.indexOf('"', aliasStart);
            if (end > aliasStart) return importLine.substring(aliasStart, end);
        }
        int first = importLine.indexOf('"');
        int second = first >= 0 ? importLine.indexOf('"', first + 1) : -1;
        return first >= 0 && second > first
                ? importLine.substring(first + 1, second) : importLine;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Rejects a submission whose top-level imports cannot be resolved, mirroring the
     * runtime directive resolution in ProcessStatementExecutor. Running this inside the
     * submit transaction guarantees a broken import can never reach the persisted REPL
     * library, which would otherwise wedge every later command in the same session.
     */
    private static void validateImports(com.follarce.domain.port.TransactionContext transaction,
                                        UUID processUid, UUID ownerId, FclProgram compiled,
                                        Instant now) {
        java.util.LinkedHashSet<String> validated = new java.util.LinkedHashSet<>();
        for (FclInstruction instruction : compiled.instructions()) {
            if (!(instruction instanceof FclInstruction.Import value)) continue;
            String target = ProcessStatementExecutor.normalizeImport(value.target());
            if (!ProcessStatementExecutor.isSha256(target)) continue;
            String name = value.alias() != null ? value.alias() : target;
            if (!validated.add(name)) continue;
            boolean resolvable = transaction.packages()
                    .findProcessBinding(processUid, name).isPresent();
            if (!resolvable) {
                resolvable = transaction.packages()
                        .findInstalledReleaseByDatabaseFileHash(ownerId,
                                new com.follarce.domain.vfs.ObjectHash(
                                        target.toLowerCase(java.util.Locale.ROOT)))
                        .isPresent();
            }
            if (!resolvable) {
                throw new com.follarce.fcl.FclRuntimeException("Unresolved package import: "
                        + value.target() + "; install the exact package hash first and "
                        + "submit again");
            }
        }
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

    static String librarySource(FclContinuation continuation) {
        FclScope global = java.util.Objects.requireNonNull(continuation,
                "continuation").globalScope();
        if (!global.contains(LIBRARY_SCOPE_KEY)) return "";
        Object value = global.get(LIBRARY_SCOPE_KEY);
        if (!(value instanceof String source)) {
            throw new IllegalStateException("Persisted REPL library is invalid");
        }
        return source;
    }

    /** Keeps top-level definitions from mixed REPL/include/exec source without replaying code. */
    static String appendFunctionDeclarations(String library, String source) {
        String declarations = functionDeclarations(source);
        if (declarations.isBlank()) return library;
        String combined = library == null || library.isBlank() ? declarations
                : library + (library.endsWith("\n") ? "" : "\n") + declarations;
        new FclCompiler().compile(combined);
        return combined;
    }

    /** Removes a mutable process-local source function without touching package/runtime code. */
    static String removeFunctionDeclaration(String library, String name) {
        if (library == null || library.isBlank()) return library == null ? "" : library;
        StringBuilder remaining = new StringBuilder(library.length());
        int cursor = 0;
        for (FunctionDeclaration declaration : declarations(library)) {
            if (!declaration.name().equals(name)) continue;
            remaining.append(library, cursor, declaration.start());
            cursor = declaration.end();
        }
        remaining.append(library, cursor, library.length());
        return remaining.toString();
    }

    private static String functionDeclarations(String source) {
        StringBuilder declarations = new StringBuilder();
        for (FunctionDeclaration declaration : declarations(source)) {
            declarations.append(source, declaration.start(), declaration.end());
            if (!declarations.toString().endsWith("\n")) declarations.append('\n');
        }
        return declarations.toString();
    }

    private static java.util.Set<String> functionNames(String source) {
        return declarations(source).stream().map(FunctionDeclaration::name)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** Minimal source scanner: FCL functions are top-level and braces in strings/comments are ignored. */
    private static List<FunctionDeclaration> declarations(String source) {
        List<FunctionDeclaration> result = new java.util.ArrayList<>();
        int depth = 0;
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '"') {
                index = skipString(source, index + 1);
                continue;
            }
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                index = skipLine(source, index + 2);
                continue;
            }
            if (current == '{') { depth++; index++; continue; }
            if (current == '}') { depth = Math.max(0, depth - 1); index++; continue; }
            int declarationStart = index;
            int functionStart = index;
            if (depth == 0 && (source.startsWith("public", index) || source.startsWith("private", index))
                    && wordBoundary(source, index - 1)
                    && wordBoundary(source, index + (source.startsWith("public", index) ? 6 : 7))) {
                functionStart = skipSpace(source, index + (source.startsWith("public", index) ? 6 : 7));
            }
            if (depth == 0 && source.startsWith("func", functionStart)
                    && wordBoundary(source, functionStart - 1)
                    && wordBoundary(source, functionStart + 4)) {
                int nameStart = skipSpace(source, functionStart + 4);
                int nameEnd = nameStart;
                while (nameEnd < source.length() && (Character.isLetterOrDigit(source.charAt(nameEnd))
                        || source.charAt(nameEnd) == '_')) nameEnd++;
                if (nameEnd == nameStart) { index = functionStart + 4; continue; }
                int body = source.indexOf('{', nameEnd);
                if (body < 0) { index = nameEnd; continue; }
                int end = matchingBrace(source, body);
                if (end < 0) { index = body + 1; continue; }
                result.add(new FunctionDeclaration(source.substring(nameStart, nameEnd), declarationStart,
                        end + 1));
                index = end + 1;
                continue;
            }
            index++;
        }
        return result;
    }

    private static int matchingBrace(String source, int open) {
        int depth = 1;
        for (int index = open + 1; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '"') { index = skipString(source, index + 1) - 1; continue; }
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                index = skipLine(source, index + 2) - 1;
                continue;
            }
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return index;
        }
        return -1;
    }

    private static int skipString(String source, int index) {
        while (index < source.length()) {
            if (source.charAt(index) == '\\') { index += 2; continue; }
            if (source.charAt(index++) == '"') break;
        }
        return index;
    }

    private static int skipLine(String source, int index) {
        while (index < source.length() && source.charAt(index) != '\n'
                && source.charAt(index) != '\r') index++;
        return index;
    }

    private static int skipSpace(String source, int index) {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        return index;
    }

    private static boolean wordBoundary(String source, int index) {
        return index < 0 || index >= source.length() || !(Character.isLetterOrDigit(source.charAt(index))
                || source.charAt(index) == '_');
    }

    private record FunctionDeclaration(String name, int start, int end) {}

    private static void requireUpdated(
            com.follarce.domain.port.ProcessRepository.UpdateResult result) {
        if (result != com.follarce.domain.port.ProcessRepository.UpdateResult.UPDATED) {
            throw new IllegalStateException(
                    "Suspended terminal process changed while accepting input: " + result);
        }
    }

    public String render(Object value) {
        return renderValue(value);
    }

    static String renderValue(Object value) {
        if (value instanceof String text) {
            String candidate = text.strip();
            if (candidate.startsWith("{") || candidate.startsWith("[")) {
                try {
                    com.google.gson.JsonElement parsed =
                            com.google.gson.JsonParser.parseString(candidate);
                    if (parsed.isJsonObject() || parsed.isJsonArray()) {
                        return DISPLAY_JSON.toJson(parsed);
                    }
                } catch (com.google.gson.JsonParseException ignored) {
                    // Ordinary text that resembles JSON retains normal string rendering.
                }
            }
        }
        return DISPLAY_JSON.toJson(value);
    }

    public record Submission(CilProcess process, String source) {
    }

    public record Snapshot(long pid, CilProcess.Status status, Object result,
                           Map<String, Object> variables, boolean failed, boolean keyInput,
                           boolean coalesceTextInput, List<String> errors) {
        public Snapshot {
            variables = immutableVariables(variables);
            errors = List.copyOf(errors);
        }
    }

    private static Map<String, Object> immutableVariables(Map<String, Object> variables) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(
                java.util.Objects.requireNonNull(variables, "variables")));
    }

    private record PreparedSource(String source, String library) {
    }
}
