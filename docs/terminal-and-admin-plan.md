# Terminal + Administrator Global Management — Implementation Plan and Current State

Status: administrator VFS, cross-user access, and the FCL system APIs are **implemented**;
the durable interactive terminal is **implemented** (server, session, REPL, bootstrap)
Date: 2026-07-26 (rewritten in English and synced with current behavior: 2026-08-03)

> 2026-07-26 implementation note: `SYSTEM_ADMIN`, `AdminVfsService`, cross-user audit,
> VFS list/write/create/rename/constrained-delete, and the administrator FCL file
> functions are complete. Ordinary LOGIN roles remain under forced RLS. This document
> reflects the shipped design rather than the earlier planning sketch: the interactive
> terminal is delivered by the TCP `TerminalServer` plus `DatabaseTerminalControl`,
> process and package management are FCL functions rather than shell subcommands, and
> administrator VFS traversal is routed through `/Users/<username>/...` paths. FCL
> namespaces and security limits follow the repository root `README.md`.

---

## 0. Scope

1. Provide a durable interactive terminal (`cilexec>` REPL) that executes FCL with every namespace available.
2. Allow scripts, process control, and VFS file operations from the terminal.
3. Let an administrator cross the user isolation boundary to view and manage every user's processes and VFS, under full audit.

---

## 1. Current State

### 1.1 Implemented Components

| Component | File | Status |
|---|---|---|
| `TerminalServer` (TCP session server, port 8022 default) | `terminal/TerminalServer.java` | Implemented |
| `TerminalAccessService` (login, registration, rate limiting) | `terminal/TerminalAccessService.java` | Implemented |
| `TerminalBootstrap` (admin account provisioning) | `terminal/TerminalBootstrap.java` | Implemented |
| `DatabaseTerminalControl` (durable control plane + FCL REPL) | `terminal/DatabaseTerminalControl.java` | Implemented |
| `TerminalReplService` (durable FCL process submissions) | `application/TerminalReplService.java` | Implemented |
| `TerminalConsole` (host console adapter) | `terminal/TerminalConsole.java` | Implemented |
| `ShellCommand` / `ShellCommandParser` (colon commands) | `terminal/ShellCommand*.java` | Implemented |
| `TerminalService` (sessions, history, input submission) | `terminal/TerminalService.java` | Implemented |
| `AdminVfsService` (cross-user VFS) | `vfs/AdminVfsService.java` | Implemented |
| `VfsService` (owner-scoped file CRUD) | `vfs/VfsService.java` | Implemented |
| `ProgramService` (FCL compilation) | `application/ProgramService.java` | Implemented |
| `ProcessService` (process lifecycle, ownership-checked control) | `application/ProcessService.java` | Implemented |
| `AuthService` (user creation) | `auth/AuthService.java` | Implemented |
| `Authorization` (capability gates) | `auth/Authorization.java` | Implemented |
| `RuntimeBootstrap.startTerminalServer()` (assembly) | `app/RuntimeBootstrap.java` | Implemented |

### 1.2 What the Earlier Plan Assumed and What Actually Shipped

The original plan sketched an in-process `TerminalConsole` with `TerminalControl`
commands such as `run`, `ps`, `inspect`, `admin-ps`, and `admin-kill`. The shipped
design keeps the durable terminal but moves almost all operations into FCL:

| Plan assumption | Shipped behavior |
|---|---|
| `CilExecShell` implements `TerminalControl` | `DatabaseTerminalControl` implements `TerminalControl`; process, file, package, user, effect, and system operations are FCL functions with every namespace available |
| Shell subcommands `run`, `ps`, `kill`, `inspect`, `pause`, `resume` | Express these as FCL (e.g. `process.fork`, `process.getList`, `process.kill`) or via the REPL; the terminal command set is deliberately small |
| Shell subcommands `admin-ps`, `admin-kill`, `admin-ls`, `admin-cat`, `admin-tree` | Administrator process control goes through FCL with `PROCESS_CONTROL_OWN`/`PROCESS_CONTROL_ANY`/`SYSTEM_ADMIN` rules; administrator VFS traversal uses `/Users/<username>/...` path routing |
| `TerminalControl` interface, no implementation | Implemented by `DatabaseTerminalControl` (interactive and headless variants) |
| One history per user, in memory | Per-user persistent history (most recent 200 entries) across reconnects and restarts |

---

## 2. Design Decisions

### 2.1 VFS Has No Global Tree

Each user keeps an independent root `/`. Administrators see every user's VFS by
traversing under `/Users/<username>/...` rather than by redesigning the VFS as one
global tree.

**Reasons:**
- Small change; the schema is untouched.
- Analogy to Docker namespaces — each container has its own `/`; the host
  (administrator) sees everything.
- RLS isolation is safe by default.

### 2.2 Administrator Traversal Mechanism

RLS policies are unchanged. Administrator operations do **not** go through
`SET LOCAL ROLE`; they run directly as the `cilexec_runtime` database role inside
explicit transactions and use the `*ByAdministrator` repository/service methods,
which are backed by forced-RLS policies covering administrator access. The Runtime
still re-checks the actor's `SYSTEM_ADMIN` capability, and every cross-user
operation appends an audit event.

**Two execution paths:**

```text
normal user operations:
  JdbcTransactionExecutor.inUserTransaction(userId, ...)
    → SET LOCAL ROLE cilexec_user_xxx
    → RLS: owner_id = auth.current_cilexec_user_id()  → only own rows

administrator operations:
  explicit transactions as cilexec_runtime (no SET ROLE)
    → capability check (SYSTEM_ADMIN)
    → AdminVfsService / *ByAdministrator methods
    → RLS permits administrator policies; audit appended
```

The `local` administrator account additionally gets a stable `/Users` virtual
directory as the entry point for per-user home mounts.

### 2.3 The system_admin Capability

```sql
INSERT INTO auth.capability (capability_id, capability_key, description, system_capability)
VALUES ('00000000-0000-4000-8000-00000000000c', 'system_admin',
        'Manage all users, processes, VFS nodes, IPC, timers, and effects', true)
ON CONFLICT (capability_key) DO NOTHING;
```

Capability enforcement:

```java
// same-owner control → PROCESS_CONTROL_OWN; cross-owner → PROCESS_CONTROL_ANY;
// SYSTEM_ADMIN always allowed (mirrored by ProcessService and the FCL runtime)
Authorization.require(transaction, userId, capability);   // general gate
Authorization.requireAdministrator(transaction, userId);  // SYSTEM_ADMIN gate
```

`SYSTEM_ADMIN` satisfies every capability check. It never implies PostgreSQL
`BYPASSRLS` or cluster-superuser privileges.

---

## 3. Terminal Protocol and Session Behavior

### 3.1 Transport

`TerminalServer` is a TCP server (default port 8022, configurable via
`CILEXEC_TERMINAL_PORT`). Each connection is one terminal session bound to one
authenticated user. A headless variant (`DatabaseTerminalControl.headless`) binds a
named durable context for host tooling.

### 3.2 Session Lifecycle

- Sessions are durable: working directory, FCL process context, and command history
  are persisted and resume after reconnect or Runtime restart.
- **Disconnect detection.** The input pump treats end-of-stream on the socket as the
  authoritative disconnect signal and wakes the blocked session loop, so a
  disconnected client never keeps a session slot (or a polling loop) alive forever.
- **Idle timeout.** The socket read timeout is 60 seconds. A socket that never
  exchanged any byte is abandoned as a slot; sessions that have already exchanged
  bytes stay connected.
- `:exit` closes only the calling transport; `:logout` returns to the login prompt
  without losing REPL state; both leave the shared Runtime and background processes
  running.

### 3.3 Input Model

A control frame (`\0`-prefixed) carries interrupt and dimension events, so Ctrl+C
works while FCL is executing. The session supports:

- free-form **FCL input** (default mode): expressions and statements run as a new
  durable FCL process; `func`/`if`/`while` blocks continue on a `...>` multiline
  prompt;
- **attached input**: when a process waits in `io.input()`, the prompt changes to
  `pid:?` and the next line is delivered verbatim; terminal commands still start
  with `:`, and `::text` sends raw input beginning with `:`;
- key-mode input (`io.readKey`) with Up/Down history and Left/Right cursor editing.

### 3.4 Colon Commands

The terminal command set (prefix with `:`):

| Command | Effect |
| --- | --- |
| `:help` | Show terminal help. |
| `:cd <vfs-path>` | Change the durable working directory; the target must be a directory. `:cd` with no argument reports an error instead of crashing. |
| `:pwd` | Print the working directory. |
| `:ls [vfs-path]` | List the current or given directory; directory names end with `/`. |
| `:clear` | Clear the screen. |
| `:logout` | Return to login, keeping the user's terminal state and working directory. |
| `:exit` | Disconnect only this terminal connection; the shared Runtime and background processes continue. |
| `:shutdown` | Ask for the administrator password and stop the shared Runtime; only users with `SYSTEM_ADMIN` may use it. |

Plain `ls`/`cd` without the `:` prefix are rejected with a hint rather than
misparsed.

### 3.5 REPL Execution Model

Every FCL submission is a durable process: `TerminalReplService` compiles the
source, creates a process, enqueues it in the FIFO scheduler, and the terminal
polls the committed status until the process reaches a terminal state or starts
waiting for input. A top-level `return` ends the process and its value becomes the
program result rendered at the prompt. Terminal processes share the bounded worker
pools (default 10 scheduler workers, 6 effect workers) and the shared connection
pool; processes beyond the worker count wait in the durable queue. A terminal
process executes at most 4096 pure steps or 20 ms per scheduler slice, then
persists and re-enqueues.

---

## 4. Authentication and Administrator Bootstrap

### 4.1 Login and First Use

`TerminalAccessService` authenticates against application-owned credential
verifiers (never the host OS). On first use, `isFirstUse()` triggers bootstrap: the
`local` administrator account is created with `SYSTEM_ADMIN`. Ordinary registrations
receive `USER_CAPABILITIES` (PROCESS_CREATE, PROCESS_CONTROL_OWN, VFS_READ,
VFS_WRITE, PACKAGE_IMPORT, PACKAGE_BIND, EFFECT_REQUEST, TERMINAL_ATTACH,
AUDIT_READ).

### 4.2 Rate-Limited Password Verification

- Failed logins apply an exponential backoff (250 ms doubling), capped per
  principal; a shared `<unknown>` bucket with a lower ceiling absorbs unknown-username
  lookups so one attacker cannot slow unrelated logins.
- Unknown usernames and inactive accounts verify a **dummy credential** so response
  timing does not reveal account existence.
- Concurrent credential checks are bounded by a semaphore.
- Administrator password verification (used by `:shutdown` and by
  admin-account registration) goes through the same rate limiter.
- Passwords are read through `PasswordPrompt`, whose `Secret` buffer is
  deterministically zeroed when its lexical scope ends; usernames, passwords, and
  raw `io.input()` content never enter command history.

### 4.3 TerminalBootstrap Merges Capabilities

`TerminalBootstrap.ensure()` provisions the administrator account and its VFS
directory. It merges `SYSTEM_ADMIN` into the account's **existing** capability set
and never deletes capabilities assigned through other channels.

---

## 5. Administrator Capabilities and Services

### 5.1 AdminVfsService

Cross-user VFS operations (`AdminVfsService`) are constrained by PostgreSQL RLS even
for accounts holding the administrator capability, and every call appends an audit
event:

```text
adminListNodes / adminListDirectory   list a target user's tree
adminReadFile / adminReadFileByPath   read target user files
adminWriteFile / adminCreate*         create and write target user files
adminRename* / adminDeleteNode        rename and constrained-delete (no recursive
                                      deletion of non-empty directories)
```

### 5.2 Administrator FCL Functions

File functions accept an optional trailing target user, e.g.
`file.read("/home/a.txt", "alice")`; ordinary users may only reach their own files,
while `SYSTEM_ADMIN` holders may reach any user's files (each cross-user access
leaves an audit record). `system.*` functions, `user.*` user administration, and
`socket.bind`/`socket.accept` require administrator authority.

### 5.3 Process Control Ownership Rules

Process lifecycle operations check ownership before capability:

```text
same-owner process control → PROCESS_CONTROL_OWN
cross-owner process control → PROCESS_CONTROL_ANY
SYSTEM_ADMIN               → always allowed
```

`ProcessService.requireControl` mirrors the FCL runtime's `targetProcess` rule, so
the application boundary and the language boundary agree. Process listing locks rows
in `pid` order to keep list reads deadlock-free against statement transactions.

### 5.4 Cross-User Audit

Every administrator operation appends a structured audit event recording the actor
(the administrator), the target user, the action (`vfs.admin.*`, user
administration, process control, etc.), the result, and details JSON. Audit events
are retained per type and purged by the maintenance loop.

---

## 6. Database Changes

### 6.1 Baseline: system_admin Capability

```sql
-- administrator capability section of db/baseline/administrator_storage.sql
SET ROLE cilexec_owner;

INSERT INTO auth.capability (capability_id, capability_key, description, system_capability)
VALUES ('00000000-0000-4000-8000-00000000000c', 'system_admin',
        'Manage all users, processes, VFS nodes, IPC, timers, and effects', true)
ON CONFLICT (capability_key) DO NOTHING;

RESET ROLE;
```

### 6.2 No Schema Change for the Terminal

The terminal reuses the existing `terminal.*` session/input tables and the existing
process/scheduler machinery; no VFS, RLS, or process-table schema changes are
needed. The `system_admin` capability row and the `*ByAdministrator` RLS policies
are the only baseline additions.

---

## 7. File Change Checklist (Shipped)

| Operation | File |
|---|---|
| Implemented | `terminal/TerminalServer.java`, `TerminalAccessService.java`, `TerminalBootstrap.java`, `DatabaseTerminalControl.java`, `TerminalConsole.java`, `ShellCommand.java`, `ShellCommandParser.java`, `TerminalService.java`, `TerminalReplService.java` (application) |
| Implemented | `vfs/AdminVfsService.java`, `vfs/VfsService.java` |
| Implemented | `application/ProcessService.java` (ownership-checked control), `application/ProgramService.java` |
| Implemented | `auth/AuthService.java`, `auth/Authorization.java`, `auth/PasswordPolicy.java`, `auth/UsernamePolicy.java` |
| Implemented | `app/RuntimeBootstrap.java` (terminal assembly), `app/ApplicationCommand.java` |
| Baseline | `src/main/resources/db/baseline/administrator_storage.sql`, `terminal_runtime.sql`, `effect_terminal_audit.sql` |

---

## 8. Verification

- `mvn compile` succeeds.
- `mvn test` passes (JUnit 5 unit/lifecycle/crash-recovery suites; integration tests
  require a running PostgreSQL).
- Starting the Runtime brings up the terminal server; logging in presents the
  `cilexec>` prompt.
- `:help` lists the colon commands; FCL input evaluates as a durable process and a
  top-level `return` renders the program result.
- `:cd`, `:pwd`, `:ls` operate on the durable working directory; `:cd` without an
  argument reports an error without crashing.
- `:exit` disconnects only the caller; the shared Runtime keeps running. A client
  disconnect is detected instead of polling forever. A session is closed for
  idleness only when its attached process has been suspended (PAUSED) for the
  configured threshold (default 60 minutes, `CILEXEC_TERMINAL_IDLE_MINUTES`);
  active processes and full-screen programs waiting on input are never closed.
- First use bootstraps `local` with `SYSTEM_ADMIN`; `TerminalBootstrap` never
  removes pre-existing capabilities.
- Login and administrator-password failures are rate-limited (exponential backoff,
  dummy verification for unknown users).
- An administrator can list/read/write/rename/delete files under `/Users/<user>/...`
  and reach cross-user FCL file operations, with audit records; a non-administrator
  sees only own rows under forced RLS.
- Cross-owner process control requires `PROCESS_CONTROL_ANY` (or `SYSTEM_ADMIN`);
  same-owner requires `PROCESS_CONTROL_OWN`.
