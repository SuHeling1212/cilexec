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

/** Database-backed terminal control plane plus a durable, full-function FCL REPL. */
public final class DatabaseTerminalControl implements TerminalControl {
    private static final Duration FOREGROUND_WAIT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private final JdbcTransactionExecutor transactions;
    private final UserAccount user;
    private final TerminalService terminals;
    private final TerminalReplService repl;
    private final Runnable shutdown;
    private UUID sessionId;

    public DatabaseTerminalControl(JdbcTransactionExecutor transactions, UserAccount user,
                                   Runnable shutdown) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.user = java.util.Objects.requireNonNull(user, "user");
        this.shutdown = java.util.Objects.requireNonNull(shutdown, "shutdown");
        this.terminals = new TerminalService(transactions, Clock.systemUTC());
        this.repl = new TerminalReplService(transactions);
        this.sessionId = terminals.openOrResume(user.userId()).sessionId();
    }

    @Override
    public String execute(ShellCommand command) {
        java.util.Objects.requireNonNull(command, "command");
        return switch (command) {
            case ShellCommand.Help ignored -> help();
            case ShellCommand.ChangeDirectory cd -> changeDirectory(cd.path());
            case ShellCommand.WorkingDirectory ignored -> workingDirectory();
            case ShellCommand.ListDirectory ls -> listDirectory(ls.path());
            case ShellCommand.Logout ignored -> "logout requested";
            case ShellCommand.Exit ignored -> stop("terminal exited");
        };
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
    public String prompt() {
        Optional<TerminalReplService.Snapshot> active = repl.active(user.userId(), sessionId);
        if (active.isPresent() && active.orElseThrow().status() == CilProcess.Status.WAITING_INPUT) {
            return user.username() + "@cilexec[pid:" + active.orElseThrow().pid() + "]? ";
        }
        return user.username() + "@cilexec:" + workingDirectory() + "$ ";
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
            return replacement;
        });
    }

    private String workingDirectory() {
        return repl.workingDirectory(user.userId(), sessionId);
    }

    private String listDirectory(Optional<String> path) {
        return transactions.inUserTransaction(user.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    String current = transaction.terminal().workingDirectory(sessionId);
                    String requested = FclPath.resolve(current, path.orElse("."));
                    VfsNode directory = resolve(transaction, requested);
                    if (directory.type() != VfsNode.Type.DIRECTORY) {
                        throw new IllegalArgumentException("Not a directory: " + requested);
                    }
                    List<VfsNode> children = transaction.vfs().findChildren(user.userId(),
                            Optional.of(directory.nodeId()));
                    if (children.isEmpty()) return "";
                    return children.stream().map(node -> node.name()
                                    + (node.type() == VfsNode.Type.DIRECTORY ? "/" : ""))
                            .collect(java.util.stream.Collectors.joining("\n"));
                });
    }

    private VfsNode resolve(TransactionContext transaction, String absolutePath) {
        Optional<VfsNode> current = transaction.vfs().findChild(user.userId(),
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
            current = transaction.vfs().findChild(user.userId(),
                    Optional.of(parent.nodeId()), part);
        }
        return current.orElseThrow(() ->
                new IllegalArgumentException("Unknown VFS path: " + absolutePath));
    }

    private String stop(String message) {
        shutdown.run();
        return message;
    }

    private String await(long pid) {
        Instant deadline = Instant.now().plus(FOREGROUND_WAIT);
        TerminalReplService.Snapshot latest;
        int polls = 0;
        do {
            polls++;
            latest = repl.active(user.userId(), sessionId)
                    .filter(value -> value.pid() == pid)
                    .orElseGet(() -> snapshot(pid));
            if (terminal(latest.status()) || latest.status() == CilProcess.Status.PAUSED) {
                return renderFinished(latest);
            }
            if (latest.status() == CilProcess.Status.WAITING_INPUT) {
                return "PID " + pid + " is " + latest.status();
            }
            LockSupport.parkNanos(POLL_INTERVAL.toNanos());
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return "PID " + pid + " continues in background (terminal interrupted)";
            }
        } while (Instant.now().isBefore(deadline));
        return "PID " + pid + " continues in background (" + latest.status() + ")";
    }

    private TerminalReplService.Snapshot snapshot(long pid) {
        CilProcess process = findProcess(pid);
        return new TerminalReplService.Snapshot(pid, process.status(), null, Map.of(),
                process.status() == CilProcess.Status.FAILED, List.of());
    }

    private String renderFinished(TerminalReplService.Snapshot snapshot) {
        if (snapshot.failed()) {
            String detail = snapshot.errors().isEmpty() ? "FCL execution failed"
                    : String.join("\n", snapshot.errors());
            return "error in PID " + snapshot.pid() + ": " + detail;
        }
        return snapshot.result() == null ? "ok (PID " + snapshot.pid() + ")"
                : repl.render(snapshot.result());
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
                  :logout                        return to login without losing REPL state
                  :exit                          close the terminal and runtime

                Line editing:
                  Up/Down                        select earlier terminal commands
                  Left/Right                     move the cursor within the current line

                Process, file, package, user, effect, and system operations are FCL functions.

                Attached input:
                  When a process waits in io.input(), the prompt changes to pid:? and the
                  next line is delivered verbatim instead of being parsed as FCL. Terminal
                  commands still start with :; use ::text to send raw input beginning with :.
                """.strip();
    }
}
