package com.follarce.terminal;

import com.follarce.application.TerminalReplService;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.fcl.FclPath;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

/** Database-backed terminal control plane plus a durable, full-function FCL REPL. */
public final class DatabaseTerminalControl implements TerminalControl {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private final JdbcTransactionExecutor transactions;
    private final UserAccount user;
    private final TerminalService terminals;
    private final TerminalReplService repl;
    private final Runnable shutdown;
    private final Predicate<char[]> passwordVerifier;
    private UUID sessionId;
    private volatile Boolean isAdmin;

    public DatabaseTerminalControl(JdbcTransactionExecutor transactions, UserAccount user,
                                   Runnable shutdown, Predicate<char[]> passwordVerifier) {
        this(transactions, user, shutdown, passwordVerifier, () -> { }, () -> { });
    }

    public DatabaseTerminalControl(JdbcTransactionExecutor transactions, UserAccount user,
                                   Runnable shutdown, Predicate<char[]> passwordVerifier,
                                   Runnable schedulerWake, Runnable interruptWake) {
        this(transactions, user, shutdown, passwordVerifier, schedulerWake, interruptWake,
                Optional.empty());
    }

    /** Creates a control surface backed by the durable context for one host terminal. */
    public static DatabaseTerminalControl headless(
            JdbcTransactionExecutor transactions, UserAccount user, String contextId,
            Runnable shutdown, Predicate<char[]> passwordVerifier,
            Runnable schedulerWake, Runnable interruptWake) {
        return new DatabaseTerminalControl(transactions, user, shutdown, passwordVerifier,
                schedulerWake, interruptWake, Optional.of(contextId));
    }

    private DatabaseTerminalControl(JdbcTransactionExecutor transactions, UserAccount user,
                                    Runnable shutdown, Predicate<char[]> passwordVerifier,
                                    Runnable schedulerWake, Runnable interruptWake,
                                    Optional<String> headlessContext) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.user = java.util.Objects.requireNonNull(user, "user");
        this.shutdown = java.util.Objects.requireNonNull(shutdown, "shutdown");
        this.passwordVerifier = java.util.Objects.requireNonNull(passwordVerifier,
                "passwordVerifier");
        this.terminals = new TerminalService(transactions, Clock.systemUTC(), schedulerWake,
                interruptWake);
        this.repl = new TerminalReplService(transactions, schedulerWake);
        this.sessionId = headlessContext
                .map(context -> terminals.openOrResume(user.userId(), context))
                .orElseGet(() -> terminals.openOrResume(user.userId())).sessionId();
    }

    @Override
    public long idleRemainingNanos(long thresholdNanos) {
        try {
            Optional<CilProcess> attached = transactions.inUserTransaction(user.userId(),
                    Isolation.READ_COMMITTED, transaction ->
                            transaction.terminal().findActiveAttachment(sessionId)
                                    .flatMap(attachment -> transaction.processes()
                                            .findByUid(attachment.processUid())));
            return idleRemainingNanos(attached.orElse(null), Instant.now(), thresholdNanos);
        } catch (RuntimeException failure) {
            // A transient query failure must never close a live session.
            return Long.MAX_VALUE;
        }
    }

    /**
     * A session closes for idleness only when its attached process has been suspended
     * (PAUSED) for the whole threshold: REPL idle, nothing running. Active processes,
     * full-screen programs waiting on input, and sessions with no attachment are never
     * closed for idleness.
     */
    static long idleRemainingNanos(CilProcess process, Instant now, long thresholdNanos) {
        if (process == null || process.status() != CilProcess.Status.PAUSED) {
            return Long.MAX_VALUE;
        }
        long elapsed = Duration.between(process.updatedAt(), now).toNanos();
        long remaining = thresholdNanos - elapsed;
        return remaining <= 0 ? 0 : remaining;
    }

    @Override
    public String execute(ShellCommand command) {
        java.util.Objects.requireNonNull(command, "command");
        return switch (command) {
            case ShellCommand.Help ignored -> help();
            case ShellCommand.ChangeDirectory cd -> changeDirectory(cd.path());
            case ShellCommand.WorkingDirectory ignored -> workingDirectory();
            case ShellCommand.ListDirectory ls -> listDirectory(ls.path());
            case ShellCommand.Clear ignored -> "\033[?25h\033[2J\033[H";
            case ShellCommand.Logout ignored -> "logout requested";
            // Exit closes only the calling transport. TerminalConsole handles it before
            // delegation; keeping this branch side-effect-free protects other adapters too.
            case ShellCommand.Exit ignored -> "";
            case ShellCommand.Shutdown ignored -> throw new IllegalArgumentException(
                    "shutdown requires interactive password verification");
        };
    }

    @Override
    public boolean canShutdown() {
        return isAdmin();
    }

    @Override
    public Optional<UUID> outputRouteId() {
        return Optional.of(sessionId);
    }

    @Override
    public void shutdown(char[] password) {
        java.util.Objects.requireNonNull(password, "password");
        if (!isAdmin()) {
            throw new IllegalArgumentException("Administrator permission is required");
        }
        if (!passwordVerifier.test(password)) {
            throw new IllegalArgumentException("Invalid administrator password");
        }
        shutdown.run();
    }

    @Override
    public String evaluate(String source) {
        TerminalReplService.Submission submission = repl.submit(user.userId(), sessionId, source);
        return await(submission.process().identity().pid());
    }

    @Override
    public String submitAttachedInput(String input) {
        TerminalReplService.Snapshot active = repl.active(user.userId(), sessionId)
                .orElseThrow(() -> new IllegalStateException("No process is attached"));
        if (active.status() != CilProcess.Status.WAITING_INPUT) {
            throw new IllegalStateException("Attached PID is not waiting for input");
        }
        terminals.submit(user.userId(), sessionId, input);
        return await(active.pid());
    }

    @Override
    public boolean awaitingAttachedInput() {
        return repl.active(user.userId(), sessionId)
                .map(snapshot -> snapshot.status() == CilProcess.Status.WAITING_INPUT)
                .orElse(false);
    }

    @Override
    public AttachedInputMode attachedInputMode() {
        return repl.active(user.userId(), sessionId)
                .filter(snapshot -> snapshot.status() == CilProcess.Status.WAITING_INPUT)
                .map(snapshot -> snapshot.keyInput()
                        ? AttachedInputMode.KEY : AttachedInputMode.LINE)
                .orElse(AttachedInputMode.NONE);
    }

    @Override
    public boolean interruptForeground() {
        Optional<TerminalReplService.Snapshot> active = repl.active(user.userId(), sessionId)
                .filter(snapshot -> snapshot.status() != CilProcess.Status.PAUSED)
                .filter(snapshot -> !terminal(snapshot.status()));
        if (active.isEmpty()) return false;
        long pid = active.orElseThrow().pid();
        return terminals.interruptTerminal(user.userId(), pid);
    }

    @Override
    public List<String> commandHistory() {
        return terminals.commandHistory(user.userId());
    }

    @Override
    public String username() {
        return user.username();
    }

    @Override
    public void rememberCommand(String command) {
        terminals.rememberCommand(user.userId(), command);
    }

    @Override
    public String prompt() {
        String usernameColor = isAdmin() ? "[31m" : "[32m";
        String reset = "[0m";
        String blue = "[34m";
        String bold = "[1m";

        String coloredUser = usernameColor + user.username() + reset;
        String coloredHost = blue + "cilexec" + reset;

        Optional<TerminalReplService.Snapshot> active = repl.active(user.userId(), sessionId);
        if (active.isPresent() && active.orElseThrow().status() == CilProcess.Status.WAITING_INPUT) {
            return coloredUser + "@" + coloredHost
                    + "[pid:" + active.orElseThrow().pid() + "]? ";
        }
        String coloredPath = bold + workingDirectory() + reset;
        return coloredUser + "@" + coloredHost + ":" + coloredPath + "$ ";
    }

    private boolean isAdmin() {
        if (isAdmin == null) {
            synchronized (this) {
                if (isAdmin == null) {
                    isAdmin = transactions.inUserTransaction(user.userId(),
                            Isolation.READ_COMMITTED,
                            transaction -> transaction.auth().capabilities(user.userId())
                                    .contains(com.follarce.domain.auth.Capability.SYSTEM_ADMIN));
                }
            }
        }
        return isAdmin;
    }

    private String changeDirectory(String path) {
        return transactions.inUserTransaction(user.userId(), Isolation.SERIALIZABLE, transaction -> {
            String current = transaction.terminal().workingDirectory(sessionId);
            String replacement = FclPath.resolve(current, path);
            VfsNode directory = resolve(transaction, replacement);
            if (directory.type() != VfsNode.Type.DIRECTORY) {
                throw new IllegalArgumentException("Not a directory: " + replacement);
            }
            if (!transaction.terminal().changeWorkingDirectory(sessionId, current,
                    replacement, Instant.now())) {
                throw new IllegalStateException("Working directory changed concurrently");
            }
            return "";
        });
    }

    private String workingDirectory() {
        return repl.environmentVariable(user.userId(), sessionId, "PWD");
    }

    private String listDirectory(Optional<String> path) {
        return transactions.inUserTransaction(user.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    String current = transaction.terminal().workingDirectory(sessionId);
                    String requested = FclPath.resolve(current, path.orElse("."));
                    if (requested.equals("/Users") && isLocalAdministrator()) {
                        return transaction.auth().findUsersByAdministrator(user.userId()).stream()
                                .map(UserAccount::username).sorted(String.CASE_INSENSITIVE_ORDER)
                                .map(name -> name + "/")
                                .collect(java.util.stream.Collectors.joining("\n"));
                    }
                    ResolvedPath routed = route(transaction, requested);
                    VfsNode directory = resolve(transaction, routed.ownerId(), routed.path());
                    if (directory.type() != VfsNode.Type.DIRECTORY) {
                        throw new IllegalArgumentException("Not a directory: " + requested);
                    }
                    List<VfsNode> children = transaction.vfs().findChildren(routed.ownerId(),
                            Optional.of(directory.nodeId()));
                    if (children.isEmpty()) return "";
                    return children.stream().map(node -> node.name()
                                    + (node.type() == VfsNode.Type.DIRECTORY ? "/" : ""))
                            .collect(java.util.stream.Collectors.joining("\n"));
                });
    }

    private VfsNode resolve(TransactionContext transaction, String absolutePath) {
        ResolvedPath routed = route(transaction, absolutePath);
        return resolve(transaction, routed.ownerId(), routed.path());
    }

    private VfsNode resolve(TransactionContext transaction, UUID ownerId, String absolutePath) {
        Optional<VfsNode> current = transaction.vfs().findChild(ownerId,
                Optional.empty(), "/");
        if (absolutePath.equals("/")) {
            return current.orElseThrow(() -> new IllegalStateException("VFS root is missing"));
        }
        for (String part : absolutePath.substring(1).split("/")) {
            VfsNode parent = current.orElseThrow(() ->
                    new IllegalArgumentException("Unknown VFS path: " + absolutePath));
            if (parent.type() != VfsNode.Type.DIRECTORY) {
                throw new IllegalArgumentException("Not a directory in path: " + absolutePath);
            }
            current = transaction.vfs().findChild(ownerId,
                    Optional.of(parent.nodeId()), part);
        }
        return current.orElseThrow(() ->
                new IllegalArgumentException("Unknown VFS path: " + absolutePath));
    }

    private ResolvedPath route(TransactionContext transaction, String absolutePath) {
        if (!isLocalAdministrator() || !absolutePath.startsWith("/Users/")) {
            return new ResolvedPath(user.userId(), absolutePath);
        }
        String remainder = absolutePath.substring("/Users/".length());
        int slash = remainder.indexOf('/');
        String username = slash < 0 ? remainder : remainder.substring(0, slash);
        String userPath = slash < 0 ? "/" : remainder.substring(slash);
        UserAccount target = transaction.auth().findUsersByAdministrator(user.userId()).stream()
                .filter(candidate -> candidate.username().equalsIgnoreCase(username))
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("Unknown VFS path: " + absolutePath));
        return new ResolvedPath(target.userId(), userPath);
    }

    private record ResolvedPath(UUID ownerId, String path) {}

    private boolean isLocalAdministrator() {
        return user.username().equals("local") && isAdmin();
    }

    private String await(long pid) {
        while (true) {
            TerminalReplService.Snapshot latest = repl.active(user.userId(), sessionId)
                    .filter(value -> value.pid() == pid)
                    .orElseGet(() -> snapshot(pid));
            if (terminal(latest.status()) || latest.status() == CilProcess.Status.PAUSED) {
                return renderFinished(latest);
            }
            if (latest.status() == CilProcess.Status.WAITING_INPUT) {
                return "";
            }
            LockSupport.parkNanos(POLL_INTERVAL.toNanos());
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return "PID " + pid + " continues in background (terminal interrupted)";
            }
        }
    }

    private TerminalReplService.Snapshot snapshot(long pid) {
        CilProcess process = findProcess(pid);
        return new TerminalReplService.Snapshot(pid, process.status(), null, Map.of(),
                process.status() == CilProcess.Status.FAILED, false, List.of());
    }

    private String renderFinished(TerminalReplService.Snapshot snapshot) {
        if (snapshot.failed()) {
            String detail = snapshot.errors().isEmpty() ? "FCL execution failed"
                    : String.join("\n", snapshot.errors());
            return "error in PID " + snapshot.pid() + ": " + detail;
        }
        return snapshot.result() == null ? "" : repl.render(snapshot.result());
    }

    private CilProcess findProcess(long pid) {
        return transactions.inUserTransaction(user.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.processes().findByPid(pid)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown PID " + pid)));
    }

    private static boolean terminal(CilProcess.Status status) {
        return status == CilProcess.Status.TERMINATED || status == CilProcess.Status.FAILED
                || status == CilProcess.Status.FAILED_RECOVERY;
    }

    private static String help() {
        return """
                FCL input (default):
                  expression or statement       execute with every FCL namespace available
                  func/if/while blocks           continue on the ...> multiline prompt

                Terminal commands (prefix with :):
                  :help                          show this help
                  :cd <vfs-path>                 change the durable working directory
                  :pwd                           print the working directory
                  :ls [vfs-path]                 list a directory
                  :clear (:cls)                  clear the terminal screen
                  :logout                        return to login without losing REPL state
                  :exit (:quit)                  close only this terminal connection
                  :shutdown                      stop the shared Runtime (admin password required)

                Line editing:
                  Up/Down                        select earlier terminal commands
                  Left/Right                     move the cursor within the current line
                  Home/End                       jump to the start/end of the line
                  Ctrl-C                         cancel the current input

                FCL editor package (install it from the market first):
                  market.configure("https://market-origin")
                                                  set the market origin (once)
                  market.update()                download the index
                  market.install("<package-sha256>")
                                                  install the editor package by hash
                  import "<package-sha256>" as "editor"
                                                  import the exact package by hash
                  editor.open("notes.txt")       open or create a VFS text file
                  Ctrl-O/Ctrl-S save, Ctrl-X exit, Ctrl-W search, Ctrl-K cut line, Ctrl-U paste,
                  Ctrl-G help, mouse wheel scrolls

                Process, file, package, user, effect, and system operations are FCL functions.

                Attached input:
                  When a process waits in io.input(), the prompt changes to pid:? and the
                  next line is delivered verbatim instead of being parsed as FCL. Terminal
                  commands still start with :; use ::text to send raw input beginning with :.
                """.strip();
    }
}
